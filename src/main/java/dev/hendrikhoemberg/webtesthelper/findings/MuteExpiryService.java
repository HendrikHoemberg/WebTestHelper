package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.findings.persistence.MuteRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * Hourly sweep that expires mutes visibly (spec 6.3, D49, D50).
 */
@Service
@Transactional
public class MuteExpiryService {

    private static final Logger log = LoggerFactory.getLogger(MuteExpiryService.class);

    private final FindingStore findingStore;
    private final MuteRuleRepository muteRuleRepository;

    public MuteExpiryService(FindingStore findingStore, MuteRuleRepository muteRuleRepository) {
        this.findingStore = findingStore;
        this.muteRuleRepository = muteRuleRepository;
    }

    public MuteSweepResult sweep(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        int unmuted = findingStore.expireMutes(now);
        int rulesExpired = muteRuleRepository.expireRules(now);
        if (unmuted > 0 || rulesExpired > 0) {
            log.info("Stummschaltungs-Ablauf: {} Befunde entstummt, {} Regeln abgelaufen", unmuted, rulesExpired);
        }
        return new MuteSweepResult(unmuted, rulesExpired);
    }
}
