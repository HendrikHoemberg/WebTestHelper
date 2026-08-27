package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class FormTestModeTest {

    @Test
    void submitsReturnsTrueOnlyWhenModeIsNotNoSubmit() {
        assertThat(FormTestMode.NO_SUBMIT.submits()).isFalse();
        assertThat(FormTestMode.SUBMIT.submits()).isTrue();
        assertThat(FormTestMode.SUBMIT_AND_VERIFY_MAIL.submits()).isTrue();
    }

    @ParameterizedTest(name = "{0} effectiveFor {1} -> {2}")
    @CsvSource({
            "NO_SUBMIT, PULSE, NO_SUBMIT",
            "NO_SUBMIT, FULL, NO_SUBMIT",
            "NO_SUBMIT, DEEP, NO_SUBMIT",
            "SUBMIT, PULSE, NULL",
            "SUBMIT, FULL, NULL",
            "SUBMIT, DEEP, SUBMIT",
            "SUBMIT_AND_VERIFY_MAIL, PULSE, NULL",
            "SUBMIT_AND_VERIFY_MAIL, FULL, NULL",
            "SUBMIT_AND_VERIFY_MAIL, DEEP, SUBMIT_AND_VERIFY_MAIL"
    })
    void effectiveForEvaluatesAllNineCombinations(FormTestMode mode, RunScope scope, String expectedName) {
        FormTestMode expected = "NULL".equals(expectedName) ? null : FormTestMode.valueOf(expectedName);
        assertThat(mode.effectiveFor(scope)).isEqualTo(expected);
    }
}
