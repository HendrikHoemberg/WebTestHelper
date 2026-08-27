package dev.hendrikhoemberg.webtesthelper.checks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure judgement logic for contact form detection, honeypot refusal, field classification,
 * and plausible test value generation (spec 7.2, D91).
 */
public final class ContactForms {

    public record HarvestedField(int index, String tag, String type, String name, String id,
                                 String label, String placeholder, String autocomplete,
                                 boolean required, String display, String visibility, double opacity,
                                 int width, int height, int x, int y, List<String> optionValues) {
        public HarvestedField {
            optionValues = optionValues == null ? List.of() : List.copyOf(optionValues);
        }
    }

    public record HarvestedForm(int index, String id, String action, String method, String role,
                                boolean captcha, List<HarvestedField> fields) {
        public HarvestedForm {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    public enum FieldKind {
        EMAIL, NAME, PHONE, SUBJECT, MESSAGE, COMPANY, ADDRESS, CHOICE, CONSENT, SUBMIT, SKIP
    }

    public record ClassifiedField(HarvestedField field, FieldKind kind) {}

    public record Outcome(boolean navigated, boolean formGone, String textBefore, String textAfter) {}

    public enum SubmitVerdict {
        SUCCESS, NO_INDICATOR, ERROR_SHOWN
    }

    public enum FormVerdict {
        CONTACT, SEARCH, NEWSLETTER, LOGIN, CAPTCHA, NONE
    }

    private static final List<String> SUCCESS_WORDS = List.of(
            "vielen dank", "danke", "erfolgreich", "gesendet", "versendet", "verschickt",
            "ubermittelt", "erhalten", "bestatigung", "wir melden uns", "nachricht ist unterwegs");

    private static final List<String> ERROR_WORDS = List.of(
            "fehler", "fehlgeschlagen", "konnte nicht", "nicht gesendet", "ungultig",
            "pflichtfeld", "bitte fullen sie", "versuchen sie es");

    private ContactForms() {
    }

    /**
     * Evaluates the outcome of submitting a contact form.
     * <ol>
     *   <li>The text gained an error word &rarr; {@code ERROR_SHOWN}</li>
     *   <li>Neither navigated nor form gone &rarr; {@code NO_INDICATOR}</li>
     *   <li>The text gained a success word &rarr; {@code SUCCESS}</li>
     *   <li>Otherwise &rarr; {@code NO_INDICATOR}</li>
     * </ol>
     */
    public static SubmitVerdict verdict(Outcome outcome) {
        if (outcome == null) {
            return SubmitVerdict.NO_INDICATOR;
        }

        String foldedBefore = fold(outcome.textBefore());
        String foldedAfter = fold(outcome.textAfter());

        boolean gainedError = ERROR_WORDS.stream()
                .anyMatch(w -> foldedAfter.contains(w) && !foldedBefore.contains(w));
        if (gainedError) {
            return SubmitVerdict.ERROR_SHOWN;
        }

        if (!outcome.navigated() && !outcome.formGone()) {
            return SubmitVerdict.NO_INDICATOR;
        }

        boolean gainedSuccess = SUCCESS_WORDS.stream()
                .anyMatch(w -> foldedAfter.contains(w) && !foldedBefore.contains(w));
        if (gainedSuccess) {
            return SubmitVerdict.SUCCESS;
        }

        return SubmitVerdict.NO_INDICATOR;
    }

    /**
     * Triages a harvested form according to priority rules:
     * <ol>
     *   <li>Password input present &rarr; {@code LOGIN}</li>
     *   <li>Search role, search input, or search action &rarr; {@code SEARCH}</li>
     *   <li>Captcha selector matched &rarr; {@code CAPTCHA}</li>
     *   <li>No textarea and at most one non-submit field &rarr; {@code NEWSLETTER}</li>
     *   <li>At least one textarea or &ge; 3 fillable non-hidden fields &rarr; {@code CONTACT}</li>
     *   <li>Otherwise &rarr; {@code NONE}</li>
     * </ol>
     */
    public static FormVerdict triage(HarvestedForm form) {
        if (form == null) {
            return FormVerdict.NONE;
        }

        List<HarvestedField> fields = form.fields();

        // 1. Password input present -> LOGIN
        if (fields.stream().anyMatch(ContactForms::isPassword)) {
            return FormVerdict.LOGIN;
        }

        // 2. Search role, search input, or search action -> SEARCH
        if (isSearchRole(form.role()) || fields.stream().anyMatch(ContactForms::isSearch) || isSearchAction(form.action())) {
            return FormVerdict.SEARCH;
        }

        // 3. Captcha selector matched -> CAPTCHA
        if (form.captcha()) {
            return FormVerdict.CAPTCHA;
        }

        boolean hasTextarea = fields.stream().anyMatch(ContactForms::isTextarea);
        long nonSubmitCount = fields.stream().filter(f -> !isSubmit(f)).count();

        // 4. No textarea and at most one non-submit field -> NEWSLETTER
        if (!hasTextarea && nonSubmitCount <= 1) {
            return FormVerdict.NEWSLETTER;
        }

        // 5. At least one textarea, or at least 3 fillable non-hidden fields -> CONTACT
        long fillableNonHiddenCount = fields.stream()
                .filter(f -> !hidden(f, 1366) && !isSubmit(f) && !"file".equalsIgnoreCase(f.type()))
                .count();

        if (hasTextarea || fillableNonHiddenCount >= 3) {
            return FormVerdict.CONTACT;
        }

        return FormVerdict.NONE;
    }

    /**
     * Selects the first form triaging {@code CONTACT} in document order.
     */
    public static Optional<HarvestedForm> choose(List<HarvestedForm> forms) {
        if (forms == null || forms.isEmpty()) {
            return Optional.empty();
        }
        for (HarvestedForm form : forms) {
            if (triage(form) == FormVerdict.CONTACT) {
                return Optional.of(form);
            }
        }
        return Optional.empty();
    }

    /**
     * Determines whether a field is hidden according to D91's geometric and computed style signals.
     */
    public static boolean hidden(HarvestedField field, int viewportWidth) {
        if (field == null) {
            return true;
        }
        if ("hidden".equalsIgnoreCase(field.type())) {
            return true;
        }
        if ("none".equalsIgnoreCase(field.display())) {
            return true;
        }
        if (field.visibility() == null || !"visible".equalsIgnoreCase(field.visibility().trim())) {
            return true;
        }
        if (field.opacity() < 0.1) {
            return true;
        }
        if (field.width() <= 0 || field.height() <= 0) {
            return true;
        }
        if (field.x() + field.width() < 0) {
            return true;
        }
        if (field.y() + field.height() < 0) {
            return true;
        }
        if (field.x() > viewportWidth) {
            return true;
        }
        return false;
    }

    /**
     * Classifies all fields in the form. Hidden fields and file inputs are classified as {@code SKIP}.
     */
    public static List<ClassifiedField> classify(HarvestedForm form, int viewportWidth) {
        if (form == null || form.fields() == null || form.fields().isEmpty()) {
            return List.of();
        }
        return form.fields().stream()
                .map(field -> new ClassifiedField(field, classifyField(field, viewportWidth)))
                .toList();
    }

    private static FieldKind classifyField(HarvestedField field, int viewportWidth) {
        if (hidden(field, viewportWidth) || "file".equalsIgnoreCase(field.type())) {
            return FieldKind.SKIP;
        }

        // Source 1: autocomplete
        if (field.autocomplete() != null && !field.autocomplete().isBlank()) {
            String auto = fold(field.autocomplete());
            List<String> autoTokens = extractTokens(field.autocomplete());
            if (auto.contains("email") || hasToken(autoTokens, "email")) {
                return FieldKind.EMAIL;
            }
            if (auto.contains("tel") || hasToken(autoTokens, "tel")) {
                return FieldKind.PHONE;
            }
            if (auto.contains("given-name") || auto.contains("family-name") || hasToken(autoTokens, "name")) {
                return FieldKind.NAME;
            }
            if (auto.contains("organization") || hasToken(autoTokens, "organization")) {
                return FieldKind.COMPANY;
            }
            if (auto.contains("street-address") || auto.contains("postal-code") || auto.contains("address-level2")) {
                return FieldKind.ADDRESS;
            }
        }

        // Source 2: type and tag
        String type = fold(field.type());
        String tag = fold(field.tag());
        if (type.equals("email")) {
            return FieldKind.EMAIL;
        }
        if (type.equals("tel")) {
            return FieldKind.PHONE;
        }
        if (tag.equals("textarea") || type.equals("textarea")) {
            return FieldKind.MESSAGE;
        }
        if (tag.equals("select") || type.equals("select") || type.equals("radio")) {
            return FieldKind.CHOICE;
        }
        if (type.equals("checkbox")) {
            return FieldKind.CONSENT;
        }
        if (type.equals("submit") || type.equals("button") || type.equals("image") || type.equals("reset") || tag.equals("button")) {
            return FieldKind.SUBMIT;
        }

        // Source 3: words in name + id
        List<String> nameAndIdTokens = extractTokens(field.name(), field.id());
        FieldKind byNameAndId = matchTokens(nameAndIdTokens);
        if (byNameAndId != null) {
            return byNameAndId;
        }

        // Source 4: words in label + placeholder
        List<String> labelAndPlaceholderTokens = extractTokens(field.label(), field.placeholder());
        FieldKind byLabelAndPlaceholder = matchTokens(labelAndPlaceholderTokens);
        if (byLabelAndPlaceholder != null) {
            return byLabelAndPlaceholder;
        }

        return null;
    }

    private static FieldKind matchTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }
        if (hasToken(tokens, "email") || hasToken(tokens, "mail") || hasTokenPrefix(tokens, "email") || hasTokenSequence(tokens, "e", "mail")) {
            return FieldKind.EMAIL;
        }
        if (hasToken(tokens, "tel") || hasToken(tokens, "telefon") || hasToken(tokens, "phone") || hasToken(tokens, "mobil")
                || hasTokenPrefix(tokens, "telefon") || hasTokenPrefix(tokens, "phone") || hasTokenPrefix(tokens, "mobil")) {
            return FieldKind.PHONE;
        }
        if (hasToken(tokens, "name") || hasToken(tokens, "vorname") || hasToken(tokens, "nachname") || hasToken(tokens, "username")
                || hasTokenPrefix(tokens, "vorname") || hasTokenPrefix(tokens, "nachname")) {
            return FieldKind.NAME;
        }
        if (hasToken(tokens, "unternehmen") || hasToken(tokens, "company") || hasToken(tokens, "firma")
                || hasTokenPrefix(tokens, "unternehmen") || hasTokenPrefix(tokens, "company") || hasTokenPrefix(tokens, "firma")) {
            return FieldKind.COMPANY;
        }
        if (hasToken(tokens, "strasse") || hasToken(tokens, "plz") || hasToken(tokens, "ort") || hasToken(tokens, "adresse")
                || hasTokenSuffix(tokens, "strasse") || hasTokenPrefix(tokens, "adresse") || hasTokenPrefix(tokens, "postleitzahl")) {
            return FieldKind.ADDRESS;
        }
        if (hasToken(tokens, "betreff") || hasToken(tokens, "subject") || hasToken(tokens, "thema")
                || hasTokenPrefix(tokens, "betreff") || hasTokenPrefix(tokens, "subject") || hasTokenPrefix(tokens, "thema")) {
            return FieldKind.SUBJECT;
        }
        if (hasToken(tokens, "nachricht") || hasToken(tokens, "message") || hasToken(tokens, "anliegen") || hasToken(tokens, "kommentar")
                || hasTokenPrefix(tokens, "nachricht") || hasTokenPrefix(tokens, "message") || hasTokenPrefix(tokens, "anliegen") || hasTokenPrefix(tokens, "kommentar")) {
            return FieldKind.MESSAGE;
        }
        return null;
    }

