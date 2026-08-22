package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.crawler.persistence.ExternalUrlCacheJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerification;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalUrlCacheJdbcRepositoryTest extends AbstractPostgresTest {

    @Autowired
    ExternalUrlCacheJdbcRepository cache;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clearTable() {
        jdbc.update("DELETE FROM external_url_check");
    }

    @Test
    void storeThenFreshRoundTripsEveryColumnIncludingNullBodyPrefix() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UrlVerification original = new UrlVerification("https://example.com/page",
                UrlStatus.OK, 200, "text/html; charset=utf-8", 4096L, null, null, now);

        cache.store(List.of(original), 1L);

        Map<String, UrlVerification> fresh = cache.fresh(List.of("https://example.com/page"), now);
        UrlVerification stored = fresh.get("https://example.com/page");
        assertThat(stored).isNotNull();
        assertThat(stored.url()).isEqualTo("https://example.com/page");
        assertThat(stored.status()).isEqualTo(UrlStatus.OK);
        assertThat(stored.httpStatus()).isEqualTo(200);
        assertThat(stored.contentType()).isEqualTo("text/html; charset=utf-8");
        assertThat(stored.contentLength()).isEqualTo(4096L);
        assertThat(stored.bodyPrefix()).isNull();
        assertThat(stored.failureText()).isNull();
        assertThat(stored.checkedAt()).isEqualTo(now.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void anOkRowChecked23HoursAgoIsFreshButOneChecked25HoursAgoIsNot() {
        Instant now = Instant.now();
        UrlVerification fresh = new UrlVerification("https://example.com/fresh",
                UrlStatus.OK, 200, null, 0, null, null, now.minus(23, ChronoUnit.HOURS));
        UrlVerification stale = new UrlVerification("https://example.com/stale",
                UrlStatus.OK, 200, null, 0, null, null, now.minus(25, ChronoUnit.HOURS));

        cache.store(List.of(fresh, stale), 1L);

        Map<String, UrlVerification> result = cache.fresh(
                List.of("https://example.com/fresh", "https://example.com/stale"), now);
        assertThat(result).containsKey("https://example.com/fresh");
        assertThat(result).doesNotContainKey("https://example.com/stale");
    }

    @Test
    void aDeadRowChecked30MinutesAgoIsFreshButOneChecked2HoursAgoIsNot() {
        Instant now = Instant.now();
        UrlVerification freshDead = new UrlVerification("https://example.com/recent-dead",
                UrlStatus.DEAD, 404, null, 0, null, "Not found", now.minus(30, ChronoUnit.MINUTES));
        UrlVerification staleDead = new UrlVerification("https://example.com/old-dead",
                UrlStatus.DEAD, 404, null, 0, null, "Not found", now.minus(2, ChronoUnit.HOURS));

        cache.store(List.of(freshDead, staleDead), 1L);

        Map<String, UrlVerification> result = cache.fresh(
                List.of("https://example.com/recent-dead", "https://example.com/old-dead"), now);
        assertThat(result).containsKey("https://example.com/recent-dead");
        assertThat(result).doesNotContainKey("https://example.com/old-dead");
    }

    @Test
    void storeForSite1ThenSite2LeavesBothIdsInDependentSiteIds() {
        Instant now = Instant.now();
        UrlVerification v = new UrlVerification("https://example.com/shared",
                UrlStatus.OK, 200, null, 0, null, null, now);

        cache.store(List.of(v), 1L);
        cache.store(List.of(v), 2L);

        List<Long> ids = jdbc.queryForList(
                "SELECT jsonb_array_elements_text(dependent_site_ids)::bigint FROM external_url_check WHERE url = ?",
                Long.class, "https://example.com/shared");
        assertThat(ids).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void storeForSameSiteTwiceAddsNoDuplicateDependentSiteId() {
        Instant now = Instant.now();
        UrlVerification v = new UrlVerification("https://example.com/dup",
                UrlStatus.OK, 200, null, 0, null, null, now);

        cache.store(List.of(v), 1L);
        cache.store(List.of(v), 1L);

        List<Long> ids = jdbc.queryForList(
                "SELECT jsonb_array_elements_text(dependent_site_ids)::bigint FROM external_url_check WHERE url = ?",
                Long.class, "https://example.com/dup");
        assertThat(ids).containsExactly(1L);
    }

    @Test
    void storingSameUrlTwiceOverwritesStatusAndCheckedAt() {
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t2 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UrlVerification first = new UrlVerification("https://example.com/overwrite",
                UrlStatus.DEAD, 404, null, 0, null, "Not found", t1);
        UrlVerification second = new UrlVerification("https://example.com/overwrite",
                UrlStatus.OK, 200, null, 0, null, null, t2);

        cache.store(List.of(first), 1L);
        cache.store(List.of(second), 1L);

        Map<String, UrlVerification> result = cache.fresh(List.of("https://example.com/overwrite"), t2);
        UrlVerification stored = result.get("https://example.com/overwrite");
        assertThat(stored).isNotNull();
        assertThat(stored.status()).isEqualTo(UrlStatus.OK);
        assertThat(stored.checkedAt()).isEqualTo(t2.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void freshWithEmptyListReturnsEmptyMapWithoutTouchingDatabase() {
        int countBefore = jdbc.queryForObject(
                "SELECT count(*) FROM external_url_check", Integer.class);

        Map<String, UrlVerification> result = cache.fresh(List.of(), Instant.now());

        assertThat(result).isEmpty();
        int countAfter = jdbc.queryForObject(
                "SELECT count(*) FROM external_url_check", Integer.class);
        assertThat(countAfter).isEqualTo(countBefore);
    }

    @Test
    void bodyPrefixIsPreservedOnUpsert() {
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant t2 = Instant.now();
        UrlVerification withPrefix = new UrlVerification("https://example.com/pdf",
                UrlStatus.OK, 200, "application/pdf", 1024L, "%PDF-1.4", null, t1);
        UrlVerification withoutPrefix = new UrlVerification("https://example.com/pdf",
                UrlStatus.OK, 200, "application/pdf", 1024L, null, null, t2);

        cache.store(List.of(withPrefix), 1L);
        cache.store(List.of(withoutPrefix), 1L);

        Map<String, UrlVerification> result = cache.fresh(List.of("https://example.com/pdf"), t2);
        UrlVerification stored = result.get("https://example.com/pdf");
        assertThat(stored).isNotNull();
        assertThat(stored.bodyPrefix()).isEqualTo("%PDF-1.4");
    }
}
