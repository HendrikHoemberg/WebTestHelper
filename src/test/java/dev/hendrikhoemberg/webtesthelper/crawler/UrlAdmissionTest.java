package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UrlAdmissionTest {

    @Test
    void aPageOnTheSameSiteWithinDepthIsAdmitted() {
        assertThat(admission().admit(url("https://example.com/leistungen.html"), 1).admitted())
                .isTrue();
    }

    @Test
    void wwwAndTheApexAreTheSameSite() {
        assertThat(admission().admit(url("https://www.example.com/a.html"), 1).admitted()).isTrue();
    }

    @Test
    void anotherHostIsOffSite() {
        assertThat(admission().admit(url("https://partner.example/a"), 1).reason())
                .isEqualTo(UrlAdmission.Reason.OFF_SITE);
    }

    @Test
    void beyondMaxDepthIsRejectedWithoutBeingAnError() {
        assertThat(admission(3).admit(url("https://example.com/tief"), 4).reason())
                .isEqualTo(UrlAdmission.Reason.TOO_DEEP);
    }

    @Test
    void excludePatternsWinOverIncludePatterns() {
        UrlAdmission admission = new UrlAdmission(
                site(List.of("/blog/*"), List.of("/blog/entwurf-*"), true, 5), RobotsRules.ALLOW_ALL);
        assertThat(admission.admit(url("https://example.com/blog/beitrag"), 1).admitted()).isTrue();
        assertThat(admission.admit(url("https://example.com/blog/entwurf-7"), 1).reason())
                .isEqualTo(UrlAdmission.Reason.EXCLUDED);
        assertThat(admission.admit(url("https://example.com/impressum"), 1).reason())
                .isEqualTo(UrlAdmission.Reason.NOT_INCLUDED);
    }

    @Test
    void anEmptyIncludeListMeansEverythingIsIncluded() {
        assertThat(admission().admit(url("https://example.com/irgendwo"), 1).admitted()).isTrue();
    }

    @Test
    void patternsMatchPathAndQueryAnchoredAtBothEnds() {
        // Deviation D8: /blog/* matches /blog/beitrag, not /blog.
        UrlAdmission admission = new UrlAdmission(
                site(List.of(), List.of("/blog/*"), true, 5), RobotsRules.ALLOW_ALL);
        assertThat(admission.admit(url("https://example.com/blog"), 1).admitted()).isTrue();
        assertThat(admission.admit(url("https://example.com/blog/x"), 1).admitted()).isFalse();
    }

    @Test
    void robotsIsHonouredUnlessTheSiteOverridesIt() {
        RobotsRules robots = RobotsRules.parse("User-agent: *\nDisallow: /geheim/");
        assertThat(new UrlAdmission(site(List.of(), List.of(), true, 5), robots)
                .admit(url("https://example.com/geheim/x"), 1).reason())
                .isEqualTo(UrlAdmission.Reason.ROBOTS);
        assertThat(new UrlAdmission(site(List.of(), List.of(), false, 5), robots)
                .admit(url("https://example.com/geheim/x"), 1).admitted())
                .isTrue();
    }

    @Test
    void assetsAreNotNavigableAndNeverEnterTheFrontier() {
        // Deviation D10: verified over HTTP on virtual threads in Plan 3, not by a browser.
        for (String asset : List.of("/handbuch.pdf", "/logo.PNG", "/archiv.zip", "/stil.css")) {
            assertThat(admission().admit(url("https://example.com" + asset), 1).reason())
                    .isEqualTo(UrlAdmission.Reason.NOT_NAVIGABLE);
        }
        assertThat(admission().admit(url("https://example.com/seite.html"), 1).admitted()).isTrue();
        assertThat(admission().admit(url("https://example.com/seite"), 1).admitted()).isTrue();
    }

    @Test
    void nonHttpSchemesAreRejected() {
        // UrlNormalizer drops non-web schemes at the door, so one is built by hand: a
        // NormalizedUrl that somehow reaches the frontier must still not slip past.
        assertThat(admission().admit(new NormalizedUrl("ftp", "example.com", 21, "/x", null), 1).reason())
                .isEqualTo(UrlAdmission.Reason.BAD_SCHEME);
    }

    private static SiteContext site(List<String> include, List<String> exclude,
                                    boolean respectRobots, int maxDepth) {
        return new SiteContext(
                1L,
                "Test",
                UrlNormalizer.normalize("https://example.com/").orElseThrow(),
                new CrawlBudget(300, maxDepth, Duration.ofMinutes(30)),
                include, exclude, List.of(), respectRobots, "WebTestHelper-Test", Map.of());
    }

    private static UrlAdmission admission() {
        return new UrlAdmission(site(List.of(), List.of(), true, 5), RobotsRules.ALLOW_ALL);
    }

    private static UrlAdmission admission(int maxDepth) {
        return new UrlAdmission(site(List.of(), List.of(), true, maxDepth), RobotsRules.ALLOW_ALL);
    }

    private static NormalizedUrl url(String value) {
        return UrlNormalizer.normalize(value).orElseThrow();
    }
}