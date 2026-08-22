package dev.hendrikhoemberg.webtesthelper.crawler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RobotsRulesTest {

    private static final String ROBOTS = """
            # Kommentar
            User-agent: Bingbot
            Disallow: /

            User-agent: *
            Disallow: /geheim/
            Disallow: /suche?
            Allow: /geheim/oeffentlich.html
            Disallow: /*.json$

            Sitemap: https://example.com/sitemap.xml
            """;

    @Test
    void theStarGroupApplies() {
        RobotsRules rules = RobotsRules.parse(ROBOTS);
        assertThat(rules.allows("/")).isTrue();
        assertThat(rules.allows("/leistungen.html")).isTrue();
        assertThat(rules.allows("/geheim/intern.html")).isFalse();
    }

    @Test
    void aGroupNamingAnotherAgentIsIgnoredEntirely() {
        // Bingbot's blanket Disallow: / must not leak into our rules (deviation D9).
        assertThat(RobotsRules.parse(ROBOTS).allows("/impressum.html")).isTrue();
    }

    @Test
    void theLongestMatchingRuleWinsAndAllowBreaksTies() {
        RobotsRules rules = RobotsRules.parse(ROBOTS);
        assertThat(rules.allows("/geheim/oeffentlich.html")).isTrue();
        assertThat(rules.allows("/geheim/sonstiges.html")).isFalse();
    }

    @Test
    void wildcardsAndEndAnchorsAreHonoured() {
        RobotsRules rules = RobotsRules.parse(ROBOTS);
        assertThat(rules.allows("/daten/export.json")).isFalse();
        assertThat(rules.allows("/daten/export.json.html")).isTrue();
        assertThat(rules.allows("/suche?q=test")).isFalse();
    }

    @Test
    void sitemapLinesAreCollectedRegardlessOfGroup() {
        assertThat(RobotsRules.parse(ROBOTS).sitemaps())
                .containsExactly("https://example.com/sitemap.xml");
    }

    @Test
    void anEmptyDisallowMeansEverythingIsAllowed() {
        assertThat(RobotsRules.parse("User-agent: *\nDisallow:").allows("/beliebig")).isTrue();
    }

    @Test
    void anUnreadableOrAbsentRobotsFileAllowsEverything() {
        assertThat(RobotsRules.parse("").allows("/beliebig")).isTrue();
        assertThat(RobotsRules.ALLOW_ALL.allows("/beliebig")).isTrue();
    }
}