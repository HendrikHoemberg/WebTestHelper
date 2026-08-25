package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;

import java.time.Instant;

/**
 * A mute rule domain model (spec 6.3, D48).
 *
 * @param id              the database id.
 * @param siteId          the site id this rule applies to, or {@code null} for a global rule (fleet-wide).
 * @param checkType       the check type to mute, or {@code null} for every check.
 * @param subjectPattern  glob pattern over {@code finding.subject_key}, or {@code null}.
 * @param locationPattern glob pattern over {@code finding.location_key}, or {@code null}.
 * @param reason          the mandatory human reason for muting.
 * @param createdBy       the user who created the rule.
 * @param expiresAt       the mandatory expiry timestamp.
 * @param createdAt       when the rule was created.
 */
public record MuteRule(long id, Long siteId, CheckType checkType, String subjectPattern,
                       String locationPattern, String reason, String createdBy,
                       Instant expiresAt, Instant createdAt) {
}
