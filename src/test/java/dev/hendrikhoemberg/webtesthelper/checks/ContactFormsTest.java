package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.checks.ContactForms.ClassifiedField;
import dev.hendrikhoemberg.webtesthelper.checks.ContactForms.FieldKind;
import dev.hendrikhoemberg.webtesthelper.checks.ContactForms.FormVerdict;
import dev.hendrikhoemberg.webtesthelper.checks.ContactForms.HarvestedField;
import dev.hendrikhoemberg.webtesthelper.checks.ContactForms.HarvestedForm;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure judgement tests for contact form triage, honeypot detection, field classification,
 * and plausible test values (plan 12, Task 1, D91).
 */
class ContactFormsTest {

    private static final int VIEWPORT_WIDTH = 1366;

    // --- Helper builders for test fixtures ---

    private static HarvestedField visibleField(int index, String tag, String type, String name, String id,
                                              String label, String placeholder, String autocomplete) {
        return new HarvestedField(index, tag, type, name, id, label, placeholder, autocomplete,
                false, "inline-block", "visible", 1.0, 185, 21, 46, 100, List.of());
    }

    private static HarvestedField visibleInput(int index, String type, String name, String label) {
        return visibleField(index, "input", type, name, name, label, "", "");
    }

    private static HarvestedField visibleTextarea(int index, String name, String label) {
        return new HarvestedField(index, "textarea", "textarea", name, name, label, "", "",
                false, "block", "visible", 1.0, 300, 150, 46, 200, List.of());
    }

    private static HarvestedField visibleSelect(int index, String name, List<String> options) {
        return new HarvestedField(index, "select", "select", name, name, "", "", "",
                false, "inline-block", "visible", 1.0, 185, 21, 46, 150, options);
    }

    private static HarvestedField submitButton(int index, String label) {
        return new HarvestedField(index, "button", "submit", "submit", "submit", label, "", "",
                false, "inline-block", "visible", 1.0, 100, 30, 46, 300, List.of());
    }

    // --- Triage Tests (Question 6 & Rules) ---

    @Test
    void triageLoginFormFirst() {
        // Login form asserted first because a bug here is the only one that types into a password field
        HarvestedForm loginForm = new HarvestedForm(0, "login", "/login", "POST", null, false, List.of(
                visibleInput(0, "text", "username", "Benutzername"),
                visibleInput(1, "password", "password", "Passwort"),
                submitButton(2, "Anmelden")
        ));

        assertThat(ContactForms.triage(loginForm)).isEqualTo(FormVerdict.LOGIN);
    }

    @Test
    void triageSearchFormByRole() {
        HarvestedForm searchForm = new HarvestedForm(0, "suche", "/find", "GET", "search", false, List.of(
                visibleInput(0, "text", "q", "Suche"),
                submitButton(1, "Suchen")
        ));

        assertThat(ContactForms.triage(searchForm)).isEqualTo(FormVerdict.SEARCH);
    }

    @Test
    void triageSearchFormBySearchInputType() {
        HarvestedForm searchForm = new HarvestedForm(0, "search-bar", "/results", "GET", null, false, List.of(
                visibleInput(0, "search", "query", "Search..."),
                submitButton(1, "Search")
        ));

        assertThat(ContactForms.triage(searchForm)).isEqualTo(FormVerdict.SEARCH);
    }

    @Test
    void triageSearchFormByActionWord() {
        HarvestedForm sucheAction = new HarvestedForm(0, "s", "/suche.php", "GET", null, false, List.of(
                visibleInput(0, "text", "term", "Begriff"),
                submitButton(1, "Los")
        ));
        HarvestedForm searchAction = new HarvestedForm(1, "s2", "/site-search", "GET", null, false, List.of(
                visibleInput(0, "text", "term", "Term"),
                submitButton(1, "Go")
        ));

        assertThat(ContactForms.triage(sucheAction)).isEqualTo(FormVerdict.SEARCH);
        assertThat(ContactForms.triage(searchAction)).isEqualTo(FormVerdict.SEARCH);
    }

