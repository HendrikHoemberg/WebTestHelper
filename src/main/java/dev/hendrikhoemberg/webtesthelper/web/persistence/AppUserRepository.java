package dev.hendrikhoemberg.webtesthelper.web.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUserEntity, Long> {

    Optional<AppUserEntity> findByUsernameIgnoreCase(String username);
}
