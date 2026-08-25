package dev.hendrikhoemberg.webtesthelper.scheduling;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.scheduling.persistence.ScheduleClaimJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The scheduling tick (spec 9, D40): find due schedules, claim each occurrence in a
 * compare-and-set, and enqueue the run. The {@link ScheduleTick} timer calls {@link #tick};
 * this class stays schedule-free so tests can drive it directly.
 *
 * <p>Claim is deliberately <em>not</em> in a transaction with the enqueue. A failed enqueue —
 * the realistic case being a site deleted between the query and the enqueue — is logged and
 * the occurrence still consumed, rather than rolled back and retried forever. The claim's
 * own commit is what makes two instances safe: whoever wins the {@code next_fire_at == ?}
 * predicate owns the occurrence.
 */
@Component
public class ScheduleDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ScheduleDispatcher.class);

    private final ScheduleService schedules;
    private final RunService runs;
    private final ScheduleClaimJdbcRepository claims;
    private final SchedulingProperties properties;
    private final AppSettings appSettings;

    public ScheduleDispatcher(ScheduleService schedules, RunService runs,
                              ScheduleClaimJdbcRepository claims, SchedulingProperties properties,
                              AppSettings appSettings) {
        this.schedules = schedules;
        this.runs = runs;
        this.claims = claims;
        this.properties = properties;
        this.appSettings = appSettings;
    }

    /**
     * One tick: seed missing defaults, then fire every due schedule. Returns how many due
     * occurrences were drained — the number of claims won whose enqueue call succeeded. That is
     * <em>not</em> the number of new runs: an already-QUEUED run of the same scope collapses the
     * enqueue into the existing row (deduped), yet the occurrence still counts as drained. A
     * schedule that cannot be parsed, or whose cron has no further occurrence, is skipped with one
     * WARN and its {@code next_fire_at} is left alone — the row stays visibly stuck rather than
     * silently advancing past a fault.
     */
    public int tick(Instant now) {
        if (appSettings.schedulingPaused()) {
            // DEBUG, not INFO: a paused instance ticks 2880 times a day and an INFO line each
            // time would bury everything else in the log.
            log.debug("Planung angehalten — Tick zum Zeitpunkt {} übersprungen", now);
            return 0;
        }
        schedules.seedMissingDefaults(now);
        List<Schedule> due = schedules.due(now, properties.batchSize());
        int queued = 0;
        for (Schedule row : due) {
            Optional<CronSchedule> parsed = CronSchedule.parse(row.cron(), row.timezone());
            if (parsed.isEmpty()) {
                log.warn("Zeitplan {} für Site {} (Scope {}) hat einen ungültigen Cron '{}' und wird übersprungen",
                        row.id(), row.siteId(), row.scope(), row.cron());
                continue;
            }
            Instant next = parsed.get().nextAfter(now);
            if (next == null) {
                log.warn("Zeitplan {} für Site {} (Scope {}) feuert nie wieder (Cron '{}') und wird übersprungen",
                        row.id(), row.siteId(), row.scope(), row.cron());
                continue;
            }
            if (!claims.claim(row.id(), row.nextFireAt(), next, now)) {
                continue;
            }
            if (queue(row)) {
                queued++;
            }
        }
        return queued;
    }

    private boolean queue(Schedule row) {
        try {
            runs.enqueue(row.siteId(), RunTrigger.SCHEDULED, row.scope());
            return true;
        } catch (RuntimeException missedRun) {
            // Any enqueue failure here consumes the occurrence: the claim is already committed.
            // A consumed occurrence is never retried — retrying would re-enqueue after the row
            // has advanced past the occurrence (nothing left to fire), so the run is quietly lost.
            // The realistic cause (a site deleted between the due query and this call) is not
            // fixed by retrying either. Deliberately broad catch: whatever fails, the tick must
            // not abort the rest of the due set. Report it — including type and stack — and move on.
            log.error("Zeitplan {} verpasste den Lauf für Site {} (Scope {}): Enqueue schlug fehl",
                    row.id(), row.siteId(), row.scope(), missedRun);
            return false;
        }
    }
}
