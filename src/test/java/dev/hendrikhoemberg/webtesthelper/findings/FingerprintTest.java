package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Fingerprint}.
 *
 * <p>The production join in {@link Fingerprint} separates its four fields with U+0001, which by
 * its domain contract never occurs in any valid input (site ids are decimal, CheckType names are
 * ASCII identifiers, subject/location keys are URL-derived). These tests therefore pin the
 * injective-join guarantee from the value side: bytes that ARE allowed inside a field — including
 * the NUL byte — must not be mistaken for a field boundary. The separator byte (U+0001) itself is
 * deliberately never smuggled in here; doing so would collide, because the join is only injective
 * while the separator stays outside the key domain (see Fingerprint's class javadoc).
 */
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
    void nonSeparatorBytesInValueDoNotSpliceFieldBoundaries() {
        String first = Fingerprint.of(1, CheckType.DEAD_LINK, "a\0b", "c");
        String second = Fingerprint.of(1, CheckType.DEAD_LINK, "a", "b\0c");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void nonSeparatorByteInValueDoesNotCollapseBoundary() {
        String first = Fingerprint.of(1, CheckType.DEAD_LINK, "x\0", "y");
        String second = Fingerprint.of(1, CheckType.DEAD_LINK, "x", "\0y");
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
