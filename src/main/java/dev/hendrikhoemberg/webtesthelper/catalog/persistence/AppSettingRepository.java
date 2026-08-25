package dev.hendrikhoemberg.webtesthelper.catalog.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSettingEntity, String> {
}
