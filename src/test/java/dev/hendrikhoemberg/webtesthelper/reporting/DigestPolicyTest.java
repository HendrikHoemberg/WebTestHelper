package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DigestPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-26T10:00:00Z");

    @Test
    void pulseDigestWithZeroErrorsAndNoFailureIsNotNotifiable() {
        SiteDigest site1 = siteDigest(1L, "Site A", RunStatus.COMPLETED, 0);
        SiteDigest site2 = siteDigest(2L, "Site B", RunStatus.COMPLETED, 0);

        Digest digest = new Digest(RunScope.PULSE, NOW, List.of(site1, site2));

        assertThat(digest.notifiable()).isFalse();
        assertThat(digest.allClear()).isTrue();
        assertThat(digest.errorTotal()).isZero();
        assertThat(digest.failedRuns()).isZero();
    }

    @Test
    void pulseDigestWithOneErrorOnOneSiteIsNotifiable() {
        SiteDigest site1 = siteDigest(1L, "Site A", RunStatus.COMPLETED, 0);
        SiteDigest site2 = siteDigest(2L, "Site B", RunStatus.COMPLETED, 1);

        Digest digest = new Digest(RunScope.PULSE, NOW, List.of(site1, site2));

        assertThat(digest.notifiable()).isTrue();
        assertThat(digest.allClear()).isFalse();
        assertThat(digest.errorTotal()).isEqualTo(1);
        assertThat(digest.failedRuns()).isZero();
    }

    @Test
    void pulseDigestWithFailedRunAloneIsNotifiableWithErrorTotalZero() {
        SiteDigest site1 = siteDigest(1L, "Site A", RunStatus.COMPLETED, 0);
        SiteDigest site2 = siteDigest(2L, "Site B", RunStatus.FAILED, 0);

        Digest digest = new Digest(RunScope.PULSE, NOW, List.of(site1, site2));

        assertThat(digest.notifiable()).isTrue();
        assertThat(digest.allClear()).isFalse();
        assertThat(digest.errorTotal()).isZero();
        assertThat(digest.failedRuns()).isEqualTo(1);
    }

    @Test
    void deepDigestWithNothingWrongIsNotifiableAndReportsAllClear() {
        SiteDigest site1 = siteDigest(1L, "Site A", RunStatus.COMPLETED, 0);
        SiteDigest site2 = siteDigest(2L, "Site B", RunStatus.COMPLETED, 0);

        Digest digest = new Digest(RunScope.DEEP, NOW, List.of(site1, site2));

        assertThat(digest.notifiable()).isTrue();
        assertThat(digest.allClear()).isTrue();
        assertThat(digest.errorTotal()).isZero();
        assertThat(digest.failedRuns()).isZero();
    }

    @Test
    void pulseDigestThatIsAllClearIsNotNotifiable() {
        SiteDigest site = siteDigest(1L, "Site A", RunStatus.COMPLETED, 0);
        Digest digest = new Digest(RunScope.PULSE, NOW, List.of(site));

        assertThat(digest.allClear()).isTrue();
        assertThat(digest.notifiable()).isFalse();
    }

    @Test
    void restrictedToFiltersSitesAndPreservesOrRemovesNotifiability() {
        // D56 case: site A has no issues, site B has an error
        SiteDigest siteA = siteDigest(1L, "Site A", RunStatus.COMPLETED, 0);
        SiteDigest siteB = siteDigest(2L, "Site B", RunStatus.COMPLETED, 1);

        Digest fullDigest = new Digest(RunScope.PULSE, NOW, List.of(siteA, siteB));
        assertThat(fullDigest.notifiable()).isTrue();

        Digest restrictedToA = fullDigest.restrictedTo(Set.of(1L));
        assertThat(restrictedToA.sites()).containsExactly(siteA);
        assertThat(restrictedToA.notifiable()).isFalse();
        assertThat(restrictedToA.allClear()).isTrue();
        assertThat(restrictedToA.errorTotal()).isZero();

        Digest restrictedToB = fullDigest.restrictedTo(Set.of(2L));
        assertThat(restrictedToB.sites()).containsExactly(siteB);
        assertThat(restrictedToB.notifiable()).isTrue();
        assertThat(restrictedToB.allClear()).isFalse();
        assertThat(restrictedToB.errorTotal()).isEqualTo(1);
    }

    @Test
    void digestSectionCalculatesOmittedFindings() {
        DigestSection sectionWithOmitted = new DigestSection(List.of(), 5);
        assertThat(sectionWithOmitted.omitted()).isEqualTo(5);

        DigestSection emptySection = new DigestSection(List.of(), 0);
        assertThat(emptySection.omitted()).isZero();
    }

    @Test
    void siteDigestHelpersCorrectlyReflectStatusAndErrors() {
        SiteDigest failedSite = siteDigest(1L, "Site A", RunStatus.FAILED, 0);
        assertThat(failedSite.failed()).isTrue();
        assertThat(failedSite.notable()).isTrue();

        SiteDigest erroredSite = siteDigest(2L, "Site B", RunStatus.COMPLETED, 3);
        assertThat(erroredSite.failed()).isFalse();
        assertThat(erroredSite.notable()).isTrue();

        SiteDigest cleanSite = siteDigest(3L, "Site C", RunStatus.COMPLETED, 0);
        assertThat(cleanSite.failed()).isFalse();
        assertThat(cleanSite.notable()).isFalse();
    }

    @Test
    void digestAggregatesErrorTotalAndFailedRunsAcrossMultipleSites() {
        SiteDigest clean = siteDigest(1L, "Site A", RunStatus.COMPLETED, 0);
        SiteDigest errored1 = siteDigest(2L, "Site B", RunStatus.COMPLETED, 2);
        SiteDigest errored2 = siteDigest(3L, "Site C", RunStatus.COMPLETED, 5);
        SiteDigest failed = siteDigest(4L, "Site D", RunStatus.FAILED, 0);

        Digest digest = new Digest(RunScope.FULL, NOW, List.of(clean, errored1, errored2, failed));

        assertThat(digest.errorTotal()).isEqualTo(7);
        assertThat(digest.failedRuns()).isEqualTo(1);
        assertThat(digest.notifiable()).isTrue();
        assertThat(digest.allClear()).isFalse();
    }

    private static SiteDigest siteDigest(long siteId, String siteName, RunStatus status, int errorCount) {
        return new SiteDigest(
                siteId,
                siteName,
                100L + siteId,
                status,
                NOW,
                status == RunStatus.FAILED ? "Run failed" : null,
                false,
                new DigestSection(List.of(), errorCount),
                new DigestSection(List.of(), 0),
                errorCount,
                0,
                0,
                0
        );
    }
}
