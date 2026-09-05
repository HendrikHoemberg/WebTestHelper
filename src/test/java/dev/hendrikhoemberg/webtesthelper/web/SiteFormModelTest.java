package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.model.FormTestMode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SiteFormModelTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void emptyProvidesSensibleDefaults() {
        SiteFormModel empty = SiteFormModel.empty();

        assertThat(empty.name()).isEmpty();
        assertThat(empty.baseUrl()).isEmpty();
        assertThat(empty.maxPages()).isEqualTo(300);
        assertThat(empty.maxDepth()).isEqualTo(5);
        assertThat(empty.maxDurationMinutes()).isEqualTo(30);
        assertThat(empty.formTestMode()).isEqualTo(FormTestMode.NO_SUBMIT.name());
        assertThat(empty.respectRobots()).isTrue();
        assertThat(empty.enabled()).isTrue();
    }

    @Test
    void toFormUsesFallbackDefaultsWhenBudgetFieldsAreNull() {
        SiteFormModel model = new SiteFormModel(
                "Test",
                "https://example.com",
                null,
                null,
                null,
                "",
                "",
                true,
                null,
                true,
                "",
                null
        );

        SiteForm form = model.toForm();

        assertThat(form.name()).isEqualTo("Test");
        assertThat(form.baseUrl()).isEqualTo("https://example.com");
        assertThat(form.maxPages()).isEqualTo(300);
        assertThat(form.maxDepth()).isEqualTo(5);
        assertThat(form.maxDuration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(form.formTestMode()).isEqualTo(FormTestMode.NO_SUBMIT);
    }

    @Test
    void toFormPreservesExplicitBudgetValues() {
        SiteFormModel model = new SiteFormModel(
                "Test",
                "https://example.com",
                50,
                2,
                15,
                "/a/*",
                "/b/*",
                false,
                "CustomBot",
                false,
                "https://example.com/page",
                "SUBMIT"
        );

        SiteForm form = model.toForm();

        assertThat(form.name()).isEqualTo("Test");
        assertThat(form.baseUrl()).isEqualTo("https://example.com");
        assertThat(form.maxPages()).isEqualTo(50);
        assertThat(form.maxDepth()).isEqualTo(2);
        assertThat(form.maxDuration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(form.includePatterns()).containsExactly("/a/*");
        assertThat(form.excludePatterns()).containsExactly("/b/*");
        assertThat(form.respectRobots()).isFalse();
        assertThat(form.userAgent()).isEqualTo("CustomBot");
        assertThat(form.enabled()).isFalse();
        assertThat(form.pinnedKeyPages()).containsExactly("https://example.com/page");
        assertThat(form.formTestMode()).isEqualTo(FormTestMode.SUBMIT);
    }

    @Test
    void validationFailsWhenRequiredFieldsAreMissingOrNull() {
        SiteFormModel model = new SiteFormModel(
                "",
                "",
                null,
                null,
                null,
                "",
                "",
                true,
                null,
                true,
                "",
                null
        );

        Set<ConstraintViolation<SiteFormModel>> violations = validator.validate(model);

        assertThat(violations).extracting(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .contains("name", "baseUrl", "maxPages", "maxDepth", "maxDurationMinutes");
    }

    @Test
    void validationFailsWhenBudgetValuesAreBelowMinimum() {
        SiteFormModel model = new SiteFormModel(
                "Name",
                "https://example.com",
                0,   // min 1
                -1,  // min 0
                0,   // min 1
                "",
                "",
                true,
                null,
                true,
                "",
                null
        );

        Set<ConstraintViolation<SiteFormModel>> violations = validator.validate(model);

        assertThat(violations).extracting(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .contains("maxPages", "maxDepth", "maxDurationMinutes");
    }
}