    @Test
    void triageCaptchaForm() {
        HarvestedForm captchaForm = new HarvestedForm(0, "captcha", "/kontakt", "POST", null, true, List.of(
                visibleInput(0, "text", "name", "Name"),
                visibleInput(1, "email", "email", "E-Mail"),
                visibleTextarea(2, "message", "Nachricht"),
                submitButton(3, "Senden")
        ));

        assertThat(ContactForms.triage(captchaForm)).isEqualTo(FormVerdict.CAPTCHA);
    }

    @Test
    void triageNewsletterFormWithSingleEmailInput() {
        HarvestedForm newsletterForm = new HarvestedForm(0, "newsletter", "/subscribe", "POST", null, false, List.of(
                visibleInput(0, "email", "email", "E-Mail für Newsletter"),
                submitButton(1, "Abonnieren")
        ));

        assertThat(ContactForms.triage(newsletterForm)).isEqualTo(FormVerdict.NEWSLETTER);
    }

    @Test
    void triageNewsletterFormWithNoInputsOnlySubmit() {
        HarvestedForm buttonOnlyForm = new HarvestedForm(0, "like-form", "/like", "POST", null, false, List.of(
                submitButton(0, "Gefällt mir")
        ));

        assertThat(ContactForms.triage(buttonOnlyForm)).isEqualTo(FormVerdict.NEWSLETTER);
    }

    @Test
    void triageContactFormWithTextarea() {
        HarvestedForm contactForm = new HarvestedForm(0, "kaputt", "/kontakt", "POST", null, false, List.of(
                visibleInput(0, "text", "name", "Ihr Name"),
                visibleInput(1, "email", "email", "Ihre E-Mail"),
                visibleTextarea(2, "message", "Ihre Nachricht"),
                submitButton(3, "Absenden")
        ));

        assertThat(ContactForms.triage(contactForm)).isEqualTo(FormVerdict.CONTACT);
    }

    @Test
    void triageContactFormWithoutTextareaButThreeFillableFields() {
        HarvestedForm contactWithoutTextarea = new HarvestedForm(0, "kontakt-3fields", "/kontakt", "POST", null, false, List.of(
                visibleInput(0, "text", "name", "Name"),
                visibleInput(1, "email", "email", "E-Mail"),
                visibleInput(2, "text", "betreff", "Betreff"),
                submitButton(3, "Senden")
        ));

        assertThat(ContactForms.triage(contactWithoutTextarea)).isEqualTo(FormVerdict.CONTACT);
    }

    @Test
    void triageTwoFillableFieldsWithoutTextareaIsNone() {
        HarvestedForm twoFields = new HarvestedForm(0, "two-fields", "/submit", "POST", null, false, List.of(
                visibleInput(0, "text", "field1", "Feld 1"),
                visibleInput(1, "text", "field2", "Feld 2"),
                submitButton(2, "Senden")
        ));

        assertThat(ContactForms.triage(twoFields)).isEqualTo(FormVerdict.NONE);
    }

    // --- Choose Form Tests ---

    @Test
    void chooseSelectsFirstContactFormInDocumentOrder() {
        HarvestedForm search = new HarvestedForm(0, "search", "/search", "GET", "search", false, List.of());
        HarvestedForm login = new HarvestedForm(1, "login", "/login", "POST", null, false, List.of(
                visibleInput(0, "password", "pw", "PW")
        ));
        HarvestedForm contact1 = new HarvestedForm(2, "contact1", "/contact1", "POST", null, false, List.of(
                visibleTextarea(0, "msg", "Nachricht")
        ));
        HarvestedForm contact2 = new HarvestedForm(3, "contact2", "/contact2", "POST", null, false, List.of(
                visibleTextarea(0, "msg", "Nachricht")
        ));

        Optional<HarvestedForm> chosen = ContactForms.choose(List.of(search, login, contact1, contact2));

        assertThat(chosen).contains(contact1);
    }

    @Test
    void chooseIgnoresCaptchaAndReturnsEmptyWhenNoContactForm() {
        HarvestedForm search = new HarvestedForm(0, "search", "/search", "GET", "search", false, List.of());
        HarvestedForm captcha = new HarvestedForm(1, "captcha", "/contact", "POST", null, true, List.of(
                visibleTextarea(0, "msg", "Nachricht")
        ));

        Optional<HarvestedForm> chosen = ContactForms.choose(List.of(search, captcha));

        assertThat(chosen).isEmpty();
    }

