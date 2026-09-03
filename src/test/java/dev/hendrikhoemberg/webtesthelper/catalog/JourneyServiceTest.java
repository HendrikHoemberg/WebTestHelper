package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.model.AssertionType;
import dev.hendrikhoemberg.webtesthelper.model.JourneyDefinition;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.LocatorStrategy;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.model.StepAssertion;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class JourneyServiceTest extends AbstractPostgresTest {

    @Autowired
    JourneyService journeyService;

    @Autowired
    SiteService siteService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    jakarta.persistence.EntityManager entityManager;

    private long siteA;
    private long siteB;

    @BeforeEach
    void setUp() {
        siteA = siteService.create(new SiteForm("Site A", "https://a.test/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        siteB = siteService.create(new SiteForm("Site B", "https://b.test/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
    }

    @Test
    void journeyRoundTripsJsonbWithEveryStepFieldIntact() {
        UUID step1Id = UUID.randomUUID();
        JourneyStep step1 = new JourneyStep(
                step1Id,
                0,
                StepAction.GOTO,
                List.of(),
                "https://a.test/login",
                null,
                false,
                5000
        );

        UUID step2Id = UUID.randomUUID();
        LocatorCandidate cand1 = new LocatorCandidate(LocatorStrategy.TEST_ID, "submit-btn", 0);
        LocatorCandidate cand2 = new LocatorCandidate(LocatorStrategy.ROLE, "button", 1);
        LocatorCandidate cand3 = new LocatorCandidate(LocatorStrategy.CSS, ".submit", 2);
        StepAssertion assertion2 = new StepAssertion(AssertionType.VISIBLE, "true");
        JourneyStep step2 = new JourneyStep(
                step2Id,
                1,
                StepAction.CLICK,
                List.of(cand1, cand2, cand3),
                null,
                assertion2,
                true,
                10000
        );

        UUID step3Id = UUID.randomUUID();
        LocatorCandidate cand4 = new LocatorCandidate(LocatorStrategy.LABEL, "Username", 0);
        StepAssertion assertion3 = new StepAssertion(AssertionType.TEXT_CONTAINS, "Welcome");
        JourneyStep step3 = new JourneyStep(
                step3Id,
                2,
                StepAction.FILL,
                List.of(cand4),
                "{{cred.login.username}}",
                assertion3,
                false,
                3000
        );

        long journeyId = journeyService.create(siteA, "Checkout Flow", List.of(step1, step2, step3));
        entityManager.flush();
        entityManager.clear();

        Optional<JourneyDefinition> found = journeyService.findDefinition(journeyId);
        assertThat(found).isPresent();
        JourneyDefinition def = found.get();
        assertThat(def.id()).isEqualTo(journeyId);
        assertThat(def.siteId()).isEqualTo(siteA);
        assertThat(def.name()).isEqualTo("Checkout Flow");
        assertThat(def.enabled()).isTrue();
        assertThat(def.steps()).hasSize(3);

        JourneyStep loadedStep1 = def.steps().get(0);
        assertThat(loadedStep1.id()).isEqualTo(step1Id);
        assertThat(loadedStep1.ordinal()).isEqualTo(0);
        assertThat(loadedStep1.action()).isEqualTo(StepAction.GOTO);
        assertThat(loadedStep1.locatorCandidates()).isEmpty();
        assertThat(loadedStep1.value()).isEqualTo("https://a.test/login");
        assertThat(loadedStep1.assertion()).isNull();
        assertThat(loadedStep1.optional()).isFalse();
        assertThat(loadedStep1.timeoutMs()).isEqualTo(5000);

        JourneyStep loadedStep2 = def.steps().get(1);
        assertThat(loadedStep2.id()).isEqualTo(step2Id);
        assertThat(loadedStep2.ordinal()).isEqualTo(1);
        assertThat(loadedStep2.action()).isEqualTo(StepAction.CLICK);
        assertThat(loadedStep2.locatorCandidates()).hasSize(3);
        assertThat(loadedStep2.locatorCandidates().get(0)).isEqualTo(cand1);
        assertThat(loadedStep2.locatorCandidates().get(1)).isEqualTo(cand2);
        assertThat(loadedStep2.locatorCandidates().get(2)).isEqualTo(cand3);
        assertThat(loadedStep2.value()).isNull();
        assertThat(loadedStep2.assertion()).isNotNull();
        assertThat(loadedStep2.assertion().type()).isEqualTo(AssertionType.VISIBLE);
        assertThat(loadedStep2.assertion().expected()).isEqualTo("true");
        assertThat(loadedStep2.optional()).isTrue();
        assertThat(loadedStep2.timeoutMs()).isEqualTo(10000);

        JourneyStep loadedStep3 = def.steps().get(2);
        assertThat(loadedStep3.id()).isEqualTo(step3Id);
        assertThat(loadedStep3.ordinal()).isEqualTo(2);
        assertThat(loadedStep3.action()).isEqualTo(StepAction.FILL);
        assertThat(loadedStep3.locatorCandidates()).containsExactly(cand4);
        assertThat(loadedStep3.value()).isEqualTo("{{cred.login.username}}");
        assertThat(loadedStep3.assertion()).isEqualTo(assertion3);
        assertThat(loadedStep3.optional()).isFalse();
        assertThat(loadedStep3.timeoutMs()).isEqualTo(3000);

        String rawJson = jdbcTemplate.queryForObject("SELECT steps FROM journey WHERE id = ?", String.class, journeyId);
        assertThat(rawJson).isNotNull();
        assertThat(rawJson).contains("submit-btn", "VISIBLE", "optional");
    }

    @Test
    void sameNameOnTwoSitesAcceptedDuplicateOnSameSiteThrows() {
        JourneyStep step = new JourneyStep(UUID.randomUUID(), 0, StepAction.GOTO, List.of(), "https://a.test", null, false, 5000);
        journeyService.create(siteA, "Login Flow", List.of(step));

        long idB = journeyService.create(siteB, "Login Flow", List.of(step));
        assertThat(idB).isPositive();

        assertThatThrownBy(() -> journeyService.create(siteA, "Login Flow", List.of(step)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("journey.name.duplicate");

        assertThatThrownBy(() -> journeyService.create(siteA, "login flow", List.of(step)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("journey.name.duplicate");
    }

    @Test
    void invalidNamesRejected() {
        JourneyStep step = new JourneyStep(UUID.randomUUID(), 0, StepAction.GOTO, List.of(), "https://a.test", null, false, 5000);

        assertThatThrownBy(() -> journeyService.create(siteA, null, List.of(step)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("journey.name.blank");

        assertThatThrownBy(() -> journeyService.create(siteA, "", List.of(step)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("journey.name.blank");

        assertThatThrownBy(() -> journeyService.create(siteA, "   ", List.of(step)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("journey.name.blank");

        assertThatThrownBy(() -> journeyService.create(999999L, "Valid Name", List.of(step)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Site existiert nicht: 999999");
    }

    @Test
    void resolveUniqueNameResolvesCollisions() {
        JourneyStep step = new JourneyStep(UUID.randomUUID(), 0, StepAction.GOTO, List.of(), "https://a.test", null, false, 5000);
        journeyService.create(siteA, "Neuer Ablauf", List.of(step));

        assertThat(journeyService.resolveUniqueName(siteA, "Neuer Ablauf")).isEqualTo("Neuer Ablauf 2");

        journeyService.create(siteA, "Neuer Ablauf 2", List.of(step));
        assertThat(journeyService.resolveUniqueName(siteA, "Neuer Ablauf")).isEqualTo("Neuer Ablauf 3");

        // Non-duplicate name returned as-is
        assertThat(journeyService.resolveUniqueName(siteA, "Anderer Ablauf")).isEqualTo("Anderer Ablauf");

        // Null or blank defaults to Neuer Ablauf (or disambiguated variant)
        assertThat(journeyService.resolveUniqueName(siteA, null)).isEqualTo("Neuer Ablauf 3");
    }

    @Test
    void deletingSiteCascadesAndRemovesJourneyRows() {
        JourneyStep step = new JourneyStep(UUID.randomUUID(), 0, StepAction.GOTO, List.of(), "https://a.test", null, false, 5000);
        journeyService.create(siteA, "Journey 1", List.of(step));
        journeyService.create(siteA, "Journey 2", List.of(step));

        siteService.delete(siteA);
        entityManager.flush();
        entityManager.clear();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journey WHERE site_id = ?", Integer.class, siteA);
        assertThat(count).isEqualTo(0);
    }

    @Test
    void updateRenumbersOrdinalsDense0ToNMinus1AndPreservesStepUuids() {
        UUID uuid0 = UUID.randomUUID();
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();

        JourneyStep step0 = new JourneyStep(uuid0, 0, StepAction.GOTO, List.of(), "https://a.test/start", null, false, 5000);
        JourneyStep step1 = new JourneyStep(uuid1, 1, StepAction.CLICK, List.of(new LocatorCandidate(LocatorStrategy.ROLE, "link", 0)), null, null, false, 5000);
        JourneyStep step2 = new JourneyStep(uuid2, 2, StepAction.ASSERT, List.of(), null, new StepAssertion(AssertionType.URL_MATCHES, ".*/done"), false, 5000);

        long journeyId = journeyService.create(siteA, "Original Journey", List.of(step0, step1, step2));
        entityManager.flush();
        entityManager.clear();

        // Reorder steps: step2 first (ordinal 99), then step0 (ordinal 50), then step1 (ordinal 10)
        JourneyStep reorderedStep2 = new JourneyStep(uuid2, 99, step2.action(), step2.locatorCandidates(), step2.value(), step2.assertion(), step2.optional(), step2.timeoutMs());
        JourneyStep reorderedStep0 = new JourneyStep(uuid0, 50, step0.action(), step0.locatorCandidates(), step0.value(), step0.assertion(), step0.optional(), step0.timeoutMs());
        JourneyStep reorderedStep1 = new JourneyStep(uuid1, 10, step1.action(), step1.locatorCandidates(), step1.value(), step1.assertion(), step1.optional(), step1.timeoutMs());

        journeyService.update(journeyId, "Updated Journey", false, List.of(reorderedStep2, reorderedStep0, reorderedStep1));
        entityManager.flush();
        entityManager.clear();

        JourneyDefinition updated = journeyService.findDefinition(journeyId).orElseThrow();
        assertThat(updated.name()).isEqualTo("Updated Journey");
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.steps()).hasSize(3);

        assertThat(updated.steps().get(0).id()).isEqualTo(uuid2);
        assertThat(updated.steps().get(0).ordinal()).isEqualTo(0);
        assertThat(updated.steps().get(0).assertion()).isEqualTo(step2.assertion());

        assertThat(updated.steps().get(1).id()).isEqualTo(uuid0);
        assertThat(updated.steps().get(1).ordinal()).isEqualTo(1);
        assertThat(updated.steps().get(1).value()).isEqualTo("https://a.test/start");

        assertThat(updated.steps().get(2).id()).isEqualTo(uuid1);
        assertThat(updated.steps().get(2).ordinal()).isEqualTo(2);
        assertThat(updated.steps().get(2).locatorCandidates()).containsExactly(new LocatorCandidate(LocatorStrategy.ROLE, "link", 0));
    }

    @Test
    void updateDuplicateNameOnSameSiteIsRejected() {
        JourneyStep step = new JourneyStep(UUID.randomUUID(), 0, StepAction.GOTO, List.of(), "https://a.test", null, false, 5000);
        long id1 = journeyService.create(siteA, "Flow A", List.of(step));
        long id2 = journeyService.create(siteA, "Flow B", List.of(step));

        assertThatThrownBy(() -> journeyService.update(id2, "Flow A", true, List.of(step)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("journey.name.duplicate");

        assertThatThrownBy(() -> journeyService.update(id2, "flow a", true, List.of(step)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("journey.name.duplicate");

        // Updating own name to same name is allowed
        journeyService.update(id2, "Flow B", true, List.of(step));
        JourneyDefinition def2 = journeyService.findDefinition(id2).orElseThrow();
        assertThat(def2.name()).isEqualTo("Flow B");
    }

    @Test
    void findEnabledBySiteReturnsOnlyEnabledJourneys() {
        JourneyStep step = new JourneyStep(UUID.randomUUID(), 0, StepAction.GOTO, List.of(), "https://a.test", null, false, 5000);
        long id1 = journeyService.create(siteA, "Beta Flow", List.of(step));
        long id2 = journeyService.create(siteA, "Alpha Flow", List.of(step));
        long id3 = journeyService.create(siteA, "Gamma Flow", List.of(step));

        journeyService.update(id3, "Gamma Flow", false, List.of(step));
        entityManager.flush();
        entityManager.clear();

        List<JourneyDefinition> enabledList = journeyService.findEnabledBySite(siteA);
        assertThat(enabledList).hasSize(2);
        assertThat(enabledList.get(0).name()).isEqualTo("Alpha Flow");
        assertThat(enabledList.get(0).id()).isEqualTo(id2);
        assertThat(enabledList.get(1).name()).isEqualTo("Beta Flow");
        assertThat(enabledList.get(1).id()).isEqualTo(id1);
    }

    @Test
    void deleteRemovesJourney() {
        JourneyStep step = new JourneyStep(UUID.randomUUID(), 0, StepAction.GOTO, List.of(), "https://a.test", null, false, 5000);
        long journeyId = journeyService.create(siteA, "To Delete", List.of(step));
        entityManager.flush();
        entityManager.clear();

        journeyService.delete(journeyId);
        entityManager.flush();
        entityManager.clear();

        assertThat(journeyService.findDefinition(journeyId)).isEmpty();
    }

    @Test
    void jsonbByteStabilityRoundTripProducesIdenticalJson() {
        UUID stepId = UUID.randomUUID();
        LocatorCandidate cand1 = new LocatorCandidate(LocatorStrategy.TEST_ID, "btn", 0);
        StepAssertion assertion = new StepAssertion(AssertionType.VISIBLE, "true");
        JourneyStep step = new JourneyStep(stepId, 0, StepAction.CLICK, List.of(cand1), null, assertion, true, 5000);

        long id = journeyService.create(siteA, "Stable JSON", List.of(step));
        entityManager.flush();
        entityManager.clear();

        String initialJson = jdbcTemplate.queryForObject("SELECT steps FROM journey WHERE id = ?", String.class, id);

        JourneyDefinition loaded = journeyService.findDefinition(id).orElseThrow();
        journeyService.update(id, loaded.name(), loaded.enabled(), loaded.steps());
        entityManager.flush();
        entityManager.clear();

        String updatedJson = jdbcTemplate.queryForObject("SELECT steps FROM journey WHERE id = ?", String.class, id);
        assertThat(updatedJson).isEqualTo(initialJson);
    }
}
