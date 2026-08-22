package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.checks.CheckConfig;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import dev.hendrikhoemberg.webtesthelper.model.TlsCertificateFact;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerifications;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TlsCertCheckTest {

    private static final Instant STARTED = Instant.parse("2026-01-15T12:00:00Z");

    private final TlsCertCheck check = new TlsCertCheck();

    private static RunFacts facts(TlsCertificateFact tls) {
        return new RunFacts(1L, RunScope.FULL, STARTED, SoftNotFoundProbe.NONE,
                UrlVerifications.EMPTY, tls, List.of());
    }

    private static CheckConfig config(RunFacts facts) {
        return new CheckConfig(Severity.ERROR, Map.of(), facts);
    }

    private static CheckConfig config(RunFacts facts, Map<String, Object> options) {
        return new CheckConfig(Severity.ERROR, options, facts);
    }

    private static SiteContext site() {
        return new SiteContext(1L, "Beispiel", Snapshots.url("https://example.com/"),
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, null, Map.of());
    }

    private static RunSnapshots snapshots() {
        return new RunSnapshots(1L, site(), List.of(), SoftNotFoundProbe.NONE);
    }

    @Test
    void anHttpSiteHasNoCertificateFinding() {
        assertThat(check.evaluate(snapshots(), site(), config(facts(TlsCertificateFact.NONE))))
                .isEmpty();
    }

    @Test
    void aFailedHandshakeIsReportedAsAnError() {
        TlsCertificateFact fact = new TlsCertificateFact("example.com", false,
                "Verbindung abgelehnt", null, null, null);

        assertThat(check.evaluate(snapshots(), site(), config(facts(fact))))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.type()).isEqualTo(CheckType.TLS_CERT);
                    assertThat(finding.messageKey()).isEqualTo("finding.TLS_CERT.handshakeFailed");
                    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
                    assertThat(finding.subjectKey()).isEqualTo("example.com");
                    assertThat(finding.locationKey()).isEqualTo("*");
                    assertThat(finding.messageArgs()).containsExactly("example.com",
                            "Verbindung abgelehnt");
                });
    }

    @Test
    void anExpiredCertificateIsReportedAsAnError() {
        TlsCertificateFact fact = new TlsCertificateFact("example.com", true, null,
                STARTED.minus(720, ChronoUnit.DAYS), STARTED.minus(1, ChronoUnit.DAYS),
                "CN=example.com");

        String expectedDate = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                .withZone(ZoneId.of("Europe/Berlin")).format(fact.notAfter());

        assertThat(check.evaluate(snapshots(), site(), config(facts(fact))))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.TLS_CERT.expired");
                    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
                    assertThat(finding.messageArgs()).containsExactly("example.com", expectedDate);
                });
    }

    @Test
    void aCertificateExpiringSoonWarnsEvenWhenTheSiteDemandsError() {
        TlsCertificateFact fact = new TlsCertificateFact("example.com", true, null,
                STARTED.minus(720, ChronoUnit.DAYS), STARTED.plus(10, ChronoUnit.DAYS),
                "CN=example.com");

        CheckConfig errorConfig = new CheckConfig(Severity.ERROR, Map.of(), facts(fact));

        assertThat(check.evaluate(snapshots(), site(), errorConfig))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.TLS_CERT.expiringSoon");
                    assertThat(finding.severity()).isEqualTo(Severity.WARN);
                    assertThat(finding.messageArgs()).containsExactly("example.com", "10");
                });
    }

    @Test
    void aCertificateWithNinetyDaysLeftIsFineByDefault() {
        TlsCertificateFact fact = new TlsCertificateFact("example.com", true, null,
                STARTED.minus(720, ChronoUnit.DAYS), STARTED.plus(90, ChronoUnit.DAYS),
                "CN=example.com");

        assertThat(check.evaluate(snapshots(), site(), config(facts(fact)))).isEmpty();
    }

    @Test
    void aLongerWarningWindowSurfacesTheNinetyDayCertificate() {
        TlsCertificateFact fact = new TlsCertificateFact("example.com", true, null,
                STARTED.minus(720, ChronoUnit.DAYS), STARTED.plus(90, ChronoUnit.DAYS),
                "CN=example.com");

        CheckConfig warnConfig = config(facts(fact), Map.of("warnDays", 120));

        assertThat(check.evaluate(snapshots(), site(), warnConfig))
                .singleElement()
                .satisfies(finding -> assertThat(finding.messageArgs())
                        .containsExactly("example.com", "90"));
    }
}
