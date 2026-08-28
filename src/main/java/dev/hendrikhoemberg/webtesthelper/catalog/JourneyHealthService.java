package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.JourneyEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.JourneyRepository;
import dev.hendrikhoemberg.webtesthelper.model.JourneyReplayResult;
import dev.hendrikhoemberg.webtesthelper.model.ReplayStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Tracks journey health and reliability statistics across replays (§10.4, D106).
 */
@Service
@Transactional
public class JourneyHealthService {

    private final JourneyRepository journeys;

    public JourneyHealthService(JourneyRepository journeys) {
        this.journeys = Objects.requireNonNull(journeys, "journeys");
    }

    /**
     * Records the outcome of a journey replay and updates health statistics.
     *
     * <ul>
     *   <li>{@link ReplayStatus#PASSED}: updates {@code lastSuccessAt} to now and resets {@code consecutiveFailures} to 0.</li>
     *   <li>{@link ReplayStatus#DRIFTED}: counts as a pass (resets {@code consecutiveFailures} to 0 and updates {@code lastSuccessAt} to now)
     *       while incrementing {@code driftCount} by {@code result.driftCount()}.</li>
     *   <li>{@link ReplayStatus#FAILED}: increments {@code consecutiveFailures} by 1 without changing {@code lastSuccessAt}.</li>
     * </ul>
     *
     * @param journeyId ID of the replayed journey
     * @param result    outcome of the replay
     * @return updated health statistics
     * @throws IllegalArgumentException if the journey does not exist
     */
    public JourneyHealth record(long journeyId, JourneyReplayResult result) {
        Objects.requireNonNull(result, "result");
        JourneyEntity entity = journeys.findById(journeyId)
                .orElseThrow(() -> new IllegalArgumentException("Journey existiert nicht: " + journeyId));

        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        switch (result.status()) {
            case PASSED -> {
                entity.setLastSuccessAt(now);
                entity.setConsecutiveFailures(0);
            }
            case DRIFTED -> {
                entity.setLastSuccessAt(now);
                entity.setConsecutiveFailures(0);
                int driftIncrement = result.driftCount() > 0 ? result.driftCount() : 1;
                entity.setDriftCount(entity.getDriftCount() + driftIncrement);
            }
            case FAILED -> {
                entity.setConsecutiveFailures(entity.getConsecutiveFailures() + 1);
            }
        }
        entity.setUpdatedAt(now);
        JourneyEntity saved = journeys.save(entity);
        return toHealth(saved);
    }

    /**
     * Queries the health statistics of a journey by ID.
     *
     * @param journeyId ID of the journey
     * @return health statistics, or empty if not found
     */
    @Transactional(readOnly = true)
    public Optional<JourneyHealth> health(long journeyId) {
        return journeys.findById(journeyId).map(this::toHealth);
    }

    /**
     * Queries the health statistics of all journeys belonging to a site, keyed by journey ID.
     *
     * @param siteId ID of the site
     * @return map of journey ID to health statistics
     */
    @Transactional(readOnly = true)
    public Map<Long, JourneyHealth> healthBySite(long siteId) {
        return journeys.findBySiteIdOrderByNameAsc(siteId).stream()
                .collect(Collectors.toMap(
                        JourneyEntity::getId,
                        this::toHealth,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    private JourneyHealth toHealth(JourneyEntity entity) {
        return new JourneyHealth(
                entity.getLastSuccessAt(),
                entity.getConsecutiveFailures(),
                entity.getDriftCount()
        );
    }
}
