package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.TlsCertificateFact;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * The certificate a site presents at its base URL (spec 7.1). Pure: it compares two instants, the
 * expiry and the run's start, and never touches the network — that is {@code TlsProbe}'s job, and
 * its result arrives as a {@link TlsCertificateFact}.
 */
public final class TlsCertCheck implements SiteCheck {

    static final String HANDSHAKE_FAILED = "finding.TLS_CERT.handshakeFailed";
    static final String EXPIRED = "finding.TLS_CERT.expired";
    static final String EXPIRING_SOON = "finding.TLS_CERT.expiringSoon";
    static final int DEFAULT_WARN_DAYS = 30;

    @Override
    public CheckType type() {
        return CheckType.TLS_CERT;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(HANDSHAKE_FAILED, EXPIRED, EXPIRING_SOON);
    }

    @Override
    public List<CheckFinding> evaluate(RunSnapshots snapshots, SiteContext site, CheckConfig config) {
        TlsCertificateFact fact = config.facts().tlsCertificate();
        if (fact.host() == null) {
            return List.of();
        }
        if (!fact.handshakeOk()) {
            return List.of(finding(config, HANDSHAKE_FAILED,
                    List.of(fact.host(), fact.failureText()), config.severity()));
        }
        Instant now = config.facts().startedAt();
        long days = ChronoUnit.DAYS.between(now, fact.notAfter());
        if (days < 0) {
            return List.of(finding(config, EXPIRED,
                    List.of(fact.host(), format(fact.notAfter())), config.severity()));
        }
        int warnDays = config.option("warnDays", DEFAULT_WARN_DAYS);
        if (days <= warnDays) {
            return List.of(finding(config, EXPIRING_SOON,
                    List.of(fact.host(), String.valueOf(days)), Severity.WARN));
        }
        return List.of();
    }

    private CheckFinding finding(CheckConfig config, String messageKey, List<String> args,
            Severity severity) {
        return new CheckFinding(type(), severity, config.facts().tlsCertificate().host(), null,
                messageKey, args, Evidence.NONE);
    }

    private static String format(Instant instant) {
        return DateTimeFormatter.ofPattern("dd.MM.yyyy")
                .withZone(ZoneId.of("Europe/Berlin")).format(instant);
    }
}
