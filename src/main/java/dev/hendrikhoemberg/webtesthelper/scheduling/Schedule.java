package dev.hendrikhoemberg.webtesthelper.scheduling;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;

import java.time.Instant;

/**
 * A site's schedule for one tier (spec 9). The read-side view the scheduling module exposes;
 * {@link dev.hendrikhoemberg.webtesthelper.scheduling.persistence.ScheduleEntity} never leaves
 * this module (spec 5.1).
 *
 * @param id          the schedule id
 * @param siteId      the owning site
 * @param scope       the tier, which together with {@code siteId} is the identity (D38)
 * @param cron        the cron expression, evaluated in {@code timezone}
 * @param timezone    the timezone the cron is evaluated in
 * @param enabled     whether the tier runs at all; rows are disabled, never deleted
 * @param lastFiredAt when the tier last fired, or null if never
 * @param nextFireAt  the next occurrence, or null if the cron never fires again
 */
public record Schedule(long id, long siteId, RunScope scope, String cron, String timezone,
                       boolean enabled, Instant lastFiredAt, Instant nextFireAt) {
}
