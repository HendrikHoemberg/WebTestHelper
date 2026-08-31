package dev.hendrikhoemberg.webtesthelper.model;

import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrlVerificationTest {

    @Test
    void anUnreachableSnapshotWithATransientReasonIsUnverifiableNotDead() {
        PageSnapshot snapshot = Snapshots.page("https://example.com/x")
                .unreachable("net::ERR_NETWORK_CHANGED at https://example.com/x");

        UrlVerification verification = UrlVerification.ofSnapshot(snapshot);

        assertThat(verification.status()).isEqualTo(UrlStatus.UNVERIFIABLE);
        assertThat(verification.httpStatus()).isZero();
        assertThat(verification.failureText()).contains("ERR_NETWORK_CHANGED");
    }

    @Test
    void anUnreachableSnapshotWithAPageLevelReasonIsStillDead() {
        PageSnapshot snapshot = Snapshots.page("https://example.com/x")
                .unreachable("net::ERR_TOO_MANY_REDIRECTS at https://example.com/x");

        UrlVerification verification = UrlVerification.ofSnapshot(snapshot);

        assertThat(verification.status()).isEqualTo(UrlStatus.DEAD);
        assertThat(verification.failureText()).contains("ERR_TOO_MANY_REDIRECTS");
    }

    @Test
    void aReachableSnapshotCarriesItsHttpStatus() {
        PageSnapshot snapshot = Snapshots.page("https://example.com/x").status(404).build();

        UrlVerification verification = UrlVerification.ofSnapshot(snapshot);

        assertThat(verification.status()).isEqualTo(UrlStatus.DEAD);
        assertThat(verification.httpStatus()).isEqualTo(404);
    }
}