    // --- Hidden / Honeypot Tests (Question 3 Table Oracle) ---

    @Test
    void hiddenRow1DisplayNone() {
        // rect 0x0, x 0, display "none", visibility "visible", opacity 1.0
        HarvestedField field = new HarvestedField(0, "input", "text", "website", "website",
                "", "", "", false, "none", "visible", 1.0, 0, 0, 0, 0, List.of());

        assertThat(ContactForms.hidden(field, VIEWPORT_WIDTH)).isTrue();
    }

    @Test
    void hiddenRow2PositionAbsoluteLeftMinus9999px() {
        // rect 185x21, x -9999, display "block", visibility "visible", opacity 1.0
        HarvestedField field = new HarvestedField(0, "input", "text", "fax", "fax",
                "", "", "", false, "block", "visible", 1.0, 185, 21, -9999, 0, List.of());

        assertThat(ContactForms.hidden(field, VIEWPORT_WIDTH)).isTrue();
    }

    @Test
    void hiddenRow3VisibilityHidden() {
        // rect 185x21, x 461, display "inline-block", visibility "hidden", opacity 1.0
        HarvestedField field = new HarvestedField(0, "input", "text", "url2", "url2",
                "", "", "", false, "inline-block", "hidden", 1.0, 185, 21, 461, 0, List.of());

        assertThat(ContactForms.hidden(field, VIEWPORT_WIDTH)).isTrue();
    }

    @Test
    void hiddenRow4OpacityZeroHeightZero() {
        // rect 177x0, x 650, display "inline-block", visibility "visible", opacity 0.0
        HarvestedField field = new HarvestedField(0, "input", "text", "company2", "company2",
                "", "", "", false, "inline-block", "visible", 0.0, 177, 0, 650, 0, List.of());

        assertThat(ContactForms.hidden(field, VIEWPORT_WIDTH)).isTrue();
    }

    @Test
    void hiddenRow5InputTypeHidden() {
        // <input type="hidden"> rect 0x0, x 0, display "none", visibility "visible", opacity 1.0
        HarvestedField field = new HarvestedField(0, "input", "hidden", "csrf", "csrf",
                "", "", "", false, "none", "visible", 1.0, 0, 0, 0, 0, List.of());

        assertThat(ContactForms.hidden(field, VIEWPORT_WIDTH)).isTrue();
    }

    @Test
    void hiddenRow6OrdinaryVisibleFieldIsNotHidden() {
        // rect 185x21, x 46, display "inline-block", visibility "visible", opacity 1.0
        HarvestedField field = new HarvestedField(0, "input", "text", "name", "name",
                "Name", "", "", false, "inline-block", "visible", 1.0, 185, 21, 46, 0, List.of());

        assertThat(ContactForms.hidden(field, VIEWPORT_WIDTH)).isFalse();
    }

    @Test
    void hiddenOffscreenRightBeyondViewport() {
        // x > viewportWidth (1400 > 1366)
        HarvestedField field = new HarvestedField(0, "input", "text", "trap", "trap",
                "", "", "", false, "inline-block", "visible", 1.0, 185, 21, 1400, 100, List.of());

        assertThat(ContactForms.hidden(field, VIEWPORT_WIDTH)).isTrue();
    }

    @Test
    void hiddenOffscreenTopAboveViewport() {
        // y + height < 0 (-100 + 20 = -80 < 0)
        HarvestedField field = new HarvestedField(0, "input", "text", "trap", "trap",
                "", "", "", false, "inline-block", "visible", 1.0, 185, 20, 100, -100, List.of());

        assertThat(ContactForms.hidden(field, VIEWPORT_WIDTH)).isTrue();
    }

    // --- Classification Tests ---

    @Test
    void classifyTypeEmailWinsOverNameNachricht() {
        HarvestedField field = visibleField(0, "input", "email", "nachricht", "nachricht", "", "", "");
        HarvestedForm form = new HarvestedForm(0, "f", "/submit", "POST", null, false, List.of(field));

        List<ClassifiedField> classified = ContactForms.classify(form, VIEWPORT_WIDTH);

        assertThat(classified).hasSize(1);
        assertThat(classified.get(0).kind()).isEqualTo(FieldKind.EMAIL);
    }

