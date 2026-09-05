package dev.hendrikhoemberg.webtesthelper.persistence;

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
import dev.hendrikhoemberg.webtesthelper.auth.persistence.AppUserEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class JpaEntityEqualityTest {

    record EntityFixture<T>(
            Class<T> type,
            Supplier<T> factory,
            BiConsumer<T, Long> idSetter
    ) {}

    static Stream<EntityFixture<?>> idBasedEntities() {
        return Stream.of(
                new EntityFixture<>(SiteEntity.class, SiteEntity::new, SiteEntity::setId),
                new EntityFixture<>(SiteCheckSettingEntity.class, SiteCheckSettingEntity::new, SiteCheckSettingEntity::setId),
                new EntityFixture<>(CredentialEntity.class, CredentialEntity::new, CredentialEntity::setId),
                new EntityFixture<>(JourneyEntity.class, JourneyEntity::new, JourneyEntity::setId),
                new EntityFixture<>(NotificationRecipientEntity.class, NotificationRecipientEntity::new, NotificationRecipientEntity::setId),
                new EntityFixture<>(ScheduleEntity.class, ScheduleEntity::new, ScheduleEntity::setId),
                new EntityFixture<>(RunEntity.class, RunEntity::new, RunEntity::setId),
                new EntityFixture<>(MuteRuleEntity.class, MuteRuleEntity::new, MuteRuleEntity::setId),
                new EntityFixture<>(NotificationEntity.class, NotificationEntity::new, NotificationEntity::setId),
                new EntityFixture<>(AppUserEntity.class, AppUserEntity::new, AppUserEntity::setId)
        );
    }

    @ParameterizedTest
    @MethodSource("idBasedEntities")
    <T> void reflexivity(EntityFixture<T> fixture) {
        T transientEntity = fixture.factory().get();
        assertThat(transientEntity).isEqualTo(transientEntity);

        T persistedEntity = fixture.factory().get();
        fixture.idSetter().accept(persistedEntity, 1L);
        assertThat(persistedEntity).isEqualTo(persistedEntity);
    }

    @ParameterizedTest
    @MethodSource("idBasedEntities")
    <T> void nullAndTypeSafety(EntityFixture<T> fixture) {
        T entity = fixture.factory().get();
        fixture.idSetter().accept(entity, 1L);

        assertThat(entity).isNotEqualTo(null);
        assertThat(entity).isNotEqualTo("some string");
        assertThat(entity).isNotEqualTo(new Object());
    }

    @ParameterizedTest
    @MethodSource("idBasedEntities")
    <T> void transientEntitiesAreNotEqual(EntityFixture<T> fixture) {
        T e1 = fixture.factory().get();
        T e2 = fixture.factory().get();

        assertThat(e1).isNotEqualTo(e2);
        assertThat(e2).isNotEqualTo(e1);
    }

    @ParameterizedTest
    @MethodSource("idBasedEntities")
    <T> void persistedEntitiesWithSameIdAreEqual(EntityFixture<T> fixture) {
        T e1 = fixture.factory().get();
        T e2 = fixture.factory().get();
        fixture.idSetter().accept(e1, 42L);
        fixture.idSetter().accept(e2, 42L);

        assertThat(e1).isEqualTo(e2);
        assertThat(e2).isEqualTo(e1);
        assertThat(e1.hashCode()).isEqualTo(e2.hashCode());
    }

    @ParameterizedTest
    @MethodSource("idBasedEntities")
    <T> void persistedEntitiesWithDifferentIdsAreNotEqual(EntityFixture<T> fixture) {
        T e1 = fixture.factory().get();
        T e2 = fixture.factory().get();
        fixture.idSetter().accept(e1, 1L);
        fixture.idSetter().accept(e2, 2L);

        assertThat(e1).isNotEqualTo(e2);
        assertThat(e2).isNotEqualTo(e1);
    }

    @ParameterizedTest
    @MethodSource("idBasedEntities")
    <T> void transientComparedToPersistedIsNotEqual(EntityFixture<T> fixture) {
        T transientEntity = fixture.factory().get();
        T persistedEntity = fixture.factory().get();
        fixture.idSetter().accept(persistedEntity, 1L);

        assertThat(transientEntity).isNotEqualTo(persistedEntity);
        assertThat(persistedEntity).isNotEqualTo(transientEntity);
    }

    @ParameterizedTest
    @MethodSource("idBasedEntities")
    <T> void hashSetBucketStabilityOnIdMutation(EntityFixture<T> fixture) {
        T entity = fixture.factory().get();
        Set<T> set = new HashSet<>();
        set.add(entity);

        assertThat(set).contains(entity);

        // Simulate JPA assigning an ID on persist
        fixture.idSetter().accept(entity, 999L);

        // Crucial JPA contract check: entity MUST still be found in the HashSet!
        assertThat(set)
                .as("Entity must remain findable in HashSet after ID is assigned")
                .contains(entity);
    }

    @Test
    @DisplayName("AppSettingEntity natural key equality tests")
    void appSettingNaturalKeyEquality() {
        AppSettingEntity s1 = new AppSettingEntity();
        s1.setSettingKey("mail.host");
        s1.setSettingValue("smtp.example.com");

        AppSettingEntity s2 = new AppSettingEntity();
        s2.setSettingKey("mail.host");
        s2.setSettingValue("smtp.other.com");

        AppSettingEntity s3 = new AppSettingEntity();
        s3.setSettingKey("mail.port");
        s3.setSettingValue("25");

        // Reflexivity
        assertThat(s1).isEqualTo(s1);

        // Null and type safety
        assertThat(s1).isNotEqualTo(null);
        assertThat(s1).isNotEqualTo("mail.host");

        // Same key equals
        assertThat(s1).isEqualTo(s2);
        assertThat(s2).isEqualTo(s1);
        assertThat(s1.hashCode()).isEqualTo(s2.hashCode());

        // Different key not equals
        assertThat(s1).isNotEqualTo(s3);

        // Null key safety
        AppSettingEntity nullKey1 = new AppSettingEntity();
        AppSettingEntity nullKey2 = new AppSettingEntity();
        assertThat(nullKey1).isNotEqualTo(nullKey2);
        assertThat(nullKey1).isNotEqualTo(s1);

        // HashSet stability
        Set<AppSettingEntity> set = new HashSet<>();
        set.add(s1);
        assertThat(set).contains(s2);
    }
}
