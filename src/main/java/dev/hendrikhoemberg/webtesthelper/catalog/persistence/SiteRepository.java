package dev.hendrikhoemberg.webtesthelper.catalog.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SiteRepository extends JpaRepository<SiteEntity, Long> {

    Optional<SiteEntity> findByBaseUrl(String baseUrl);
}
