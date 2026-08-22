package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SiteResourceFetcherTest {

    private static FixtureSite site;
    private final SiteResourceFetcher fetcher = new SiteResourceFetcher();

    @BeforeAll static void start() { site = FixtureSite.start(); }
    @AfterAll static void stop() { site.close(); }

    private static NormalizedUrl url(String value) {
        return UrlNormalizer.normalize(value).orElseThrow();
    }

    @Test
    void robotsTxtIsFetched() {
        assertThat(fetcher.fetchText(url(site.url("robots.txt"))))
                .hasValueSatisfying(body -> assertThat(body).contains("Disallow: /geheim/"));
    }

    @Test
    void aDeadHostYieldsEmptyRatherThanThrowing() {
        assertThat(fetcher.fetchText(url("http://localhost:9/robots.txt"))).isEmpty();
    }

    @Test
    void theFixturesSoft404CatchAllStillReturnsABodyAndThatIsTheCallersProblem() {
        // fetchText only reports transport and status; recognising a soft 404 is a check's job.
        assertThat(fetcher.fetchText(url(site.url("sitemap-gibt-es-nicht.xml")))).isPresent();
    }
}