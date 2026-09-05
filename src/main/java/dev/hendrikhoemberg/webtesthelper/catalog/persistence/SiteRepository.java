package dev.hendrikhoemberg.webtesthelper.catalog.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SiteRepository extends JpaRepository<SiteEntity, Long> {

    Optional<SiteEntity> findByBaseUrl(String baseUrl);

    @Query("SELECT s.id FROM SiteEntity s WHERE s.enabled = TRUE")
    List<Long> findEnabledIds();

    @Query("SELECT s.id FROM SiteEntity s")
    List<Long> findAllIds();
}
