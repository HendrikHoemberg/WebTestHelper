package dev.hendrikhoemberg.webtesthelper.scheduling;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class TierCronTest {

    @Test
    void pulseComposesDailyDefaultCron() {
        assertThat(TierCron.compose(RunScope.PULSE, LocalTime.of(3, 0)))
                .isEqualTo("0 0 3 * * *");
    }

    @Test
    void fullComposesWeeklySundayDefaultCron() {
        assertThat(TierCron.compose(RunScope.FULL, LocalTime.of(3, 0)))
                .isEqualTo("0 0 3 * * SUN");
    }

    @Test
    void deepComposesMonthlyFirstDefaultCron() {
        assertThat(TierCron.compose(RunScope.DEEP, LocalTime.of(3, 0)))
                .isEqualTo("0 0 3 1 * *");
    }

    @Test
    void composeRoundTripsPulseAtQuarterPastTen() {
        assertThat(TierCron.compose(RunScope.PULSE, LocalTime.of(22, 45)))
                .isEqualTo("0 45 22 * * *");
        assertThat(TierCron.timeOfDay(RunScope.PULSE, "0 45 22 * * *"))
                .contains(LocalTime.of(22, 45));
    }

    @Test
    void composeRoundTripsFullOnSunday() {
        String cron = TierCron.compose(RunScope.FULL, LocalTime.of(9, 30));
        assertThat(cron).isEqualTo("0 30 9 * * SUN");
        assertThat(TierCron.timeOfDay(RunScope.FULL, cron)).contains(LocalTime.of(9, 30));
    }

    @Test
    void composeRoundTripsDeepOnFirst() {
        String cron = TierCron.compose(RunScope.DEEP, LocalTime.of(2, 15));
        assertThat(cron).isEqualTo("0 15 2 1 * *");
        assertThat(TierCron.timeOfDay(RunScope.DEEP, cron)).contains(LocalTime.of(2, 15));
    }

    @Test
    void aCronThatDoesNotFitTheTierShapeIsReportedEmpty() {
        assertThat(TierCron.timeOfDay(RunScope.PULSE, "0 0 3 * * MON,THU")).isEmpty();
        assertThat(TierCron.timeOfDay(RunScope.FULL, "0 0 3 * * *")).isEmpty();
        assertThat(TierCron.timeOfDay(RunScope.DEEP, "0 0 3 * * SUN")).isEmpty();
    }

    @Test
    void aNonZeroSecondsCronDoesNotFit() {
        assertThat(TierCron.timeOfDay(RunScope.PULSE, "30 0 3 * * *")).isEmpty();
    }

    @Test
    void anUnparseableCronIsReportedEmptyNotThrown() {
        assertThat(TierCron.timeOfDay(RunScope.PULSE, "keine ahnung")).isEmpty();
        assertThat(TierCron.timeOfDay(RunScope.PULSE, null)).isEmpty();
    }
}
