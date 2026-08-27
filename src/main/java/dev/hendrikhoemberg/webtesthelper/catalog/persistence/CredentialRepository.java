package dev.hendrikhoemberg.webtesthelper.catalog.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CredentialRepository extends JpaRepository<CredentialEntity, Long> {

    List<CredentialEntity> findBySiteIdOrderByNameAsc(long siteId);

    Optional<CredentialEntity> findBySiteIdAndName(long siteId, String name);

    boolean existsBySiteIdAndName(long siteId, String name);
}

