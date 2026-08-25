package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;

import java.time.Instant;

/**
 * Input form for creating a {@link MuteRule}.
 *
 * @param siteId          the site id, or {@code null} for a global rule.
 * @param checkType       the check type, or {@code null} for every check.
 * @param subjectPattern  glob pattern over subject_key, or {@code null}.
 * @param locationPattern glob pattern over location_key, or {@code null}.
 * @param reason          the reason for muting.
 * @param expiresAt       the expiry timestamp.
 */
public record MuteRuleForm(Long siteId, CheckType checkType, String subjectPattern,
                           String locationPattern, String reason, Instant expiresAt) {
}