    @Test
    void classifyAutocompleteTelOnTextInputIsPhone() {
        HarvestedField field = visibleField(0, "input", "text", "phone_number", "phone_number", "", "", "tel");
        HarvestedForm form = new HarvestedForm(0, "f", "/submit", "POST", null, false, List.of(field));

        List<ClassifiedField> classified = ContactForms.classify(form, VIEWPORT_WIDTH);

        assertThat(classified).hasSize(1);
        assertThat(classified.get(0).kind()).isEqualTo(FieldKind.PHONE);
    }

    @Test
    void classifyTextareaWithNoNameIsMessage() {
        HarvestedField field = new HarvestedField(0, "textarea", "textarea", "", "",
                "", "", "", false, "block", "visible", 1.0, 300, 150, 46, 200, List.of());
        HarvestedForm form = new HarvestedForm(0, "f", "/submit", "POST", null, false, List.of(field));

        List<ClassifiedField> classified = ContactForms.classify(form, VIEWPORT_WIDTH);

        assertThat(classified).hasSize(1);
        assertThat(classified.get(0).kind()).isEqualTo(FieldKind.MESSAGE);
    }

    @Test
    void classifyStrasseAndStrasseClassifyTheSame() {
        HarvestedField fieldEszett = visibleField(0, "input", "text", "field1", "field1", "Straße", "", "");
        HarvestedField fieldSs = visibleField(1, "input", "text", "field2", "field2", "Strasse", "", "");
        HarvestedForm form = new HarvestedForm(0, "f", "/submit", "POST", null, false, List.of(fieldEszett, fieldSs));

        List<ClassifiedField> classified = ContactForms.classify(form, VIEWPORT_WIDTH);

        assertThat(classified).hasSize(2);
        assertThat(classified.get(0).kind()).isEqualTo(FieldKind.ADDRESS);
        assertThat(classified.get(1).kind()).isEqualTo(FieldKind.ADDRESS);
    }

    @Test
    void classifyHiddenFieldsAndFileInputsAreSkip() {
        HarvestedField hiddenHoneypot = new HarvestedField(0, "input", "text", "company", "company",
                "", "", "", false, "none", "visible", 1.0, 0, 0, 0, 0, List.of());
        HarvestedField fileInput = visibleField(1, "input", "file", "attachment", "attachment", "Anhang", "", "");
        HarvestedForm form = new HarvestedForm(0, "f", "/submit", "POST", null, false, List.of(hiddenHoneypot, fileInput));

        List<ClassifiedField> classified = ContactForms.classify(form, VIEWPORT_WIDTH);

        assertThat(classified).hasSize(2);
        assertThat(classified.get(0).kind()).isEqualTo(FieldKind.SKIP);
        assertThat(classified.get(1).kind()).isEqualTo(FieldKind.SKIP);
    }

    @Test
    void classifyUnclassifiableVisibleTextInputHasNullKind() {
        HarvestedField unclassifiable = visibleField(0, "input", "text", "custom_id_123", "custom_id_123",
                "Referenzcode", "XYZ", "");
        HarvestedForm form = new HarvestedForm(0, "f", "/submit", "POST", null, false, List.of(unclassifiable));

        List<ClassifiedField> classified = ContactForms.classify(form, VIEWPORT_WIDTH);

        assertThat(classified).hasSize(1);
        assertThat(classified.get(0).kind()).isNull();
    }

    @Test
    void classifyChoiceConsentSubmitNameCompanySubject() {
        HarvestedField nameField = visibleField(0, "input", "text", "vorname", "vorname", "Vorname", "", "");
        HarvestedField companyField = visibleField(1, "input", "text", "firma", "firma", "Firma", "", "organization");
        HarvestedField subjectField = visibleField(2, "input", "text", "betreff", "betreff", "Thema", "", "");
        HarvestedField choiceSelect = visibleSelect(3, "department", List.of("", "Support", "Sales"));
        HarvestedField consentBox = visibleField(4, "input", "checkbox", "privacy", "privacy", "Datenschutz", "", "");
        HarvestedField submitBtn = submitButton(5, "Absenden");

        HarvestedForm form = new HarvestedForm(0, "f", "/submit", "POST", null, false,
                List.of(nameField, companyField, subjectField, choiceSelect, consentBox, submitBtn));

        List<ClassifiedField> classified = ContactForms.classify(form, VIEWPORT_WIDTH);

        assertThat(classified).extracting(ClassifiedField::kind).containsExactly(
                FieldKind.NAME,
                FieldKind.COMPANY,
                FieldKind.SUBJECT,
                FieldKind.CHOICE,
                FieldKind.CONSENT,
                FieldKind.SUBMIT
        );
    }

