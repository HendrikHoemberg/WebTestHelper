package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprintTest {

    @Test
    void isStableAndHexadecimal() {
        String a = Fingerprint.of(1, CheckType.DEAD_LINK, "https://e.com/a", "/a");
        String b = Fingerprint.of(1, CheckType.DEAD_LINK, "https://e.com/a", "/a");
        assertThat(a).isEqualTo(b);
        assertThat(a).matches("[0-9a-f]{64}");
    }

    @Test
    void differsAcrossSitesForTheSameSubject() {
        String site1 = Fingerprint.of(1, CheckType.DEAD_LINK, "https://e.com/a", "/a");
        String site2 = Fingerprint.of(2, CheckType.DEAD_LINK, "https://e.com/a", "/a");
        assertThat(site1).isNotEqualTo(site2);
    }

    @Test
    void separatorIsNotSpliceable() {
        String first = Fingerprint.of(1, CheckType.DEAD_LINK, "a\0b", "c");
        String second = Fingerprint.of(1, CheckType.DEAD_LINK, "a", "b\0c");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void distinguishesSiteWideFromPageScopedLocation() {
        String siteWide = Fingerprint.of(1, CheckType.DEAD_LINK, "https://e.com", "*");
        String pageScoped = Fingerprint.of(1, CheckType.DEAD_LINK, "https://e.com", "/");
        assertThat(siteWide).isNotEqualTo(pageScoped);
    }

    @Test
    void changesWithTheCheckType() {
        String pageStatus = Fingerprint.of(1, CheckType.PAGE_STATUS, "https://e.com/a", "/a");
        String deadLink = Fingerprint.of(1, CheckType.DEAD_LINK, "https://e.com/a", "/a");
        assertThat(pageStatus).isNotEqualTo(deadLink);
    }
}
