package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.crawler.persistence.CrawlFrontierJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.LinkRef;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

class CrawlServiceEnqueueTest {

    @Test
    void aDiscoveryEnqueueFailureIsCaughtAndDoesNotCorruptTheVisit() {
        CrawlFrontierJdbcRepository frontier = new ThrowingFrontier();
        CrawlerProperties properties = new CrawlerProperties(1, 10, Duration.ofSeconds(5),
                Duration.ZERO, Path.of("target/test-artifacts"), true, false);
        CrawlService service = new CrawlService(frontier, null, null, null, properties);

        NormalizedUrl base = UrlNormalizer.normalize("https://example.com/").orElseThrow();
        NormalizedUrl link = UrlNormalizer.normalize("https://example.com/a").orElseThrow();
        SiteContext site = new SiteContext(1L, "T", base,
                new CrawlBudget(100, 5, Duration.ofMinutes(5)), List.of(), List.of(), List.of(),
                true, "ua", Map.of());
        UrlAdmission admission = new UrlAdmission(site, RobotsRules.ALLOW_ALL);
        CrawlRequest request = new CrawlRequest(1L, site, RunScope.FULL, "w");

        PageSnapshot snapshot = new PageSnapshot(base, base.value(), 0, true, null, 200, Map.of(),
                List.of(base.value()), 0L, "", "", "", 0L,
                List.of(new LinkRef("/a", link, "x", true, "")), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), null);
        CrawlTarget target = new CrawlTarget(1L, base.value(), 0);

        assertThatCode(() -> service.enqueueDiscovered(request, target, admission, snapshot))
                .doesNotThrowAnyException();
    }

    private static final class ThrowingFrontier extends CrawlFrontierJdbcRepository {
        ThrowingFrontier() {
            super(null);
        }

        @Override
        public int enqueue(long runId, Collection<String> urls, int depth, String discoveredFrom) {
            throw new RuntimeException("Datenbank nicht erreichbar");
        }
    }
}