    @Test
    void classifyDoesNotFalselyMatchSubstringsLikeSupportAntwortHotel() {
        HarvestedField supportField = visibleField(0, "input", "text", "support", "support", "Support", "Support", "");
        HarvestedField antwortField = visibleField(1, "input", "text", "antwort", "antwort", "Ihre Antwort", "Antwort", "");
        HarvestedField hotelField = visibleField(2, "input", "text", "hotel", "hotel", "Hotelname", "Hotel", "");
        HarvestedForm form = new HarvestedForm(0, "f", "/submit", "POST", null, false,
                List.of(supportField, antwortField, hotelField));

        List<ClassifiedField> classified = ContactForms.classify(form, VIEWPORT_WIDTH);

        assertThat(classified).hasSize(3);
        assertThat(classified.get(0).kind()).isNull();
        assertThat(classified.get(1).kind()).isNull();
        assertThat(classified.get(2).kind()).isNull();
    }

    // --- Plausible Values Tests ---

    @Test
    void plausibleValuesForStandardKinds() {
        String email = "test@example.com";
        String token = "WTH-ABC123XYZ456";

        ClassifiedField emailField = new ClassifiedField(visibleField(0, "input", "email", "email", "email", "", "", ""), FieldKind.EMAIL);
        ClassifiedField nameField = new ClassifiedField(visibleField(1, "input", "text", "name", "name", "", "", ""), FieldKind.NAME);
        ClassifiedField phoneField = new ClassifiedField(visibleField(2, "input", "tel", "phone", "phone", "", "", ""), FieldKind.PHONE);
        ClassifiedField companyField = new ClassifiedField(visibleField(3, "input", "text", "firma", "firma", "", "", ""), FieldKind.COMPANY);
        ClassifiedField subjectField = new ClassifiedField(visibleField(4, "input", "text", "betreff", "betreff", "", "", ""), FieldKind.SUBJECT);
        ClassifiedField consentField = new ClassifiedField(visibleField(5, "input", "checkbox", "agb", "agb", "", "", ""), FieldKind.CONSENT);
        ClassifiedField submitField = new ClassifiedField(submitButton(6, "Send"), FieldKind.SUBMIT);
        ClassifiedField skipField = new ClassifiedField(visibleField(7, "input", "file", "upload", "upload", "", "", ""), FieldKind.SKIP);
        ClassifiedField unclassified = new ClassifiedField(visibleField(8, "input", "text", "custom", "custom", "", "", ""), null);

        assertThat(ContactForms.plausible(emailField, email, token)).isEqualTo("test@example.com");
        assertThat(ContactForms.plausible(nameField, email, token)).isEqualTo("WebTestHelper Prüfung");
        assertThat(ContactForms.plausible(phoneField, email, token)).isEqualTo("030 123456789");
        assertThat(ContactForms.plausible(companyField, email, token)).isEqualTo("WebTestHelper (Testeintrag)");
        assertThat(ContactForms.plausible(subjectField, email, token)).isEqualTo("Automatische Prüfung – bitte ignorieren");
        assertThat(ContactForms.plausible(consentField, email, token)).isEqualTo("checked");
        assertThat(ContactForms.plausible(submitField, email, token)).isNull();
        assertThat(ContactForms.plausible(skipField, email, token)).isNull();
        assertThat(ContactForms.plausible(unclassified, email, token)).isNull();
    }

