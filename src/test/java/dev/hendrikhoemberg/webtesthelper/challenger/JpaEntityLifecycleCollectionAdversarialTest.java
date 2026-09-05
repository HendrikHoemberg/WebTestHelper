package dev.hendrikhoemberg.webtesthelper.challenger;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.AppSettingEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.CredentialEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.JourneyEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.NotificationRecipientEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteCheckSettingEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteEntity;
import dev.hendrikhoemberg.webtesthelper.findings.persistence.MuteRuleEntity;
import dev.hendrikhoemberg.webtesthelper.reporting.persistence.NotificationEntity;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunEntity;
import dev.hendrikhoemberg.webtesthelper.scheduling.persistence.ScheduleEntity;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import dev.hendrikhoemberg.webtesthelper.auth.persistence.AppUserEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirical stress test verifying JPA entity equality, hash code stability, and collection behavior
 * in HashSets and HashMaps across the real database lifecycle (DB-03).
 */
class JpaEntityLifecycleCollectionAdversarialTest extends AbstractPostgresTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate tx;

    record EntityHarness<T>(
            Class<T> type,
            Supplier<T> factory,
            Function<T, ?> idGetter,
            BiConsumer<T, Long> idSetter
    ) {}

    static Stream<EntityHarness<?>> idBasedHarnesses() {
        return Stream.of(
                new EntityHarness<>(SiteEntity.class, () -> {
                    SiteEntity s = new SiteEntity();
                    s.setName("TestSite");
                    s.setBaseUrl("https://equality-test.example.com");
                    return s;
                }, SiteEntity::getId, SiteEntity::setId),

                new EntityHarness<>(ScheduleEntity.class, () -> {
                    ScheduleEntity s = new ScheduleEntity();
                    s.setSiteId(9999L);
                    s.setCron("0 0 * * * ?");
                    return s;
                }, ScheduleEntity::getId, ScheduleEntity::setId),

                new EntityHarness<>(RunEntity.class, () -> {
                    RunEntity r = new RunEntity();
                    r.setSiteId(9999L);
                    return r;
                }, RunEntity::getId, RunEntity::setId),

                new EntityHarness<>(SiteCheckSettingEntity.class, () -> {
                    SiteCheckSettingEntity c = new SiteCheckSettingEntity();
                    c.setSiteId(9999L);
                    c.setCheckType(dev.hendrikhoemberg.webtesthelper.model.CheckType.DEAD_LINK);
                    return c;
                }, SiteCheckSettingEntity::getId, SiteCheckSettingEntity::setId),

                new EntityHarness<>(CredentialEntity.class, () -> {
                    CredentialEntity c = new CredentialEntity();
                    c.setSiteId(9999L);
                    c.setUsername("user");
                    return c;
                }, CredentialEntity::getId, CredentialEntity::setId),

                new EntityHarness<>(JourneyEntity.class, () -> {
                    JourneyEntity j = new JourneyEntity();
                    j.setSiteId(9999L);
                    j.setName("Journey");
                    return j;
                }, JourneyEntity::getId, JourneyEntity::setId),

                new EntityHarness<>(NotificationRecipientEntity.class, () -> {
                    NotificationRecipientEntity n = new NotificationRecipientEntity();
                    n.setSiteId(9999L);
                    n.setEmail("test@example.com");
                    return n;
                }, NotificationRecipientEntity::getId, NotificationRecipientEntity::setId),

                new EntityHarness<>(MuteRuleEntity.class, () -> {
                    MuteRuleEntity m = new MuteRuleEntity();
                    m.setSiteId(9999L);
                    m.setReason("reason-123");
                    return m;
                }, MuteRuleEntity::getId, MuteRuleEntity::setId),

                new EntityHarness<>(NotificationEntity.class, () -> {
                    return new NotificationEntity("ops@example.com", "Alert", "<p>Alert</p>", "Alert");
                }, NotificationEntity::getId, NotificationEntity::setId),

                new EntityHarness<>(AppUserEntity.class, () -> {
                    AppUserEntity u = new AppUserEntity();
                    u.setUsername("testuser-" + System.nanoTime());
                    u.setPasswordHash("hashed");
                    u.setRole(dev.hendrikhoemberg.webtesthelper.auth.AppRole.USER);
                    return u;
                }, AppUserEntity::getId, AppUserEntity::setId)
        );
    }

    @ParameterizedTest
    @MethodSource("idBasedHarnesses")
    <T> void hashSetAndHashMapMaintainIntegrityAcrossLifecycle(EntityHarness<T> harness) {
        T entity = harness.factory().get();
        Set<T> set = new HashSet<>();
        Map<T, String> map = new HashMap<>();

        // 1. Transient phase
        set.add(entity);
        map.put(entity, "initial_metadata");

        assertThat(set).contains(entity);
        assertThat(map.get(entity)).isEqualTo("initial_metadata");
        assertThat(set).hasSize(1);
        assertThat(map).hasSize(1);

        // 2. Persist phase (simulating JPA generating and assigning ID)
        Long assignedId = 42L;
        harness.idSetter().accept(entity, assignedId);

        // CRITICAL: Must still be findable in both Set and Map after ID mutation
        assertThat(set)
                .as("Entity must remain in HashSet after id assigned")
                .contains(entity);
        assertThat(map.get(entity))
                .as("Entity must remain accessible in HashMap after id assigned")
                .isEqualTo("initial_metadata");

        // 3. Detached / freshly loaded copy with same ID
        T loadedCopy = harness.factory().get();
        harness.idSetter().accept(loadedCopy, assignedId);

        // Even though loadedCopy is a DIFFERENT object in memory, it represents the same database entity
        assertThat(loadedCopy).isEqualTo(entity);
        assertThat(set)
                .as("Freshly loaded copy with same ID must match contains in HashSet")
                .contains(loadedCopy);
        assertThat(map.get(loadedCopy))
                .as("Freshly loaded copy with same ID must match key in HashMap")
                .isEqualTo("initial_metadata");

        // 4. Removal using loaded copy
        boolean removed = set.remove(loadedCopy);
        assertThat(removed).isTrue();
        assertThat(set).isEmpty();

        String removedValue = map.remove(loadedCopy);
        assertThat(removedValue).isEqualTo("initial_metadata");
        assertThat(map).isEmpty();
    }

    @Test
    @DisplayName("DB-03 Multi-Entity Collision: 50 transient instances in HashSet transition safely to distinct IDs")
    void multipleTransientEntitiesInHashSetSurviveIdMutation() {
        Set<SiteEntity> set = new HashSet<>();
        Map<SiteEntity, Integer> map = new HashMap<>();
        int count = 50;
        SiteEntity[] entities = new SiteEntity[count];

        for (int i = 0; i < count; i++) {
            SiteEntity s = new SiteEntity();
            s.setName("Site-" + i);
            s.setBaseUrl("https://site-" + i + ".example.com");
            entities[i] = s;
            set.add(s);
            map.put(s, i);
        }

        // All 50 transient entities have the same hashCode (getClass().hashCode())
        // But because id is null, only identity equality holds -> all 50 distinct instances must be retained!
        assertThat(set).hasSize(count);
        assertThat(map).hasSize(count);

        // Now mutate IDs to 1..50 (as database would assign during batch persist)
        for (int i = 0; i < count; i++) {
            entities[i].setId((long) (i + 1));
        }

        // Verify all 50 are still present and findable
        for (int i = 0; i < count; i++) {
            assertThat(set).contains(entities[i]);
            assertThat(map.get(entities[i])).isEqualTo(i);

            // And findable via a fresh detached instance
            SiteEntity fresh = new SiteEntity();
            fresh.setId((long) (i + 1));
            assertThat(set).contains(fresh);
            assertThat(map.get(fresh)).isEqualTo(i);
        }

        // Remove each via fresh instance
        for (int i = 0; i < count; i++) {
            SiteEntity fresh = new SiteEntity();
            fresh.setId((long) (i + 1));
            assertThat(set.remove(fresh)).isTrue();
            assertThat(map.remove(fresh)).isEqualTo(i);
        }

        assertThat(set).isEmpty();
        assertThat(map).isEmpty();
    }

    @Test
    @DisplayName("DB-03 AppSettingEntity: Natural key equality works across values and collection mutations")
    void appSettingNaturalKeyLifecycle() {
        Set<AppSettingEntity> set = new HashSet<>();
        Map<AppSettingEntity, String> map = new HashMap<>();

        AppSettingEntity s1 = new AppSettingEntity("security.max_attempts", "5", false);
        set.add(s1);
        map.put(s1, "rule-1");

        assertThat(set).contains(s1);
        assertThat(map.get(s1)).isEqualTo("rule-1");

        // Clone with same natural key but different value
        AppSettingEntity s1Updated = new AppSettingEntity("security.max_attempts", "10", false);
        assertThat(s1Updated).isEqualTo(s1);
        assertThat(set).contains(s1Updated);
        assertThat(map.get(s1Updated)).isEqualTo("rule-1");

        // Overwrite map value using natural key
        map.put(s1Updated, "rule-updated");
        assertThat(map).hasSize(1);
        assertThat(map.get(s1)).isEqualTo("rule-updated");

        // Adding to Set does not increase size
        set.add(s1Updated);
        assertThat(set).hasSize(1);

        // Removal by key
        assertThat(set.remove(s1Updated)).isTrue();
        assertThat(set).isEmpty();
    }

    @Test
    @DisplayName("DB-03 Real JPA EntityManager: Entity persisted into real PostgreSQL database retains HashSet identity")
    void realJpaEntityManagerPersistenceRetainsCollectionIntegrity() {
        Set<SiteEntity> set = new HashSet<>();
        Map<SiteEntity, String> map = new HashMap<>();

        SiteEntity site = new SiteEntity();
        site.setName("RealDbSite");
        site.setBaseUrl("https://realdb.example.com");

        // Add transient entity to collections
        set.add(site);
        map.put(site, "attached_tag");

        // Persist in real database transaction
        Long generatedId = tx.execute(status -> {
            entityManager.persist(site);
            entityManager.flush();
            return site.getId();
        });

        assertThat(generatedId).isNotNull();

        // Verify entity is still findable after real database persist
        assertThat(set).contains(site);
        assertThat(map.get(site)).isEqualTo("attached_tag");

        // Clear persistence context (simulating detach)
        entityManager.clear();

        // Load fresh instance from database
        SiteEntity loadedFromDb = entityManager.find(SiteEntity.class, generatedId);
        assertThat(loadedFromDb).isNotNull();
        assertThat(loadedFromDb).isNotSameAs(site);
        assertThat(loadedFromDb).isEqualTo(site);

        // Must be found in original set and map via the loaded instance
        assertThat(set).contains(loadedFromDb);
        assertThat(map.get(loadedFromDb)).isEqualTo("attached_tag");

        // Remove using loaded instance
        assertThat(set.remove(loadedFromDb)).isTrue();
        assertThat(set).isEmpty();
        assertThat(map.remove(loadedFromDb)).isEqualTo("attached_tag");
        assertThat(map).isEmpty();
    }
}
