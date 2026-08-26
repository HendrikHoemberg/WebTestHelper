package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.checks.CheckDescriptor;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.ReportSection;
import dev.hendrikhoemberg.webtesthelper.findings.RunDiff;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FindingViewFactoryTest {

    private final CheckRegistry registry = CheckRegistry.standard();
    private final MessageSource messageSource = createMessageSource();
    private final FindingViewFactory factory = new FindingViewFactory(messageSource, registry);

    private static MessageSource createMessageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    @Test
    void resolvesTitleRemediationAndMessageWithArgs() {
        Finding finding = new Finding(
                1L, 42L, "fp-1",
                CheckType.DEAD_LINK,
                "https://example.com/dead",
                "https://example.com/source",
                Severity.ERROR,
                "finding.DEAD_LINK.dead",
                List.of("https://example.com/dead", "404 Not Found"),
                Evidence.NONE,
                ObservedStatus.ACTIVE,
                TriageStatus.UNTRIAGED,
                null,
                10L, 10L, null, null,
                1, 1,
                Instant.parse("2026-08-25T10:00:00Z"),
                Instant.parse("2026-08-25T10:00:00Z")
        );

        FindingView view = factory.of(finding, Locale.GERMAN);

        assertThat(view.id()).isEqualTo(1L);
        assertThat(view.title()).isEqualTo("Tote Links");
        assertThat(view.remediation()).contains("Verweis auf die richtige Adresse korrigieren");
        assertThat(view.message()).isEqualTo("Der Verweis auf https://example.com/dead führt ins Leere (404 Not Found).");
        assertThat(view.locationText()).isEqualTo("https://example.com/source");
        assertThat(view.siteWide()).isFalse();
        assertThat(view.pageCount()).isEqualTo(1);
        assertThat(view.severity()).isEqualTo(Severity.ERROR);
        assertThat(view.triage()).isEqualTo(TriageStatus.UNTRIAGED);
    }

    @Test
    void siteWideFindingGetsFormattedLocationTextAndSiteWideTrue() {
        Finding finding = new Finding(
                2L, 42L, "fp-2",
                CheckType.TLS_CERT,
                "cert",
                "*",
                Severity.WARN,
                "finding.TLS_CERT.expiringSoon",
                List.of("example.com", "14"),
                Evidence.NONE,
                ObservedStatus.ACTIVE,
                TriageStatus.UNTRIAGED,
                null,
                10L, 10L, null, null,
                312, 312,
                Instant.parse("2026-08-25T10:00:00Z"),
                Instant.parse("2026-08-25T10:00:00Z")
        );

        FindingView view = factory.of(finding, Locale.GERMAN);

        assertThat(view.siteWide()).isTrue();
        assertThat(view.locationText()).isEqualTo("auf 312 Seiten");
        assertThat(view.pageCount()).isEqualTo(312);
    }

    @Test
    void ofRunDiffPreservesReportSectionOrderAndOmitsNothing() {
        Finding fNew = new Finding(1L, 42L, "fp-1", CheckType.DEAD_LINK, "s1", "loc1", Severity.ERROR,
                "finding.DEAD_LINK.dead", List.of("https://example.com/a", "404"), Evidence.NONE,
                ObservedStatus.ACTIVE, TriageStatus.UNTRIAGED, null, 10L, 10L, null, null, 1, 1,
                Instant.now(), Instant.now());
        Finding fFixed = new Finding(2L, 42L, "fp-2", CheckType.PAGE_STATUS, "s2", "loc2", Severity.ERROR,
                "finding.PAGE_STATUS.httpError", List.of("500"), Evidence.NONE,
                ObservedStatus.RESOLVED, TriageStatus.UNTRIAGED, null, 9L, 9L, 10L, null, 1, 1,
                Instant.now(), Instant.now());

        Map<ReportSection, List<Finding>> bySection = new LinkedHashMap<>();
        bySection.put(ReportSection.NEW, List.of(fNew));
        bySection.put(ReportSection.FIXED, List.of(fFixed));
        bySection.put(ReportSection.REGRESSED, List.of());
        bySection.put(ReportSection.KNOWN, List.of());
        bySection.put(ReportSection.STILL_OPEN, List.of());

        RunDiff diff = new RunDiff(10L, bySection);

        Map<ReportSection, List<FindingView>> result = factory.of(diff, Locale.GERMAN);

        assertThat(result.keySet()).containsExactly(
                ReportSection.FIXED,
                ReportSection.NEW,
                ReportSection.REGRESSED,
                ReportSection.KNOWN,
                ReportSection.STILL_OPEN
        );
        assertThat(result.get(ReportSection.FIXED)).hasSize(1);
        assertThat(result.get(ReportSection.NEW)).hasSize(1);
        assertThat(result.get(ReportSection.REGRESSED)).isEmpty();
        assertThat(result.get(ReportSection.KNOWN)).isEmpty();
        assertThat(result.get(ReportSection.STILL_OPEN)).isEmpty();
    }

    @Test
    void noRenderedTextCarriesAnInternalIdentifier() {
        for (CheckDescriptor descriptor : registry.all()) {
            CheckType type = descriptor.type();
            String messageKey = descriptor.messageKeys().iterator().next();
            Finding finding = new Finding(
                    100L, 42L, "fp-" + type.name(),
                    type,
                    "https://example.com/item",
                    "https://example.com/page",
                    Severity.ERROR,
                    messageKey,
                    List.of("https://example.com/target", "detail"),
                    Evidence.NONE,
                    ObservedStatus.ACTIVE,
                    TriageStatus.UNTRIAGED,
                    null,
                    10L, 10L, null, null,
                    1, 1,
                    Instant.parse("2026-08-25T10:00:00Z"),
                    Instant.parse("2026-08-25T10:00:00Z")
            );

            FindingView view = factory.of(finding, Locale.GERMAN);

            for (CheckType candidate : CheckType.values()) {
                String typeName = candidate.name();
                assertThat(view.title())
                        .as("Title for %s must not contain '%s'", type, typeName)
                        .doesNotContain(typeName);
                assertThat(view.remediation())
                        .as("Remediation for %s must not contain '%s'", type, typeName)
                        .doesNotContain(typeName);
                assertThat(view.message())
                        .as("Message for %s must not contain '%s'", type, typeName)
                        .doesNotContain(typeName);
                assertThat(view.locationText())
                        .as("LocationText for %s must not contain '%s'", type, typeName)
                        .doesNotContain(typeName);
            }
        }
    }

    @Test
    void pageUnreachableWithChromiumErrorHumanisesWithoutInternalIdentifier() {
        Finding finding = new Finding(
                101L, 42L, "fp-unreachable",
                CheckType.PAGE_UNREACHABLE,
                "https://example.com/unreachable",
                "https://example.com/unreachable",
                Severity.ERROR,
                "finding.PAGE_UNREACHABLE.navigation",
                List.of("net::ERR_TOO_MANY_REDIRECTS"),
                Evidence.NONE,
                ObservedStatus.ACTIVE,
                TriageStatus.UNTRIAGED,
                null,
                10L, 10L, null, null,
                1, 1,
                Instant.parse("2026-08-25T10:00:00Z"),
                Instant.parse("2026-08-25T10:00:00Z")
        );

        FindingView view = factory.of(finding, Locale.GERMAN);

        assertThat(view.message()).doesNotContain("net::");
        assertThat(view.message()).doesNotContain("ERR_");
        assertThat(view.message()).doesNotContain("PAGE_UNREACHABLE");
        assertThat(view.message()).contains("Die Seite liess sich nicht laden");
        assertThat(view.message()).contains("Weiterleitung");
    }

    @Test
    void detailOfCreatesCompleteFindingDetailView() {
        Evidence evidence = new Evidence(
                "abcdef0123456789abcdef0123456789.png",
                500,
                "GET /test",
                "Internal Server Error",
                List.of("uncaught exception in app.js")
        );
        Finding finding = new Finding(
                102L, 42L, "fp-detail",
                CheckType.PAGE_UNREACHABLE,
                "https://example.com/item",
                "*",
                Severity.ERROR,
                "finding.PAGE_UNREACHABLE.navigation",
                List.of("net::ERR_CONNECTION_REFUSED"),
                evidence,
                ObservedStatus.ACTIVE,
                TriageStatus.ACKNOWLEDGED,
                "Bekanntes Wartungsfenster",
                5L, 10L, null, null,
                312, 312,
                Instant.parse("2026-08-25T08:00:00Z"),
                Instant.parse("2026-08-25T10:00:00Z")
        );

        dev.hendrikhoemberg.webtesthelper.findings.FindingOccurrence occ1 =
                new dev.hendrikhoemberg.webtesthelper.findings.FindingOccurrence("https://example.com/p1", Severity.ERROR, "m", List.of(), Evidence.NONE);
        dev.hendrikhoemberg.webtesthelper.findings.FindingOccurrence occ2 =
                new dev.hendrikhoemberg.webtesthelper.findings.FindingOccurrence("https://example.com/p2", Severity.ERROR, "m", List.of(), Evidence.NONE);

        FindingDetailView detail = factory.detailOf(finding, List.of(occ1, occ2), Locale.GERMAN);

        assertThat(detail.summary().id()).isEqualTo(102L);
        assertThat(detail.summary().siteWide()).isTrue();
        assertThat(detail.description()).isEqualTo("Prüft, ob sich eine Seite überhaupt laden lässt.");
        assertThat(detail.rawTechnicalDetail()).isEqualTo("net::ERR_CONNECTION_REFUSED");
        assertThat(detail.technicalDetail()).contains("abgelehnt");
        assertThat(detail.httpStatus()).isEqualTo(500);
        assertThat(detail.requestDetail()).isEqualTo("GET /test");
        assertThat(detail.responseDetail()).isEqualTo("Internal Server Error");
        assertThat(detail.consoleExcerpt()).containsExactly("uncaught exception in app.js");
        assertThat(detail.screenshotUrl()).isEqualTo("/artefakte/10/abcdef0123456789abcdef0123456789.png");
        assertThat(detail.pages()).containsExactly("https://example.com/p1", "https://example.com/p2");
        assertThat(detail.pageTotal()).isEqualTo(312);
        assertThat(detail.triageReason()).isEqualTo("Bekanntes Wartungsfenster");
    }
}

