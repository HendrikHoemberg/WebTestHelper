package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.findings.persistence.MuteRuleEntity;
import dev.hendrikhoemberg.webtesthelper.findings.persistence.MuteRuleRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Applies and unapplies mute rules across findings (spec 6.3, D46, D48, D51).
 */
@Component
public class MuteRuleApplier {

    private final MuteRuleRepository repository;
    private final FindingStore store;

    public MuteRuleApplier(MuteRuleRepository repository, FindingStore store) {
        this.repository = repository;
        this.store = store;
    }

    /**
     * Applies all active rules for the given site (site-specific and global) to the findings seen in this run.
     * Evaluated one rule at a time so a malformed pattern in one rule cannot fail the entire run.
     */
    public int applyToRun(long siteId, long runId, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        List<MuteRuleEntity> rules = repository.findForSite(siteId);
        int total = 0;
        for (MuteRuleEntity entity : rules) {
            if (entity.getExpiresAt().isAfter(now)) {
                MuteRule rule = toDomain(entity);
                total += store.applyMuteRule(siteId, runId, rule, now);
            }
        }
        return total;
    }

    /**
     * Retroactively applies a mute rule across all findings for its site (or all sites if global).
     * Mutes matching ACTIVE and RESOLVED UNTRIAGED findings so revived findings come back muted.
     */
    public int applyRule(MuteRule rule, Instant now) {
        Objects.requireNonNull(rule, "rule must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (!rule.expiresAt().isAfter(now)) {
            return 0;
        }
        return store.applyMuteRule(rule.siteId(), null, rule, now);
    }

    /**
     * Returns all findings muted by the specified rule back to UNTRIAGED and stamps mute_expired_at.
     * Touches no findings muted by human triage (muted_by_rule_id is null).
     */
    public int unmuteRule(long ruleId, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return store.unmuteRule(ruleId, now);
    }

    private MuteRule toDomain(MuteRuleEntity entity) {
        return new MuteRule(
                entity.getId(),
                entity.getSiteId(),
                entity.getCheckType(),
                entity.getSubjectPattern(),
                entity.getLocationPattern(),
                entity.getReason(),
                entity.getCreatedBy(),
                entity.getExpiresAt(),
                entity.getCreatedAt()
        );
    }
}
