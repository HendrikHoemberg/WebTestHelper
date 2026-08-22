package dev.hendrikhoemberg.webtesthelper.runner.persistence;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RunRepository extends JpaRepository<RunEntity, Long> {

    List<RunEntity> findBySiteIdOrderByQueuedAtDesc(Long siteId, Limit limit);

    Optional<RunEntity> findFirstBySiteIdAndStatusOrderByQueuedAtAsc(Long siteId, RunStatus status);

    /** Matches ux_run_single_queued_per_site_scope: the tiers of spec 9 queue independently. */
    Optional<RunEntity> findFirstBySiteIdAndStatusAndScopeOrderByQueuedAtAsc(
            Long siteId, RunStatus status, RunScope scope);

    /** The previous completed run of the same site — the diff's baseline (spec 6.3). */
    Optional<RunEntity> findFirstBySiteIdAndStatusAndIdLessThanOrderByIdDesc(
            Long siteId, RunStatus status, Long beforeRunId);
}