    /**
     * Generates a stable, plausible test value for a classified field.
     */
    public static String plausible(ClassifiedField classified, String email, String token) {
        if (classified == null || classified.kind() == null) {
            return null;
        }
        return switch (classified.kind()) {
            case EMAIL -> email;
            case NAME -> "WebTestHelper Prüfung";
            case PHONE -> "030 123456789";
            case COMPANY -> "WebTestHelper (Testeintrag)";
            case ADDRESS -> plausibleAddress(classified.field());
            case SUBJECT -> "Automatische Prüfung – bitte ignorieren";
            case MESSAGE -> "Dies ist eine automatische Testnachricht von WebTestHelper. Bitte ignorieren. Kennung: " + token;
            case CHOICE -> plausibleChoice(classified.field());
            case CONSENT -> "checked";
            case SUBMIT, SKIP -> null;
        };
    }

    private static String plausibleAddress(HarvestedField field) {
        if (field == null) {
            return "Teststraße 1";
        }
        String auto = fold(field.autocomplete());
        if (auto.contains("postal-code")) {
            return "10115";
        }
        if (auto.contains("address-level2")) {
            return "Berlin";
        }

        List<String> tokens = extractTokens(field.name(), field.id(), field.label(), field.placeholder());

        if (hasToken(tokens, "plz") || hasTokenPrefix(tokens, "postleitzahl")) {
            return "10115";
        }
        if (hasToken(tokens, "ort") || hasToken(tokens, "stadt") || hasTokenPrefix(tokens, "wohnort")) {
            return "Berlin";
        }
        return "Teststraße 1";
    }

