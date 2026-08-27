package dev.hendrikhoemberg.webtesthelper.catalog.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CredentialRepository extends JpaRepository<CredentialEntity, Long> {

    List<CredentialEntity> findBySiteIdOrderByNameAsc(long siteId);

    boolean existsBySiteIdAndName(long siteId, String name);
}
