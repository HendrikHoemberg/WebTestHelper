package dev.hendrikhoemberg.webtesthelper.scheduling;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.scheduling.persistence.ScheduleEntity;
import dev.hendrikhoemberg.webtesthelper.scheduling.persistence.ScheduleRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * The schedule catalog: reads one tier's rows for a site, the due set for the dispatcher,
 * and the seed/update writes behind them. The JPA entities never leave this module (spec 5.1) —
 * every method must say the record, not the entity.
 *
 * <p>Deliberately not {@code @Transactional} at the type level, for the same reason
 * {@code RunService} is not: {@link #seedDefaults} swallows a {@link DataIntegrityViolationException}
 * on {@code ux_schedule_site_scope} as a lost race, and that only works if the failed save rolled
 * back its own transaction rather than poisoning a shared one.
 */
@Service
public class ScheduleService {

    private static final String DEFAULT_TIMEZONE = "Europe/Berlin";

    private final ScheduleRepository schedules;
    private final SiteService siteService;

    public ScheduleService(ScheduleRepository schedules, SiteService siteService) {
        this.schedules = schedules;
        this.siteService = siteService;
    }

    @Transactional(readOnly = true)
    public List<Schedule> forSite(long siteId) {
        return schedules.findBySiteIdOrderByScope(siteId).stream()
                .map(this::toSchedule)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Schedule> due(Instant now, int limit) {
        List<Long> enabledSiteIds = siteService.enabledSiteIds();
        if (enabledSiteIds.isEmpty()) {
            return List.of();
        }
        return schedules.findDue(enabledSiteIds, now, Limit.of(limit)).stream()
                .map(this::toSchedule)
                .toList();
    }

    /**
     * The earliest future occurrence per site, across the three tiers. The repository returns
     * enabled rows of enabled sites with a non-null {@code next_fire_at}, ordered per site then
     * by occurrence; DISTINCT ON is not expressible in JPQL, so the first row per site is taken
     * here. A site with no enabled, non-null row is absent.
     */
    @Transactional(readOnly = true)
    public Map<Long, Schedule> nextFirePerSite() {
        List<Long> enabledSiteIds = siteService.enabledSiteIds();
        if (enabledSiteIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Schedule> firstPerSite = new LinkedHashMap<>();
        for (ScheduleEntity row : schedules.findNextFirePerSite(enabledSiteIds)) {
            firstPerSite.putIfAbsent(row.getSiteId(), toSchedule(row));
        }
        return firstPerSite;
    }

    /**
     * Seeds one row per missing scope for {@code siteId}, and tolerates losing the race to a
     * concurrent seed of the same (site, tier): the winner's row is exactly what was wanted.
     */
    public void seedDefaults(long siteId, Instant now) {
        for (RunScope scope : RunScope.values()) {
            if (schedules.existsBySiteIdAndScope(siteId, scope)) {
                continue;
            }
            ScheduleEntity row = new ScheduleEntity();
            row.setSiteId(siteId);
            row.setScope(scope);
            row.setCron(scope.defaultCron());
            row.setTimezone(DEFAULT_TIMEZONE);
            row.setEnabled(true);
            row.setNextFireAt(defaultNext(scope, now));
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            try {
                schedules.save(row);
            } catch (DataIntegrityViolationException raceLostToAnotherSeed) {
                // Mirrors RunService.enqueue: the find-then-save check is racy, so
                // ux_schedule_site_scope converts the loser's insert into a duplicate key.
                // The winner's row is what everybody wanted.
            }
        }
    }

    /** D38's lazy backfill: seeds every site that has no schedule rows at all. */
    public int seedMissingDefaults(Instant now) {
        Set<Long> missingSiteIds = new LinkedHashSet<>(siteService.allSiteIds());
        missingSiteIds.removeAll(schedules.findConfiguredSiteIds());
        for (Long siteId : missingSiteIds) {
            seedDefaults(siteId, now);
        }
        return missingSiteIds.size();
    }

    /**
     * Updates one tier's schedule. A cron {@code CronSchedule.parse} rejects raises before any
     * write, so a bad expression cannot publish a row that would stop the tick for every site.
     */
    @Transactional
    public void update(long scheduleId, String cron, String timezone, boolean enabled, Instant now) {
        ScheduleEntity row = schedules.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("Zeitplan " + scheduleId + " existiert nicht"));
        CronSchedule parsed = CronSchedule.parse(cron, timezone)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Nicht als Cron interpretierbar: " + cron));
        row.setCron(cron);
        row.setTimezone(timezone);
        row.setEnabled(enabled);
        // Recomputed from now, never from the stored value (D40: fires once past the occurrence
        // that was missed, without replaying it).
        row.setNextFireAt(parsed.nextAfter(now));
        row.setUpdatedAt(now);
        schedules.save(row);
    }

    private Instant defaultNext(RunScope scope, Instant now) {
        return CronSchedule.parse(scope.defaultCron(), DEFAULT_TIMEZONE)
                .orElseThrow(() -> new IllegalStateException(
                        "Unbekannter Standard-Cron für " + scope))
                .nextAfter(now);
    }

    private Schedule toSchedule(ScheduleEntity row) {
        return new Schedule(row.getId(), row.getSiteId(), row.getScope(), row.getCron(),
                row.getTimezone(), row.isEnabled(), row.getLastFiredAt(), row.getNextFireAt());
    }
}
