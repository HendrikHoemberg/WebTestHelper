package dev.hendrikhoemberg.webtesthelper.catalog.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JourneyRepository extends JpaRepository<JourneyEntity, Long> {

    List<JourneyEntity> findBySiteIdOrderByNameAsc(long siteId);

    List<JourneyEntity> findBySiteIdAndEnabledTrueOrderByNameAsc(long siteId);

    Optional<JourneyEntity> findBySiteIdAndName(long siteId, String name);

    Optional<JourneyEntity> findBySiteIdAndNameIgnoreCase(long siteId, String name);

    boolean existsBySiteIdAndNameIgnoreCase(long siteId, String name);

    boolean existsBySiteIdAndNameIgnoreCaseAndIdNot(long siteId, String name, long id);
}
