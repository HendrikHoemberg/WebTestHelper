package dev.hendrikhoemberg.webtesthelper.recorder;

import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.recorder.CapturedEvent.EventKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepBuilderTest {

    private static final String START_URL = "http://localhost:8080/test";

    @Test
    void rejectsNullEventsList() {
        assertThatThrownBy(() -> StepBuilder.build(null, START_URL))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullStartUrl() {
        assertThatThrownBy(() -> StepBuilder.build(List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankStartUrl() {
        assertThatThrownBy(() -> StepBuilder.build(List.of(), "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyEventsYieldsSingleGotoStep() {
        List<JourneyStep> steps = StepBuilder.build(List.of(), START_URL);

        assertThat(steps).hasSize(1);
        JourneyStep gotoStep = steps.get(0);
        assertThat(gotoStep.id()).isNotNull();
        assertThat(gotoStep.ordinal()).isZero();
        assertThat(gotoStep.action()).isEqualTo(StepAction.GOTO);
        assertThat(gotoStep.value()).isEqualTo(START_URL);
        assertThat(gotoStep.locatorCandidates()).isEmpty();
        assertThat(gotoStep.assertion()).isNull();
        assertThat(gotoStep.optional()).isFalse();
        assertThat(gotoStep.timeoutMs()).isEqualTo(JourneyStep.DEFAULT_TIMEOUT_MS);
    }

    @Test
    void clickEventProducesClickStep() {
        CapturedEvent clickEvent = new CapturedEvent(
                EventKind.CLICK,
                "button",
                "btn-submit",
                "submit-btn",
                "button",
                "Absenden",
                null,
                "Absenden",
                null,
                "#btn-submit"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(clickEvent), START_URL);

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).action()).isEqualTo(StepAction.GOTO);

        JourneyStep step = steps.get(1);
        assertThat(step.id()).isNotNull();
        assertThat(step.ordinal()).isEqualTo(1);
        assertThat(step.action()).isEqualTo(StepAction.CLICK);
        assertThat(step.value()).isNull();
        assertThat(step.locatorCandidates()).isEqualTo(CandidateBuilder.build(clickEvent));
    }

    @Test
    void cookieBannerEventsAreFilteredOut() {
        CapturedEvent cookieEvent = new CapturedEvent(
                EventKind.CLICK,
                "div",
                "usercentrics-root",
                null,
                null,
                null,
                null,
                null,
                null,
                "#usercentrics-root"
        );
        CapturedEvent regularEvent = new CapturedEvent(
                EventKind.CLICK,
                "button",
                "btn-submit",
                "submit-btn",
                "button",
                "Absenden",
                null,
                "Absenden",
                null,
                "#btn-submit"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(cookieEvent, regularEvent), START_URL);

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).action()).isEqualTo(StepAction.GOTO);
        assertThat(steps.get(1).action()).isEqualTo(StepAction.CLICK);
        assertThat(steps.get(1).ordinal()).isEqualTo(1);
        assertThat(steps.get(1).locatorCandidates().get(0).value()).isEqualTo("submit-btn");
    }

    @Test
    void inputEventProducesFillStepWithValue() {
        CapturedEvent inputEvent = new CapturedEvent(
                EventKind.INPUT,
                "input",
                "email-field",
                "input-email",
                "textbox",
                "E-Mail-Adresse",
                "E-Mail",
                null,
                "user@example.com",
                "input#email-field"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(inputEvent), START_URL);

        assertThat(steps).hasSize(2);
        JourneyStep step = steps.get(1);
        assertThat(step.ordinal()).isEqualTo(1);
        assertThat(step.action()).isEqualTo(StepAction.FILL);
        assertThat(step.value()).isEqualTo("user@example.com");
        assertThat(step.locatorCandidates()).isEqualTo(CandidateBuilder.build(inputEvent));
    }

    @Test
    void changeEventProducesSelectStepWithValue() {
        CapturedEvent changeEvent = new CapturedEvent(
                EventKind.CHANGE,
                "select",
                "country-select",
                "select-country",
                "combobox",
                "Land",
                "Land auswählen",
                null,
                "DE",
                "select#country-select"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(changeEvent), START_URL);

        assertThat(steps).hasSize(2);
        JourneyStep step = steps.get(1);
        assertThat(step.ordinal()).isEqualTo(1);
        assertThat(step.action()).isEqualTo(StepAction.SELECT);
        assertThat(step.value()).isEqualTo("DE");
        assertThat(step.locatorCandidates()).isEqualTo(CandidateBuilder.build(changeEvent));
    }

    @Test
    void consecutiveInputEventsOnSameElementCollapseIntoSingleFillWithFinalValue() {
        CapturedEvent k1 = new CapturedEvent(
                EventKind.INPUT, "input", "query", "search-box", "textbox",
                "Suche", "Suchbegriff", null, "h", "input#query"
        );
        CapturedEvent k2 = new CapturedEvent(
                EventKind.INPUT, "input", "query", "search-box", "textbox",
                "Suche", "Suchbegriff", null, "he", "input#query"
        );
        CapturedEvent k3 = new CapturedEvent(
                EventKind.INPUT, "input", "query", "search-box", "textbox",
                "Suche", "Suchbegriff", null, "hel", "input#query"
        );
        CapturedEvent k4 = new CapturedEvent(
                EventKind.INPUT, "input", "query", "search-box", "textbox",
                "Suche", "Suchbegriff", null, "hello world", "input#query"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(k1, k2, k3, k4), START_URL);

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).action()).isEqualTo(StepAction.GOTO);

        JourneyStep step = steps.get(1);
        assertThat(step.ordinal()).isEqualTo(1);
        assertThat(step.action()).isEqualTo(StepAction.FILL);
        assertThat(step.value()).isEqualTo("hello world");
        assertThat(step.locatorCandidates()).isEqualTo(CandidateBuilder.build(k4));
    }

    @Test
    void nonConsecutiveInputEventsOnSameElementAreNotCollapsed() {
        CapturedEvent input1 = new CapturedEvent(
                EventKind.INPUT, "input", "query", "search-box", "textbox",
                "Suche", "Suchbegriff", null, "first", "input#query"
        );
        CapturedEvent click = new CapturedEvent(
                EventKind.CLICK, "button", "help-btn", "btn-help", "button",
                "Hilfe", null, "Hilfe", null, "button#help-btn"
        );
        CapturedEvent input2 = new CapturedEvent(
                EventKind.INPUT, "input", "query", "search-box", "textbox",
                "Suche", "Suchbegriff", null, "second", "input#query"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(input1, click, input2), START_URL);

        assertThat(steps).hasSize(4);
        assertThat(steps.get(0).action()).isEqualTo(StepAction.GOTO);
        assertThat(steps.get(1).action()).isEqualTo(StepAction.FILL);
        assertThat(steps.get(1).value()).isEqualTo("first");
        assertThat(steps.get(2).action()).isEqualTo(StepAction.CLICK);
        assertThat(steps.get(3).action()).isEqualTo(StepAction.FILL);
        assertThat(steps.get(3).value()).isEqualTo("second");
    }

    @Test
    void consecutiveInputEventsOnDifferentElementsAreNotCollapsed() {
        CapturedEvent input1 = new CapturedEvent(
                EventKind.INPUT, "input", "first-name", null, "textbox",
                "Vorname", "Vorname", null, "Alice", "input#first-name"
        );
        CapturedEvent input2 = new CapturedEvent(
                EventKind.INPUT, "input", "last-name", null, "textbox",
                "Nachname", "Nachname", null, "Smith", "input#last-name"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(input1, input2), START_URL);

        assertThat(steps).hasSize(3);
        assertThat(steps.get(1).action()).isEqualTo(StepAction.FILL);
        assertThat(steps.get(1).value()).isEqualTo("Alice");
        assertThat(steps.get(2).action()).isEqualTo(StepAction.FILL);
        assertThat(steps.get(2).value()).isEqualTo("Smith");
    }

    @Test
    void clickThenInputOnSameElementCollapsesIntoOneFillStep() {
        CapturedEvent click = new CapturedEvent(
                EventKind.CLICK, "input", "name", "input-name", "textbox",
                "Name", "Vorname", null, null, "input#name"
        );
        CapturedEvent input = new CapturedEvent(
                EventKind.INPUT, "input", "name", "input-name", "textbox",
                "Name", "Vorname", null, "Erika Mustermann", "input#name"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(click, input), START_URL);

        assertThat(steps).hasSize(2);
        JourneyStep step = steps.get(1);
        assertThat(step.ordinal()).isEqualTo(1);
        assertThat(step.action()).isEqualTo(StepAction.FILL);
        assertThat(step.value()).isEqualTo("Erika Mustermann");
    }

    @Test
    void clickThenChangeOnSameSelectCollapsesIntoOneSelectStep() {
        CapturedEvent click = new CapturedEvent(
                EventKind.CLICK, "select", "reise-ziel", null, "combobox",
                "Ziel", "Ziel", null, null, "select#reise-ziel"
        );
        CapturedEvent change = new CapturedEvent(
                EventKind.CHANGE, "select", "reise-ziel", null, "combobox",
                "Ziel", "Ziel", null, "paris", "select#reise-ziel"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(click, change), START_URL);

        assertThat(steps).hasSize(2);
        JourneyStep step = steps.get(1);
        assertThat(step.ordinal()).isEqualTo(1);
        assertThat(step.action()).isEqualTo(StepAction.SELECT);
        assertThat(step.value()).isEqualTo("paris");
    }

    @Test
    void changeOnNonSelectElementProducesFillStepNotSelect() {
        CapturedEvent change = new CapturedEvent(
                EventKind.CHANGE, "input", "name", null, "textbox",
                "Name", "Vorname", null, "Alice", "input#name"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(change), START_URL);

        assertThat(steps).hasSize(2);
        JourneyStep step = steps.get(1);
        assertThat(step.action()).isEqualTo(StepAction.FILL);
        assertThat(step.value()).isEqualTo("Alice");
    }

    @Test
    void changeFollowingInputOnSameTextFieldCollapsesIntoOneFillStep() {
        CapturedEvent input = new CapturedEvent(
                EventKind.INPUT, "input", "name", null, "textbox",
                "Name", "Vorname", null, "Alice", "input#name"
        );
        CapturedEvent change = new CapturedEvent(
                EventKind.CHANGE, "input", "name", null, "textbox",
                "Name", "Vorname", null, "Alice", "input#name"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(input, change), START_URL);

        assertThat(steps).hasSize(2);
        JourneyStep step = steps.get(1);
        assertThat(step.action()).isEqualTo(StepAction.FILL);
        assertThat(step.value()).isEqualTo("Alice");
    }

    @Test
    void consecutiveChangeOnSameSelectCollapsesIntoOneSelectStepWithFinalValue() {
        CapturedEvent c1 = new CapturedEvent(
                EventKind.CHANGE, "select", "ziel", null, "combobox",
                "Ziel", "Ziel", null, "berlin", "select#ziel"
        );
        CapturedEvent c2 = new CapturedEvent(
                EventKind.CHANGE, "select", "ziel", null, "combobox",
                "Ziel", "Ziel", null, "paris", "select#ziel"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(c1, c2), START_URL);

        assertThat(steps).hasSize(2);
        JourneyStep step = steps.get(1);
        assertThat(step.action()).isEqualTo(StepAction.SELECT);
        assertThat(step.value()).isEqualTo("paris");
    }

    @Test
    void submitFollowingClickOnSubmitButtonIsSuppressed() {
        CapturedEvent clickSubmit = new CapturedEvent(
                EventKind.CLICK, "button", "submit-btn", "btn-submit", "button",
                "Absenden", null, "Absenden", null, "form > button#submit-btn"
        );
        CapturedEvent formSubmit = new CapturedEvent(
                EventKind.SUBMIT, "form", "contact-form", "form-contact", "form",
                "Kontakt", null, null, null, "form#contact-form"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(clickSubmit, formSubmit), START_URL);

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).action()).isEqualTo(StepAction.GOTO);
        assertThat(steps.get(1).action()).isEqualTo(StepAction.CLICK);
        assertThat(steps.get(1).locatorCandidates()).isEqualTo(CandidateBuilder.build(clickSubmit));
    }

    @Test
    void standaloneSubmitEventProducesClickStep() {
        CapturedEvent formSubmit = new CapturedEvent(
                EventKind.SUBMIT, "form", "contact-form", "form-contact", "form",
                "Kontakt", null, null, null, "form#contact-form"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(formSubmit), START_URL);

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).action()).isEqualTo(StepAction.GOTO);
        assertThat(steps.get(1).action()).isEqualTo(StepAction.CLICK);
        assertThat(steps.get(1).locatorCandidates()).isEqualTo(CandidateBuilder.build(formSubmit));
    }

    @Test
    void passwordInputNeverCarriesPlaintextPasswordInBuiltSteps() {
        String plainSecret = "SuperSecretP@ssw0rd!2026";

        CapturedEvent pwdEventCss = new CapturedEvent(
                EventKind.INPUT, "input", "secret-field", null, "textbox",
                "Secret", "Secret", null, plainSecret, "form > input[type='password']"
        );
        CapturedEvent pwdEventId = new CapturedEvent(
                EventKind.INPUT, "input", "password", null, "textbox",
                "Passwort", "Passwort", null, plainSecret, "form > input#password"
        );
        CapturedEvent pwdEventLabel = new CapturedEvent(
                EventKind.INPUT, "input", "key-input", null, "textbox",
                "Passwort", "Passwort", null, plainSecret, "form > input#key-input"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(pwdEventCss, pwdEventId, pwdEventLabel), START_URL);

        assertThat(steps).hasSize(4); // GOTO + 3 FILL steps

        for (int i = 1; i <= 3; i++) {
            JourneyStep step = steps.get(i);
            assertThat(step.action()).isEqualTo(StepAction.FILL);
            assertThat(step.value()).isEmpty();
        }

        // Assert plaintext password appears nowhere in any step or field
        for (JourneyStep step : steps) {
            assertThat(step.value()).doesNotContain(plainSecret);
            assertThat(step.toString()).doesNotContain(plainSecret);
            for (LocatorCandidate candidate : step.locatorCandidates()) {
                assertThat(candidate.value()).doesNotContain(plainSecret);
            }
        }
    }

    @Test
    void consecutivePasswordInputsCollapseAndRedactValue() {
        String secret1 = "sec";
        String secret2 = "secret123";

        CapturedEvent p1 = new CapturedEvent(
                EventKind.INPUT, "input", "pwd", null, "textbox",
                "Passwort", "Passwort", null, secret1, "input#pwd"
        );
        CapturedEvent p2 = new CapturedEvent(
                EventKind.INPUT, "input", "pwd", null, "textbox",
                "Passwort", "Passwort", null, secret2, "input#pwd"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(p1, p2), START_URL);

        assertThat(steps).hasSize(2);
        JourneyStep fillStep = steps.get(1);
        assertThat(fillStep.action()).isEqualTo(StepAction.FILL);
        assertThat(fillStep.value()).isEmpty();
        assertThat(fillStep.toString()).doesNotContain(secret1);
        assertThat(fillStep.toString()).doesNotContain(secret2);
    }

    @Test
    void passwordInputWithUnrelatedNameIsRedactedWhenInputTypeIsPassword() {
        String secret = "SensitiveToken99";
        CapturedEvent pwdEvent = new CapturedEvent(
                EventKind.INPUT, "input", "txt-custom", "unrelated-testid", "textbox",
                "Code", "Code eingeben", null, secret, "form > div > input", "password"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(pwdEvent), START_URL);

        assertThat(steps).hasSize(2);
        JourneyStep fillStep = steps.get(1);
        assertThat(fillStep.action()).isEqualTo(StepAction.FILL);
        assertThat(fillStep.value()).isEmpty();
        assertThat(fillStep.toString()).doesNotContain(secret);
    }

    @Test
    void assignsDenseSequentialOrdinalsAndFreshUuids() {
        CapturedEvent e1 = new CapturedEvent(
                EventKind.INPUT, "input", "name", null, "textbox",
                "Name", "Name", null, "Alice", "input#name"
        );
        CapturedEvent e2 = new CapturedEvent(
                EventKind.CHANGE, "select", "color", null, "combobox",
                "Color", "Color", null, "blue", "select#color"
        );
        CapturedEvent e3 = new CapturedEvent(
                EventKind.CLICK, "button", "save", null, "button",
                "Save", null, "Save", null, "button#save"
        );

        List<JourneyStep> steps = StepBuilder.build(List.of(e1, e2, e3), START_URL);

        assertThat(steps).hasSize(4);
        assertThat(steps.stream().map(JourneyStep::ordinal).toList())
                .containsExactly(0, 1, 2, 3);

        Set<UUID> ids = steps.stream().map(JourneyStep::id).collect(Collectors.toSet());
        assertThat(ids).hasSize(4);
        assertThat(ids).doesNotContainNull();
    }
}
