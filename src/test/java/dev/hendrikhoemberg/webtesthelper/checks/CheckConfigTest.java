package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckConfigTest {

    private static final RunFacts FACTS = Snapshots.facts();
    private static final PageCheck CHECK = new PageStatusCheck();

    private static CheckConfig config(Map<String, Object> options) {
        return new CheckConfig(CHECK.defaultSeverity(), options, FACTS);
    }

    @Test
    void aLongStoredValueSurvivesWithoutACastToInteger() {
        // Jackson hands jsonb numbers to LinkedHashMap as integers or longs depending on size;
        // a check must read them through Number, never through (Integer).
        assertThat(config(Map.of("maxDistance", Long.valueOf(4096)))
                .option("maxDistance", 16)).isEqualTo(4096);
    }

    @Test
    void aMissingOptionFallsBack() {
        assertThat(config(Map.of()).option("maxDistance", 16)).isEqualTo(16);
    }

    @Test
    void aNonNumericOptionFallsBack() {
        assertThat(config(Map.of("maxDistance", "viel")).option("maxDistance", 16)).isEqualTo(16);
    }

    @Test
    void aListOptionBecomesPlainStringsEvenForMixedElementTypes() {
        List<Object> values = Arrays.asList("analytics", 42, Long.valueOf(7), true, null);

        assertThat(config(Map.of("ignorePatterns", values)).optionList("ignorePatterns"))
                .containsExactly("analytics", "42", "7", "true");
    }

    @Test
    void aMissingListOptionIsEmpty() {
        assertThat(config(Map.of()).optionList("ignorePatterns")).isEmpty();
    }

    @Test
    void aNullOptionValueIsDroppedRatherThanBreakingConstruction() {
        // jsonb can carry {"maxDistance": null} — an easy admin mistake at the config screen.
        Map<String, Object> options = new HashMap<>();
        options.put("maxDistance", null);

        CheckConfig config = config(options);

        assertThat(config.options()).isEmpty();
        assertThat(config.option("maxDistance", 16)).isEqualTo(16);
    }

    @Test
    void severityIsMandatory() {
        assertThatThrownBy(() -> new CheckConfig(null, Map.of(), FACTS))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void factsAreMandatory() {
        assertThatThrownBy(() -> new CheckConfig(Severity.ERROR, Map.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullOptionsBecomeAnEmptyMap() {
        assertThat(config(null).options()).isEmpty();
    }
}