package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.*;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UrlVerificationServiceTest extends AbstractPostgresTest {

    private static final String AGENT = "UrlVerificationServiceTest/1.0";

    @Autowired
    UrlVerificationService service;

    @Autowired
    JdbcTemplate jdbc;

    private FixtureSite site;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM external_url_check");
        site = FixtureSite.start();
    }

    private SiteContext siteContext(long siteId, String base) {
        NormalizedUrl baseUrl = UrlNormalizer.normalize(base).orElseThrow();
        return new SiteContext(siteId, "Site-" + siteId, baseUrl,
                new CrawlBudget(100, 10, Duration.ofSeconds(30)),
                List.of("/"), List.of(), List.of(), false, AGENT, Map.of());
    }

    private RunSnapshots snapshots(SiteContext ctx, PageSnapshot... pages) {
        return new RunSnapshots(1L, ctx, List.of(pages), SoftNotFoundProbe.NONE);
    }

    @Test
    void aPageTheCrawlVisitedAppearsWithNoHttpRequestAndCorrectStatus() {
        SiteContext ctx = siteContext(1L, site.baseUrl());
        NormalizedUrl pageUrl = UrlNormalizer.normalize(site.url("extern/ok")).orElseThrow();
        PageSnapshot snapshot = dev.hendrikhoemberg.webtesthelper.support.Snapshots
                .page(site.url("extern/ok")).build();
        RunSnapshots run = snapshots(ctx, snapshot);

        UrlVerifications result = service.verify(ctx, run, List.of(site.url("extern/ok")));

        assertThat(result.of(pageUrl)).isPresent();
        assertThat(result.of(pageUrl).get().status()).isEqualTo(UrlStatus.OK);
        assertThat(site.requestCount("/extern/ok")).isZero();
    }

    @Test
    void anExternalCandidateIsFetchedOnceAndSecondRunAnswersFromCache() {
        SiteContext ctx1 = siteContext(1L, site.baseUrl());
        SiteContext ctx2 = siteContext(2L, "http://127.0.0.1:99999/");
        String externalUrl = site.externalBase() + "extern/ok";
        NormalizedUrl externalNorm = UrlNormalizer.normalize(externalUrl).orElseThrow();
        RunSnapshots run1 = snapshots(ctx1);

        UrlVerifications result1 = service.verify(ctx1, run1, List.of(externalUrl));

        assertThat(result1.of(externalNorm)).isPresent();
        assertThat(result1.of(externalNorm).get().status()).isEqualTo(UrlStatus.OK);
        assertThat(site.requestCount("/extern/ok")).isEqualTo(1);

        UrlVerifications result2 = service.verify(ctx2, snapshots(ctx2),
                List.of(externalUrl));

        assertThat(result2.of(externalNorm)).isPresent();
        assertThat(site.requestCount("/extern/ok")).isEqualTo(1);

        List<Long> ids = jdbc.queryForList(
                "SELECT jsonb_array_elements_text(dependent_site_ids)::bigint "
                        + "FROM external_url_check WHERE url = ?",
                Long.class, externalNorm.value());
        assertThat(ids).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void anInternalCandidateIsFetchedOnBothRunsAndLeavesNoExternalRow() {
        SiteContext ctx = siteContext(1L, site.baseUrl());
        String internalUrl = site.url("extern/ok");
        NormalizedUrl internalNorm = UrlNormalizer.normalize(internalUrl).orElseThrow();
        RunSnapshots run1 = snapshots(ctx);

        service.verify(ctx, run1, List.of(internalUrl));
        assertThat(site.requestCount("/extern/ok")).isEqualTo(1);

        service.verify(ctx, snapshots(ctx), List.of(internalUrl));
        assertThat(site.requestCount("/extern/ok")).isEqualTo(2);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM external_url_check WHERE url = ?",
                Integer.class, internalNorm.value());
        assertThat(count).isZero();
    }

    @Test
    void aDocumentCandidateComesBackWithNonNullBodyPrefix() {
        SiteContext ctx = siteContext(1L, site.baseUrl());
        String pdfUrl = site.url("dateien/handbuch.pdf");
        NormalizedUrl pdfNorm = UrlNormalizer.normalize(pdfUrl).orElseThrow();
        RunSnapshots run = snapshots(ctx);

        UrlVerifications result = service.verify(ctx, run, List.of(pdfUrl));

        assertThat(result.of(pdfNorm)).isPresent();
        assertThat(result.of(pdfNorm).get().bodyPrefix()).isNotNull();
        assertThat(result.of(pdfNorm).get().bodyPrefix()).startsWith("%PDF");
    }

    @Test
    void anOrdinaryPageCandidateComesBackWithNullBodyPrefix() {
        SiteContext ctx = siteContext(1L, site.baseUrl());
        String pageUrl = site.url("extern/ok");
        NormalizedUrl pageNorm = UrlNormalizer.normalize(pageUrl).orElseThrow();
        RunSnapshots run = snapshots(ctx);

        UrlVerifications result = service.verify(ctx, run, List.of(pageUrl));

        assertThat(result.of(pageNorm)).isPresent();
        assertThat(result.of(pageNorm).get().bodyPrefix()).isNull();
    }

    @Test
    void aCachedExternalRowWithoutBodyPrefixIsMissForDocumentCandidateAndRefetched() {
        SiteContext ctx = siteContext(1L, site.baseUrl());
        String pdfUrl = site.externalBase() + "dateien/handbuch.pdf";
        NormalizedUrl pdfNorm = UrlNormalizer.normalize(pdfUrl).orElseThrow();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        jdbc.update("INSERT INTO external_url_check "
                + "(url, status, http_status, content_type, content_length, body_prefix, "
                + "failure_text, checked_at, dependent_site_ids) "
                + "VALUES (?, 'OK', 200, 'application/pdf', 1024, NULL, NULL, ?::timestamptz, '[]'::jsonb)",
                pdfNorm.value(), now.toString());

        UrlVerifications result = service.verify(ctx, snapshots(ctx), List.of(pdfUrl));

        assertThat(result.of(pdfNorm)).isPresent();
        assertThat(result.of(pdfNorm).get().bodyPrefix()).isNotNull();
        assertThat(result.of(pdfNorm).get().bodyPrefix()).startsWith("%PDF");
        assertThat(site.requestCount("/dateien/handbuch.pdf")).isEqualTo(1);
    }

    @Test
    void aCandidateThatIsNotAValidUrlIsSkippedNotThrownOn() {
        SiteContext ctx = siteContext(1L, site.baseUrl());
        RunSnapshots run = snapshots(ctx);

        UrlVerifications result = service.verify(ctx, run, List.of("not-a-url"));

        assertThat(result.byUrl()).isEmpty();
    }
}
