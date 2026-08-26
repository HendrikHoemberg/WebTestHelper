package dev.hendrikhoemberg.webtesthelper.scheduling.persistence;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteEntity;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

/**
 * <b>This interface depends on {@code catalog}'s internals and no automated check can see it.</b>
 * The {@code @Query} strings name {@link SiteEntity} — a type inside {@code catalog.persistence},
 * not on {@code catalog}'s exposed API. JPQL names entities as text, so the reference lives only
 * in a string literal: it compiles to no bytecode, and {@code ModularityTest}'s ArchUnit scan
 * therefore passes on it rather than approving it. The import above is deliberately kept, unused,
 * so that the coupling is at least greppable from this file.
 *
 * <p>The dependency is intended. Filtering disabled sites in Java after the due query would let a
 * fleet of disabled sites consume the whole batch limit and starve the enabled ones, so the site's
 * own enabled flag has to be a predicate in the query (D38/D41). What is <em>not</em> guaranteed is
 * that a future rename of {@code SiteEntity} or its {@code enabled} field will be caught by the
 * module checker; it is caught by Hibernate, which validates every JPQL string at context startup
 * and fails the whole {@code @SpringBootTest} suite.
 */
public interface ScheduleRepository extends JpaRepository<ScheduleEntity, Long> {

    List<ScheduleEntity> findBySiteIdOrderByScope(long siteId);

    boolean existsBySiteIdAndScope(long siteId, RunScope scope);

    /** Enabled rows of enabled sites whose {@code next_fire_at} has passed, oldest first (matches ix_schedule_due). */
    @Query("SELECT sch FROM ScheduleEntity sch JOIN SiteEntity site ON site.id = sch.siteId WHERE sch.enabled = TRUE AND site.enabled = TRUE AND sch.nextFireAt <= :now ORDER BY sch.nextFireAt ASC")
    List<ScheduleEntity> findDue(Instant now, Limit limit);

    /** Enabled rows of enabled sites that will ever fire, ordered per site then by next occurrence (the join matches findDue's D41 predicate). */
    @Query("SELECT sch FROM ScheduleEntity sch JOIN SiteEntity site ON site.id = sch.siteId WHERE sch.enabled = TRUE AND site.enabled = TRUE AND sch.nextFireAt IS NOT NULL ORDER BY sch.siteId ASC, sch.nextFireAt ASC")
    List<ScheduleEntity> findNextFirePerSite();

    /** Site ids with no schedule row at all — D38's lazy backfill set. */
    @Query("SELECT s.id FROM SiteEntity s WHERE NOT EXISTS (SELECT sch.id FROM ScheduleEntity sch WHERE sch.siteId = s.id)")
    List<Long> findSiteIdsMissingSchedules();
}
