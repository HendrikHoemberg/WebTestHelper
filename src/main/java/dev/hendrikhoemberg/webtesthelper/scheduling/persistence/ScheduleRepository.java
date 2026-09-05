package dev.hendrikhoemberg.webtesthelper.scheduling.persistence;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ScheduleRepository extends JpaRepository<ScheduleEntity, Long> {

    List<ScheduleEntity> findBySiteIdOrderByScope(long siteId);

    boolean existsBySiteIdAndScope(long siteId, RunScope scope);

    /** Enabled rows of specified sites whose {@code next_fire_at} has passed, oldest first (matches ix_schedule_due). */
    @Query("SELECT sch FROM ScheduleEntity sch WHERE sch.enabled = TRUE AND sch.siteId IN :siteIds AND sch.nextFireAt <= :now ORDER BY sch.nextFireAt ASC")
    List<ScheduleEntity> findDue(@Param("siteIds") Collection<Long> siteIds, @Param("now") Instant now, Limit limit);

    /** Enabled rows of specified sites that will ever fire, ordered per site then by next occurrence. */
    @Query("SELECT sch FROM ScheduleEntity sch WHERE sch.enabled = TRUE AND sch.siteId IN :siteIds AND sch.nextFireAt IS NOT NULL ORDER BY sch.siteId ASC, sch.nextFireAt ASC")
    List<ScheduleEntity> findNextFirePerSite(@Param("siteIds") Collection<Long> siteIds);

    /** Site ids that have at least one schedule row configured. */
    @Query("SELECT DISTINCT sch.siteId FROM ScheduleEntity sch")
    List<Long> findConfiguredSiteIds();
}
