package dev.hendrikhoemberg.webtesthelper.web.persistence;

import dev.hendrikhoemberg.webtesthelper.web.AppRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUserEntity, Long> {

    Optional<AppUserEntity> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    long countByRoleAndEnabled(AppRole role, boolean enabled);
}
