package dev.hendrikhoemberg.webtesthelper.findings.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MuteRuleRepository extends JpaRepository<MuteRuleEntity, Long> {

    @Query("SELECT r FROM MuteRuleEntity r WHERE r.siteId = :siteId OR r.siteId IS NULL ORDER BY r.id ASC")
    List<MuteRuleEntity> findForSite(@Param("siteId") Long siteId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE MuteRuleEntity r SET r.expiredAt = :now WHERE r.expiredAt IS NULL AND r.expiresAt <= :now")
    int expireRules(@Param("now") Instant now);
}
