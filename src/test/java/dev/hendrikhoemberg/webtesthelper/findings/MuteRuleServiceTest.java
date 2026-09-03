package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class MuteRuleServiceTest extends AbstractPostgresTest {

    private static final int MAX_MUTE_DAYS = 365;

    @Autowired
    MuteRuleService service;
    @Autowired
    SiteService sites;
    @Autowired
    JdbcTemplate jdbc;

    private long siteA;
    private long siteB;
    private Instant now;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM mute_rule");
        siteA = sites.create(new SiteForm(
                "Site A", "https://www.site-a.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        siteB = sites.create(new SiteForm(
                "Site B", "https://www.site-b.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void validPerSiteRuleRoundTripsThroughByIdWithEveryFieldIntact() {
        Instant expiresAt = now.plus(90, ChronoUnit.DAYS);
        MuteRuleForm form = new MuteRuleForm(
                siteA,
                CheckType.DEAD_LINK,
                "*linkedin.com*",
                "https://www.site-a.com/archiv/*",
                "Warten auf Relaunch",
                expiresAt);

        long ruleId = service.create(form, "alice", now);
        assertThat(ruleId).isPositive();

        Optional<MuteRule> found = service.byId(ruleId);
        assertThat(found).isPresent();

        MuteRule rule = found.get();
        assertThat(rule.id()).isEqualTo(ruleId);
        assertThat(rule.siteId()).isEqualTo(siteA);
        assertThat(rule.checkType()).isEqualTo(CheckType.DEAD_LINK);
        assertThat(rule.subjectPattern()).isEqualTo("*linkedin.com*");
        assertThat(rule.locationPattern()).isEqualTo("https://www.site-a.com/archiv/*");
        assertThat(rule.reason()).isEqualTo("Warten auf Relaunch");
        assertThat(rule.createdBy()).isEqualTo("alice");
        assertThat(rule.expiresAt()).isEqualTo(expiresAt);
        assertThat(rule.createdAt()).isEqualTo(now);
    }

    @Test
    void globalRuleIsReturnedByForSiteForTwoDifferentSites() {
        Instant expiresAt = now.plus(30, ChronoUnit.DAYS);
        MuteRuleForm globalForm = new MuteRuleForm(
                null,
                CheckType.TLS_CERT,
                null,
                null,
                "Globales Problem",
                expiresAt);

        MuteRuleForm siteAForm = new MuteRuleForm(
                siteA,
                CheckType.PAGE_STATUS,
                null,
                null,
                "Nur Site A",
                expiresAt);

        long globalId = service.create(globalForm, "admin", now);
        long siteAId = service.create(siteAForm, "alice", now);

        List<MuteRule> forSiteA = service.forSite(siteA);
        assertThat(forSiteA).extracting(MuteRule::id).containsExactlyInAnyOrder(globalId, siteAId);

        List<MuteRule> forSiteB = service.forSite(siteB);
        assertThat(forSiteB).extracting(MuteRule::id).containsExactly(globalId);

        List<MuteRule> all = service.all();
        assertThat(all).extracting(MuteRule::id).contains(globalId, siteAId);
    }

    @Test
    void blankReasonThrowsAndWritesNoRow() {
        int countBefore = countRules();
        Instant expiresAt = now.plus(90, ChronoUnit.DAYS);

        MuteRuleForm nullReason = new MuteRuleForm(siteA, CheckType.DEAD_LINK, null, null, null, expiresAt);
        assertThatThrownBy(() -> service.create(nullReason, "alice", now))
                .isInstanceOf(TriageValidationException.class)
                .satisfies(e -> assertThat(((TriageValidationException) e).messageKey()).containsIgnoringCase("reason"));
        assertThat(countRules()).isEqualTo(countBefore);

        MuteRuleForm blankReason = new MuteRuleForm(siteA, CheckType.DEAD_LINK, null, null, "   ", expiresAt);
        assertThatThrownBy(() -> service.create(blankReason, "alice", now))
                .isInstanceOf(TriageValidationException.class)
                .satisfies(e -> assertThat(((TriageValidationException) e).messageKey()).containsIgnoringCase("reason"));
        assertThat(countRules()).isEqualTo(countBefore);
    }

    @Test
    void pastExpiryThrowsAndWritesNoRow() {
        int countBefore = countRules();
        Instant past = now.minus(1, ChronoUnit.SECONDS);

        MuteRuleForm pastForm = new MuteRuleForm(siteA, CheckType.DEAD_LINK, null, null, "Grund", past);
        assertThatThrownBy(() -> service.create(pastForm, "alice", now))
                .isInstanceOf(TriageValidationException.class);
        assertThat(countRules()).isEqualTo(countBefore);

        MuteRuleForm exactNowForm = new MuteRuleForm(siteA, CheckType.DEAD_LINK, null, null, "Grund", now);
        assertThatThrownBy(() -> service.create(exactNowForm, "alice", now))
                .isInstanceOf(TriageValidationException.class);
        assertThat(countRules()).isEqualTo(countBefore);
    }

    @Test
    void expiryBeyondMaxMuteDaysThrowsAndWritesNoRow() {
        int countBefore = countRules();
        Instant tooFar = now.plus(MAX_MUTE_DAYS + 1, ChronoUnit.DAYS);

        MuteRuleForm tooFarForm = new MuteRuleForm(siteA, CheckType.DEAD_LINK, null, null, "Grund", tooFar);
        assertThatThrownBy(() -> service.create(tooFarForm, "alice", now))
                .isInstanceOf(TriageValidationException.class);
        assertThat(countRules()).isEqualTo(countBefore);
    }

    @Test
    void formWithAllThreeCriteriaBlankThrowsAndWritesNoRow() {
        int countBefore = countRules();
        Instant expiresAt = now.plus(90, ChronoUnit.DAYS);

        MuteRuleForm allBlank = new MuteRuleForm(siteA, null, "", "   ", "Grund", expiresAt);
        assertThatThrownBy(() -> service.create(allBlank, "alice", now))
                .isInstanceOf(TriageValidationException.class);
        assertThat(countRules()).isEqualTo(countBefore);

        MuteRuleForm allNull = new MuteRuleForm(siteA, null, null, null, "Grund", expiresAt);
        assertThatThrownBy(() -> service.create(allNull, "alice", now))
                .isInstanceOf(TriageValidationException.class);
        assertThat(countRules()).isEqualTo(countBefore);
    }

    @Test
    void deleteRemovesRow() {
        Instant expiresAt = now.plus(90, ChronoUnit.DAYS);
        MuteRuleForm form = new MuteRuleForm(siteA, CheckType.DEAD_LINK, "*test*", null, "Grund", expiresAt);
        long id = service.create(form, "alice", now);

        assertThat(service.byId(id)).isPresent();

        service.delete(id);

        assertThat(service.byId(id)).isEmpty();
        assertThat(countRules()).isZero();
    }

    private int countRules() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM mute_rule", Integer.class);
        return count != null ? count : 0;
    }
}
