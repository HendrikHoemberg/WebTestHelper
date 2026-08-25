package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TriageActionTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final int MAX_MUTE_DAYS = 365;

    @Test
    void mutedWithNullOrBlankOrWhitespaceReasonThrows() {
        Instant expiry = NOW.plus(90, ChronoUnit.DAYS);

        assertThatThrownBy(() -> TriageAction.of(TriageStatus.MUTED, null, expiry, NOW, MAX_MUTE_DAYS))
                .isInstanceOf(TriageValidationException.class)
                .satisfies(e -> assertThat(((TriageValidationException) e).messageKey()).containsIgnoringCase("reason"));

        assertThatThrownBy(() -> TriageAction.of(TriageStatus.MUTED, "", expiry, NOW, MAX_MUTE_DAYS))
                .isInstanceOf(TriageValidationException.class)
                .satisfies(e -> assertThat(((TriageValidationException) e).messageKey()).containsIgnoringCase("reason"));

        assertThatThrownBy(() -> TriageAction.of(TriageStatus.MUTED, "   \t\n  ", expiry, NOW, MAX_MUTE_DAYS))
                .isInstanceOf(TriageValidationException.class)
                .satisfies(e -> assertThat(((TriageValidationException) e).messageKey()).containsIgnoringCase("reason"));
    }

    @Test
    void mutedWithNullExpiryThrows() {
        assertThatThrownBy(() -> TriageAction.of(TriageStatus.MUTED, "Third-party service", null, NOW, MAX_MUTE_DAYS))
                .isInstanceOf(TriageValidationException.class);
    }

    @Test
    void mutedWithExpiryEqualToNowOrBeforeNowThrows() {
        assertThatThrownBy(() -> TriageAction.of(TriageStatus.MUTED, "Third-party service", NOW, NOW, MAX_MUTE_DAYS))
                .isInstanceOf(TriageValidationException.class);

        Instant past = NOW.minus(1, ChronoUnit.SECONDS);
        assertThatThrownBy(() -> TriageAction.of(TriageStatus.MUTED, "Third-party service", past, NOW, MAX_MUTE_DAYS))
                .isInstanceOf(TriageValidationException.class);
    }

    @Test
    void mutedWithExpiryBeyondMaxMuteDaysThrows() {
        Instant tooFar = NOW.plus(MAX_MUTE_DAYS + 1, ChronoUnit.DAYS);
        assertThatThrownBy(() -> TriageAction.of(TriageStatus.MUTED, "Third-party service", tooFar, NOW, MAX_MUTE_DAYS))
                .isInstanceOf(TriageValidationException.class);
    }

    @Test
    void mutedWithReasonAndExpiryKeepsBothVerbatim() {
        String reason = "  LinkedIn rate limit; check back in November.  ";
        Instant expiry = NOW.plus(90, ChronoUnit.DAYS);

        TriageAction action = TriageAction.of(TriageStatus.MUTED, reason, expiry, NOW, MAX_MUTE_DAYS);

        assertThat(action.target()).isEqualTo(TriageStatus.MUTED);
        assertThat(action.reason()).isEqualTo(reason);
        assertThat(action.mutedUntil()).isEqualTo(expiry);
    }

    @Test
    void wontFixWithBlankReasonThrows() {
        assertThatThrownBy(() -> TriageAction.of(TriageStatus.WONT_FIX, null, null, NOW, MAX_MUTE_DAYS))
                .isInstanceOf(TriageValidationException.class)
                .satisfies(e -> assertThat(((TriageValidationException) e).messageKey()).containsIgnoringCase("reason"));

        assertThatThrownBy(() -> TriageAction.of(TriageStatus.WONT_FIX, "", null, NOW, MAX_MUTE_DAYS))
                .isInstanceOf(TriageValidationException.class)
                .satisfies(e -> assertThat(((TriageValidationException) e).messageKey()).containsIgnoringCase("reason"));

        assertThatThrownBy(() -> TriageAction.of(TriageStatus.WONT_FIX, "   ", null, NOW, MAX_MUTE_DAYS))
                .isInstanceOf(TriageValidationException.class)
                .satisfies(e -> assertThat(((TriageValidationException) e).messageKey()).containsIgnoringCase("reason"));
    }

    @Test
    void acknowledgedWithBlankReasonIsAcceptedAndMutedUntilIsNull() {
        TriageAction blank = TriageAction.of(TriageStatus.ACKNOWLEDGED, "", null, NOW, MAX_MUTE_DAYS);
        assertThat(blank.target()).isEqualTo(TriageStatus.ACKNOWLEDGED);
        assertThat(blank.reason()).isEqualTo("");
        assertThat(blank.mutedUntil()).isNull();

        TriageAction withNull = TriageAction.of(TriageStatus.ACKNOWLEDGED, null, null, NOW, MAX_MUTE_DAYS);
        assertThat(withNull.target()).isEqualTo(TriageStatus.ACKNOWLEDGED);
        assertThat(withNull.reason()).isNull();
        assertThat(withNull.mutedUntil()).isNull();
    }

    @Test
    void untriagedReturnsNullReasonAndExpiryRegardlessOfInput() {
        Instant expiry = NOW.plus(30, ChronoUnit.DAYS);
        TriageAction action = TriageAction.of(TriageStatus.UNTRIAGED, "Some reason", expiry, NOW, MAX_MUTE_DAYS);

        assertThat(action.target()).isEqualTo(TriageStatus.UNTRIAGED);
        assertThat(action.reason()).isNull();
        assertThat(action.mutedUntil()).isNull();
    }

    @Test
    void expirySuppliedForAcknowledgedOrWontFixThrows() {
        Instant expiry = NOW.plus(30, ChronoUnit.DAYS);

        assertThatThrownBy(() -> TriageAction.of(TriageStatus.ACKNOWLEDGED, "Reason", expiry, NOW, MAX_MUTE_DAYS))
                .isInstanceOf(TriageValidationException.class);

        assertThatThrownBy(() -> TriageAction.of(TriageStatus.WONT_FIX, "Reason", expiry, NOW, MAX_MUTE_DAYS))
                .isInstanceOf(TriageValidationException.class);
    }
}