    private static String plausibleChoice(HarvestedField field) {
        if (field == null || field.optionValues() == null) {
            return null;
        }
        for (String opt : field.optionValues()) {
            if (opt != null && !opt.isBlank()) {
                return opt;
            }
        }
        return null;
    }

    private static boolean isPassword(HarvestedField field) {
        return "password".equalsIgnoreCase(field.type());
    }

    private static boolean isSearch(HarvestedField field) {
        return "search".equalsIgnoreCase(field.type());
    }

    private static boolean isTextarea(HarvestedField field) {
        return "textarea".equalsIgnoreCase(field.tag()) || "textarea".equalsIgnoreCase(field.type());
    }

    private static boolean isSubmit(HarvestedField field) {
        if ("button".equalsIgnoreCase(field.tag())) {
            return true;
        }
        String type = field.type();
        return "submit".equalsIgnoreCase(type)
                || "button".equalsIgnoreCase(type)
                || "image".equalsIgnoreCase(type)
                || "reset".equalsIgnoreCase(type);
    }

    private static boolean isSearchRole(String role) {
        return role != null && fold(role).contains("search");
    }

    private static boolean isSearchAction(String action) {
        if (action == null || action.isBlank()) {
            return false;
        }
        String folded = fold(action);
        return folded.contains("such") || folded.contains("search");
    }

    private static List<String> extractTokens(String... sources) {
        List<String> tokens = new ArrayList<>();
        for (String source : sources) {
            if (source == null || source.isBlank()) {
                continue;
            }
            String camelSplit = source.replaceAll("([a-z])([A-Z])", "$1 $2");
            String folded = fold(camelSplit);
            for (String part : folded.split("[^a-z0-9]+")) {
                if (!part.isBlank()) {
                    tokens.add(part);
                }
            }
        }
        return tokens;
    }

    private static boolean hasToken(List<String> tokens, String target) {
        return tokens.contains(target);
    }

    private static boolean hasTokenPrefix(List<String> tokens, String prefix) {
        for (String token : tokens) {
            if (token.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTokenSuffix(List<String> tokens, String suffix) {
        for (String token : tokens) {
            if (token.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTokenSequence(List<String> tokens, String first, String second) {
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (tokens.get(i).equals(first) && tokens.get(i + 1).equals(second)) {
                return true;
            }
        }
        return false;
    }

    private static String fold(String text) {
        return LanguageSwitchers.fold(text);
    }
}
