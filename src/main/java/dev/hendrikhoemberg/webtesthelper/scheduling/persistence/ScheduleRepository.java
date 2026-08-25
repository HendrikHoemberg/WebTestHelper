package dev.hendrikhoemberg.webtesthelper.scheduling.persistence;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteEntity;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface ScheduleRepository extends JpaRepository<ScheduleEntity, Long> {

    List<ScheduleEntity> findBySiteIdOrderByScope(long siteId);

    boolean existsBySiteIdAndScope(long siteId, RunScope scope);

    /** Enabled rows whose {@code next_fire_at} has passed, oldest first (matches ix_schedule_due). */
    @Query("SELECT sch FROM ScheduleEntity sch WHERE sch.enabled = TRUE AND sch.nextFireAt <= :now ORDER BY sch.nextFireAt ASC")
    List<ScheduleEntity> findDue(Instant now, Limit limit);

    /** Site ids with no schedule row at all — D38's lazy backfill set. */
    @Query("SELECT s.id FROM SiteEntity s WHERE NOT EXISTS (SELECT sch.id FROM ScheduleEntity sch WHERE sch.siteId = s.id)")
    List<Long> findSiteIdsMissingSchedules();
}
