package dev.hendrikhoemberg.webtesthelper.catalog.persistence;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SiteCheckSettingRepository extends JpaRepository<SiteCheckSettingEntity, Long> {

    List<SiteCheckSettingEntity> findBySiteId(Long siteId);

    List<SiteCheckSettingEntity> findBySiteIdIn(Collection<Long> siteIds);

    Optional<SiteCheckSettingEntity> findBySiteIdAndCheckType(Long siteId, CheckType checkType);
}
