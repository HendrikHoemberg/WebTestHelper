package dev.hendrikhoemberg.webtesthelper.checks;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import dev.hendrikhoemberg.webtesthelper.checks.ContactForms.ClassifiedField;
import dev.hendrikhoemberg.webtesthelper.checks.ContactForms.FieldKind;
import dev.hendrikhoemberg.webtesthelper.checks.ContactForms.FormVerdict;
import dev.hendrikhoemberg.webtesthelper.checks.ContactForms.HarvestedField;
import dev.hendrikhoemberg.webtesthelper.checks.ContactForms.HarvestedForm;
import dev.hendrikhoemberg.webtesthelper.checks.ContactForms.Outcome;
import dev.hendrikhoemberg.webtesthelper.checks.ContactForms.SubmitVerdict;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.FormTestMode;
import dev.hendrikhoemberg.webtesthelper.model.Mailbox;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Contact form interaction check (spec 7.2, D77, D90, D91, D94, D103).
 *
 * <p>Harvests forms, selects the contact form, classifies its fields, and fills them with plausible
 * test values. In {@link FormTestMode#NO_SUBMIT} mode, verifies whether the form can validate and
 * rejects invalid emails, without submitting anything. In {@link FormTestMode#SUBMIT} mode, submits
 * the form and evaluates whether the submission succeeded. In {@link FormTestMode#SUBMIT_AND_VERIFY_MAIL}
 * mode, also awaits a verification token in the configured test mailbox.
 */
public final class ContactFormCheck implements InteractionCheck {

    static final String REJECTS_VALID = "finding.CONTACT_FORM.rejectsValid";
    static final String ACCEPTS_INVALID = "finding.CONTACT_FORM.acceptsInvalid";
    static final String NO_SUCCESS = "finding.CONTACT_FORM.noSuccess";
    static final String ERROR_SHOWN = "finding.CONTACT_FORM.errorShown";
    static final String NOT_DELIVERED = "finding.CONTACT_FORM.notDelivered";

    private static final String BASE32_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final Random RANDOM = new SecureRandom();

    /** Used only when no verification mailbox is configured; never a deliverable domain (RFC 2606). */
    private static final String FALLBACK_EMAIL = "pruefung@webtesthelper.invalid";

    private static final String SCRIPT;
    private static final String FORM_OUTCOME_SCRIPT;

    static {
        try {
            SCRIPT = new ClassPathResource("checks/contact-form.js")
                    .getContentAsString(StandardCharsets.UTF_8);
            FORM_OUTCOME_SCRIPT = new ClassPathResource("checks/form-outcome.js")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("checks/contact-form.js oder checks/form-outcome.js fehlt im Klassenpfad", e);
        }
    }

    private final Mailbox mailbox;

    public ContactFormCheck() {
        this(Mailbox.UNCONFIGURED);
    }

    public ContactFormCheck(Mailbox mailbox) {
        this.mailbox = mailbox != null ? mailbox : Mailbox.UNCONFIGURED;
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(90);
    }

    @Override
    public CheckType type() {
        return CheckType.CONTACT_FORM;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(REJECTS_VALID, ACCEPTS_INVALID, NO_SUCCESS, ERROR_SHOWN, NOT_DELIVERED);
    }

    @Override
    public List<NormalizedUrl> targets(RunSnapshots snapshots, SiteContext site, int maxTargets) {
        return InteractionTargets.withForm(snapshots, maxTargets);
    }

    @Override
    public List<CheckFinding> evaluate(Page page, SiteContext site, CheckConfig config) {
        if (page == null) {
            return List.of();
        }

        NormalizedUrl fallback = site != null ? site.baseUrl() : null;
        NormalizedUrl observedOn = page.url() != null
                ? UrlNormalizer.normalize(page.url()).orElse(fallback)
                : fallback;

        FormTestMode mode = site != null ? site.formTestMode() : FormTestMode.NO_SUBMIT;
        RunScope scope = (config != null && config.facts() != null) ? config.facts().scope() : RunScope.FULL;
        FormTestMode effectiveMode = mode.effectiveFor(scope);

        if (effectiveMode == null) {
            throw new CheckAbstainedException(type(), page.url(), "submit-modus ausserhalb des Tiefenlaufs");
        }

        if (effectiveMode == FormTestMode.SUBMIT_AND_VERIFY_MAIL) {
            if (mailbox.address() == null || mailbox.address().isBlank()) {
                throw new CheckAbstainedException(type(), page.url(), "kein Prüfpostfach konfiguriert");
            }
        }

        int viewportWidth = page.viewportSize() != null ? page.viewportSize().width : 1366;
        List<HarvestedForm> forms = harvest(page);
        Optional<HarvestedForm> chosen = ContactForms.choose(forms, viewportWidth);

        if (chosen.isEmpty()) {
            if (forms.stream().anyMatch(f -> ContactForms.triage(f, viewportWidth) == FormVerdict.CAPTCHA)) {
                throw new CheckAbstainedException(type(), page.url(), "Formular ist durch ein Captcha geschützt");
            }
            throw new CheckAbstainedException(type(), page.url(), "kein Kontaktformular auf der Seite");
        }

        HarvestedForm form = chosen.get();
        List<ClassifiedField> classified = ContactForms.classify(form, viewportWidth);

        // The verification mailbox's address in every mode that has one, not only when delivery is
        // being proved: a reply to the test message must reach us rather than a fictional recipient,
        // and a server-side validator that rejects the placeholder domain would report a healthy
        // form as broken. SUBMIT_AND_VERIFY_MAIL has already abstained above if it is blank.
        String mailboxAddress = mailbox.address();
        String email = mailboxAddress != null && !mailboxAddress.isBlank()
                ? mailboxAddress
                : FALLBACK_EMAIL;
        String token = mintToken();

        // The token has to reach the mailbox inside the message the form sends, so a form with no
        // field able to carry it cannot prove delivery. Saying so is the honest answer; filling it
        // anyway would report notDelivered every month against a form that works (D103).
        Optional<ClassifiedField> tokenCarrier = ContactForms.tokenCarrier(classified);
        if (effectiveMode == FormTestMode.SUBMIT_AND_VERIFY_MAIL && tokenCarrier.isEmpty()) {
            throw new CheckAbstainedException(type(), page.url(),
                    "kein Feld, das die Kennung zum Prüfpostfach tragen kann");
        }

        for (ClassifiedField cf : classified) {
            String value = ContactForms.plausible(cf, email, token);
            if (tokenCarrier.filter(carrier -> carrier == cf).isPresent()) {
                value = ContactForms.withToken(value, token);
            }
            if (value != null) {
                Locator el = page.locator("[data-wth-field='" + form.index() + "-" + cf.field().index() + "']");
                switch (cf.kind()) {
                    case CONSENT -> el.check();
                    case CHOICE -> {
                        if ("select".equalsIgnoreCase(cf.field().tag())) {
                            el.selectOption(value);
                        } else {
                            el.check();
                        }
                    }
                    case EMAIL, NAME, PHONE, COMPANY, ADDRESS, SUBJECT, MESSAGE -> el.fill(value);
                    case SUBMIT, SKIP -> {}
                }
            }
        }

        // 4. Read form.checkValidity()
        ValidityResult validityResult = checkFormValidity(page, form.index());
        String subjectKey = !form.id().isBlank() ? form.id() : (!form.action().isBlank() ? form.action() : "formular#" + form.index());
        Severity severity = config != null ? config.severity() : defaultSeverity();

        String location = observedOn != null ? observedOn.value() : page.url();
        List<CheckFinding> findings = new ArrayList<>(2);

        if (!validityResult.valid()) {
            // A form the browser will not let a visitor send cannot be submitted either, so this is
            // the one early return: pressing the button would only produce a second finding about
            // the same fault.
            return List.of(new CheckFinding(
                    type(),
                    severity,
                    subjectKey,
                    observedOn,
                    REJECTS_VALID,
                    List.of(location, validityResult.invalidFieldName()),
                    Evidence.NONE));
        }

        // 5. Check invalid email acceptance
        ClassifiedField emailField = classified.stream()
                .filter(cf -> cf.kind() == FieldKind.EMAIL)
                .findFirst()
                .orElse(null);

        if (emailField != null) {
            Locator emailEl = page.locator("[data-wth-field='" + form.index() + "-" + emailField.field().index() + "']");
            emailEl.fill("kein-at-zeichen");

            ValidityResult invalidEmailValidity = checkFormValidity(page, form.index());

            String plausibleEmail = ContactForms.plausible(emailField, email, token);
            if (plausibleEmail != null) {
                emailEl.fill(plausibleEmail);
            }

            if (invalidEmailValidity.valid() && emailField.field().required()) {
                // Collected, never returned: lax e-mail validation is a different question from
                // whether the form delivers, and answering it must not cost the site the submit
                // branch — a plain <input type="text" name="email"> is the commonest German form.
                findings.add(new CheckFinding(
                        type(),
                        Severity.WARN,
                        subjectKey,
                        observedOn,
                        ACCEPTS_INVALID,
                        List.of(location, "kein-at-zeichen"),
                        Evidence.NONE));
            }
        }

        if (effectiveMode.submits()) {
            String textBefore = getBodyText(page);

            ClassifiedField submitField = classified.stream()
                    .filter(cf -> cf.kind() == FieldKind.SUBMIT)
                    .findFirst()
                    .orElse(null);

            if (submitField == null) {
                throw new CheckAbstainedException(type(), page.url(), "kein Absende-Knopf gefunden");
            }

            String initialUrl = page.url();
            NormalizedUrl initialNormalized = initialUrl != null ? UrlNormalizer.normalize(initialUrl).orElse(null) : null;

            try {
                Locator submitEl = page.locator("[data-wth-field='" + form.index() + "-" + submitField.field().index() + "']");
                submitEl.click();

                try {
                    page.waitForLoadState();
                } catch (PlaywrightException ignored) {
                }
                page.waitForTimeout(500);

                String currentUrl = page.url();
                NormalizedUrl currentNormalized = currentUrl != null ? UrlNormalizer.normalize(currentUrl).orElse(null) : null;
                boolean navigated = !Objects.equals(initialNormalized, currentNormalized);
                boolean formGone = page.locator("[data-wth-form='" + form.index() + "']").count() == 0;
                String textAfter = getBodyText(page);

                Outcome outcome = new Outcome(navigated, formGone, textBefore, textAfter);
                SubmitVerdict verdict = ContactForms.verdict(outcome);

                if (verdict == SubmitVerdict.NO_INDICATOR) {
                    findings.add(new CheckFinding(
                            type(),
                            severity,
                            subjectKey,
                            observedOn,
                            NO_SUCCESS,
                            List.of(location),
                            Evidence.NONE));
                    return List.copyOf(findings);
                } else if (verdict == SubmitVerdict.ERROR_SHOWN) {
                    findings.add(new CheckFinding(
                            type(),
                            severity,
                            subjectKey,
                            observedOn,
                            ERROR_SHOWN,
                            List.of(location),
                            Evidence.NONE));
                    return List.copyOf(findings);
                }

                if (effectiveMode == FormTestMode.SUBMIT_AND_VERIFY_MAIL) {
                    Mailbox.Result result = mailbox.awaitToken(token, Duration.ofSeconds(60));
                    if (result == Mailbox.Result.UNAVAILABLE) {
                        // D89: Mailbox failure (wrong password, unreachable IMAP server, etc.) must abstain
                        throw new CheckAbstainedException(type(), page.url(), "Prüfpostfach nicht erreichbar");
                    } else if (result == Mailbox.Result.NOT_FOUND) {
                        String successText = extractSuccessText(outcome);
                        findings.add(new CheckFinding(
                                type(),
                                severity,
                                subjectKey,
                                observedOn,
                                NOT_DELIVERED,
                                List.of(location, successText, "60"),
                                Evidence.NONE));
                    }
                }
            } finally {
                if (initialUrl != null && !Objects.equals(page.url(), initialUrl)) {
                    try {
                        page.navigate(initialUrl);
                        page.waitForLoadState();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        return List.copyOf(findings);
    }

    private static String mintToken() {
        StringBuilder sb = new StringBuilder("WTH-");
        for (int i = 0; i < 12; i++) {
            sb.append(BASE32_ALPHABET.charAt(RANDOM.nextInt(BASE32_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String extractSuccessText(Outcome outcome) {
        if (outcome == null || outcome.textAfter() == null || outcome.textAfter().isBlank()) {
            return "";
        }
        String textBeforeFolded = outcome.textBefore() != null ? LanguageSwitchers.fold(outcome.textBefore()) : "";
        String[] lines = outcome.textAfter().split("\\R+");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String folded = LanguageSwitchers.fold(trimmed);
            boolean containsSuccess = ContactForms.SUCCESS_WORDS.stream().anyMatch(folded::contains);
            boolean wasInBefore = !textBeforeFolded.isEmpty() && textBeforeFolded.contains(folded);
            if (containsSuccess && !wasInBefore) {
                return trimmed;
            }
        }
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String folded = LanguageSwitchers.fold(trimmed);
            if (ContactForms.SUCCESS_WORDS.stream().anyMatch(folded::contains)) {
                return trimmed;
            }
        }
        return outcome.textAfter().trim();
    }

    private static String getBodyText(Page page) {
        if (page == null) {
            return "";
        }
        try {
            Object res = page.evaluate(FORM_OUTCOME_SCRIPT);
            return res != null ? res.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private record ValidityResult(boolean valid, String invalidFieldName) {}

    private static ValidityResult checkFormValidity(Page page, int formIndex) {
        try {
            Object res = page.evaluate("""
                    (fi) => {
                      const f = document.querySelector(`[data-wth-form="${fi}"]`);
                      if (!f) return { valid: true, invalidFieldName: '' };
                      if (f.checkValidity()) return { valid: true, invalidFieldName: '' };
                      for (const el of f.elements) {
                        if (!el.checkValidity()) {
                          const label = (el.labels && el.labels[0] ? el.labels[0].textContent : '').trim();
                          const name = label || el.name || el.id || '';
                          return { valid: false, invalidFieldName: name };
                        }
                      }
                      return { valid: false, invalidFieldName: '' };
                    }
                    """, formIndex);
            if (res instanceof Map<?, ?> map) {
                boolean valid = Boolean.TRUE.equals(map.get("valid"));
                String fieldName = map.get("invalidFieldName") != null ? map.get("invalidFieldName").toString() : "";
                return new ValidityResult(valid, fieldName);
            }
        } catch (PlaywrightException ignored) {
        }
        return new ValidityResult(true, "");
    }

    private static List<HarvestedForm> harvest(Page page) {
        if (page == null) {
            return List.of();
        }
        try {
            Object res = page.evaluate(SCRIPT);
            if (!(res instanceof List<?> formList)) {
                return List.of();
            }
            List<HarvestedForm> forms = new ArrayList<>(formList.size());
            for (Object fObj : formList) {
                if (!(fObj instanceof Map<?, ?> fMap)) continue;
                int formIndex = toInt(fMap.get("index"), 0);
                String id = toStr(fMap.get("id"));
                String action = toStr(fMap.get("action"));
                String method = toStr(fMap.get("method"));
                String role = toStr(fMap.get("role"));
                boolean captcha = Boolean.TRUE.equals(fMap.get("captcha"));

                List<HarvestedField> fields = new ArrayList<>();
                if (fMap.get("fields") instanceof List<?> fieldList) {
                    for (Object elObj : fieldList) {
                        if (!(elObj instanceof Map<?, ?> elMap)) continue;
                        int fieldIndex = toInt(elMap.get("index"), 0);
                        String tag = toStr(elMap.get("tag"));
                        String type = toStr(elMap.get("type"));
                        String name = toStr(elMap.get("name"));
                        String fieldId = toStr(elMap.get("id"));
                        String label = toStr(elMap.get("label"));
                        String placeholder = toStr(elMap.get("placeholder"));
                        String autocomplete = toStr(elMap.get("autocomplete"));
                        boolean required = Boolean.TRUE.equals(elMap.get("required"));
                        String display = toStr(elMap.get("display"));
                        String visibility = toStr(elMap.get("visibility"));
                        double opacity = toDouble(elMap.get("opacity"), 1.0);
                        int width = toInt(elMap.get("width"), 0);
                        int height = toInt(elMap.get("height"), 0);
                        int x = toInt(elMap.get("x"), 0);
                        int y = toInt(elMap.get("y"), 0);

                        List<String> optionValues = new ArrayList<>();
                        if (elMap.get("optionValues") instanceof List<?> optList) {
                            for (Object opt : optList) {
                                if (opt != null) optionValues.add(opt.toString());
                            }
                        }

                        fields.add(new HarvestedField(fieldIndex, tag, type, name, fieldId,
                                label, placeholder, autocomplete, required, display, visibility,
                                opacity, width, height, x, y, optionValues));
                    }
                }
                forms.add(new HarvestedForm(formIndex, id, action, method, role, captcha, fields));
            }
            return forms;
        } catch (PlaywrightException e) {
            return List.of();
        }
    }

    private static int toInt(Object obj, int fallback) {
        return obj instanceof Number n ? n.intValue() : fallback;
    }

    private static double toDouble(Object obj, double fallback) {
        return obj instanceof Number n ? n.doubleValue() : fallback;
    }

    private static String toStr(Object obj) {
        return obj != null ? obj.toString() : "";
    }
}
