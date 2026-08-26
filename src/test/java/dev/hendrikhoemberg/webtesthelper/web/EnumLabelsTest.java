package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.findings.ReportSection;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import dev.hendrikhoemberg.webtesthelper.reporting.TrafficLight;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that every enum constant that can be formatted dynamically in templates
 * (via #{'ui.' + enumClass + '.' + constant}) has a human-readable German label that is
 * not identical to the raw enum constant name (spec 13.1).
 */
class EnumLabelsTest {

    @Test
    void allEnumConstantsHaveGermanLabelsThatAreNotTheConstantName() {
        ResourceBundle bundle = ResourceBundle.getBundle("messages", Locale.GERMAN);

        List<Class<? extends Enum<?>>> enumClasses = List.of(
                RunStatus.class,
                RunScope.class,
                RunTrigger.class,
                ReportSection.class,
                Severity.class,
                TriageStatus.class,
                dev.hendrikhoemberg.webtesthelper.model.ObservedStatus.class,
                dev.hendrikhoemberg.webtesthelper.catalog.TlsMode.class,
                dev.hendrikhoemberg.webtesthelper.reporting.NotificationState.class,
                TrafficLight.class
        );

        for (Class<? extends Enum<?>> enumClass : enumClasses) {
            String prefix = "ui." + enumClass.getSimpleName().toLowerCase(Locale.ROOT) + ".";
            for (Enum<?> constant : enumClass.getEnumConstants()) {
                String key = prefix + constant.name();
                assertThat(bundle.containsKey(key))
                        .as("ResourceBundle must contain key '%s'", key)
                        .isTrue();

                String resolved = bundle.getString(key);
                assertThat(resolved)
                        .as("Value for key '%s' must not be blank", key)
                        .isNotBlank();
                assertThat(resolved)
                        .as("German translation for '%s' must not be the raw constant name '%s'", key, constant.name())
                        .isNotEqualTo(constant.name());
            }
        }
    }
}