    @Test
    void plausibleAddressValuesChosenByMatchedWord() {
        String email = "test@example.com";
        String token = "WTH-TOKEN123";

        ClassifiedField streetField = new ClassifiedField(visibleField(0, "input", "text", "strasse", "strasse", "Straße", "", "street-address"), FieldKind.ADDRESS);
        ClassifiedField zipField = new ClassifiedField(visibleField(1, "input", "text", "plz", "plz", "PLZ", "", "postal-code"), FieldKind.ADDRESS);
        ClassifiedField cityField = new ClassifiedField(visibleField(2, "input", "text", "ort", "ort", "Ort", "", "address-level2"), FieldKind.ADDRESS);

        assertThat(ContactForms.plausible(streetField, email, token)).isEqualTo("Teststraße 1");
        assertThat(ContactForms.plausible(zipField, email, token)).isEqualTo("10115");
        assertThat(ContactForms.plausible(cityField, email, token)).isEqualTo("Berlin");
    }

    @Test
    void plausibleAddressDoesNotFalselyMatchSubstringsLikeSupport() {
        ClassifiedField supportAddressField = new ClassifiedField(
                visibleField(0, "input", "text", "support_address", "support_address", "Support Address", "", ""),
                FieldKind.ADDRESS);

        assertThat(ContactForms.plausible(supportAddressField, "test@example.com", "WTH-123"))
                .isEqualTo("Teststraße 1");
    }

    @Test
    void plausibleMessageContainsTokenAndNoOtherFieldContainsToken() {
        String email = "mail@example.com";
        String token = "WTH-TOKEN-42";

        ClassifiedField messageField = new ClassifiedField(visibleTextarea(0, "message", "Nachricht"), FieldKind.MESSAGE);
        String msgValue = ContactForms.plausible(messageField, email, token);

        assertThat(msgValue).isEqualTo("Dies ist eine automatische Testnachricht von WebTestHelper. Bitte ignorieren. Kennung: WTH-TOKEN-42");
        assertThat(msgValue).contains(token);

        // Verify token does not appear in any other standard plausible values
        ClassifiedField nameField = new ClassifiedField(visibleField(1, "input", "text", "name", "name", "", "", ""), FieldKind.NAME);
        ClassifiedField subjectField = new ClassifiedField(visibleField(2, "input", "text", "betreff", "betreff", "", "", ""), FieldKind.SUBJECT);
        ClassifiedField companyField = new ClassifiedField(visibleField(3, "input", "text", "firma", "firma", "", "", ""), FieldKind.COMPANY);

        assertThat(ContactForms.plausible(nameField, email, token)).doesNotContain(token);
        assertThat(ContactForms.plausible(subjectField, email, token)).doesNotContain(token);
        assertThat(ContactForms.plausible(companyField, email, token)).doesNotContain(token);
    }

    @Test
    void plausibleChoiceYieldsFirstNonBlankOptionOrNullIfOnlyBlank() {
        String email = "test@example.com";
        String token = "WTH-TOKEN";

        ClassifiedField choiceWithBlankOnly = new ClassifiedField(visibleSelect(0, "select1", List.of("", "   ")), FieldKind.CHOICE);
        ClassifiedField choiceWithOptions = new ClassifiedField(visibleSelect(1, "select2", List.of("", "Anfrage", "Support")), FieldKind.CHOICE);
        ClassifiedField choiceEmpty = new ClassifiedField(visibleSelect(2, "select3", List.of()), FieldKind.CHOICE);

        assertThat(ContactForms.plausible(choiceWithBlankOnly, email, token)).isNull();
        assertThat(ContactForms.plausible(choiceWithOptions, email, token)).isEqualTo("Anfrage");
        assertThat(ContactForms.plausible(choiceEmpty, email, token)).isNull();
    }

    @Test
    void plausibleValuesAreDeterministicAcrossMultipleCalls() {
        String email = "test@example.com";
        String token = "WTH-SAME-TOKEN";

        ClassifiedField messageField = new ClassifiedField(visibleTextarea(0, "message", "Nachricht"), FieldKind.MESSAGE);
        ClassifiedField nameField = new ClassifiedField(visibleField(1, "input", "text", "name", "name", "", "", ""), FieldKind.NAME);

        String msg1 = ContactForms.plausible(messageField, email, token);
        String msg2 = ContactForms.plausible(messageField, email, token);
        String name1 = ContactForms.plausible(nameField, email, token);
        String name2 = ContactForms.plausible(nameField, email, token);

        assertThat(msg1).isEqualTo(msg2);
        assertThat(name1).isEqualTo(name2);
    }
}
