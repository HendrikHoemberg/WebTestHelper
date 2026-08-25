package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.findings.persistence.MuteRuleEntity;
import dev.hendrikhoemberg.webtesthelper.findings.persistence.MuteRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing mute rules (spec 6.3, D48).
 * Ensures JPA entities remain inside persistence package and returns domain records.
 */
@Service
@Transactional
public class MuteRuleService {

    private final MuteRuleRepository repository;
    private final FindingProperties properties;

    public MuteRuleService(MuteRuleRepository repository, FindingProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public long create(MuteRuleForm form, String actor, Instant now) {
        Objects.requireNonNull(form, "form must not be null");
        Objects.requireNonNull(now, "now must not be null");

        if (form.reason() == null || form.reason().isBlank()) {
            throw new TriageValidationException("triage.reason.required");
        }
        if (form.expiresAt() == null) {
            throw new TriageValidationException("triage.mutedUntil.required");
        }
        if (!form.expiresAt().isAfter(now)) {
            throw new TriageValidationException("triage.mutedUntil.past");
        }
        if (form.expiresAt().isAfter(now.plus(properties.maxMuteDays(), ChronoUnit.DAYS))) {
            throw new TriageValidationException("triage.mutedUntil.too_far");
        }
        if (form.checkType() == null && MutePattern.isBlank(form.subjectPattern()) && MutePattern.isBlank(form.locationPattern())) {
            throw new TriageValidationException("triage.rule.criteria.required");
        }

        MuteRuleEntity entity = new MuteRuleEntity();
        entity.setSiteId(form.siteId());
        entity.setCheckType(form.checkType());
        entity.setSubjectPattern(MutePattern.isBlank(form.subjectPattern()) ? null : form.subjectPattern());
        entity.setLocationPattern(MutePattern.isBlank(form.locationPattern()) ? null : form.locationPattern());
        entity.setReason(form.reason());
        entity.setCreatedBy(actor);
        entity.setExpiresAt(form.expiresAt().truncatedTo(ChronoUnit.MICROS));
        entity.setCreatedAt(now.truncatedTo(ChronoUnit.MICROS));

        MuteRuleEntity saved = repository.save(entity);
        return saved.getId();
    }

    @Transactional(readOnly = true)
    public List<MuteRule> forSite(long siteId) {
        return repository.findForSite(siteId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MuteRule> all() {
        return repository.findAll().stream()
                .map(this::toDomain)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<MuteRule> byId(long id) {
        return repository.findById(id).map(this::toDomain);
    }

    public void delete(long id) {
        repository.deleteById(id);
        repository.flush();
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
