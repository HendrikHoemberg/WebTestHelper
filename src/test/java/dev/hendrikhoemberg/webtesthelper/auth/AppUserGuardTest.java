package dev.hendrikhoemberg.webtesthelper.auth;

import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import dev.hendrikhoemberg.webtesthelper.auth.persistence.AppUserEntity;
import dev.hendrikhoemberg.webtesthelper.auth.persistence.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppUserGuardTest extends AbstractPostgresTest {

    @Autowired
    AppUserRepository userRepository;

    @Autowired
    AppUserService appUserService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    private long admin(String username) {
        return appUserService.create(username, "password123", AppRole.ADMIN);
    }

    @Test
    void lastEnabledAdminCannotBeDisabled() {
        long id = admin("admin");

        assertThatThrownBy(() -> appUserService.setEnabled(id, false))
                .isInstanceOfSatisfying(UserValidationException.class,
                        ex -> assertThat(ex.messageKey()).isEqualTo("user.lastAdmin"));
    }

    @Test
    void lastEnabledAdminCannotBeDemoted() {
        long id = admin("admin");

        assertThatThrownBy(() -> appUserService.setRole(id, AppRole.USER))
                .isInstanceOfSatisfying(UserValidationException.class,
                        ex -> assertThat(ex.messageKey()).isEqualTo("user.lastAdmin"));
    }

    @Test
    void lastEnabledAdminCannotBeDeleted() {
        long id = admin("admin");

        assertThatThrownBy(() -> appUserService.delete(id))
                .isInstanceOfSatisfying(UserValidationException.class,
                        ex -> assertThat(ex.messageKey()).isEqualTo("user.lastAdmin"));
    }

    @Test
    void lastEnabledAdminCanChangeOwnPassword() {
        long id = admin("admin");

        assertThatNoException().isThrownBy(() -> appUserService.setPassword(id, "newPassword123"));
        AppUserEntity entity = userRepository.findById(id).orElseThrow();
        assertThat(passwordEncoder.matches("newPassword123", entity.getPasswordHash())).isTrue();
    }

    @Test
    void withTwoEnabledAdminsAllFourOperationsSucceed() {
        long enableTarget = admin("enable-target");
        admin("enable-backup");
        assertThatNoException().isThrownBy(() -> appUserService.setEnabled(enableTarget, false));

        long demoteTarget = admin("demote-target");
        admin("demote-backup");
        assertThatNoException().isThrownBy(() -> appUserService.setRole(demoteTarget, AppRole.USER));

        long deleteTarget = admin("delete-target");
        admin("delete-backup");
        assertThatNoException().isThrownBy(() -> appUserService.delete(deleteTarget));

        long passwordTarget = admin("password-target");
        assertThatNoException().isThrownBy(() -> appUserService.setPassword(passwordTarget, "newPassword123"));
        AppUserEntity entity = userRepository.findById(passwordTarget).orElseThrow();
        assertThat(passwordEncoder.matches("newPassword123", entity.getPasswordHash())).isTrue();
    }

    @Test
    void disablingOneAdminProtectsTheOther() {
        long first = admin("first");
        long second = admin("second");

        assertThatNoException().isThrownBy(() -> appUserService.setEnabled(first, false));
        assertThatThrownBy(() -> appUserService.setEnabled(second, false))
                .isInstanceOf(UserValidationException.class);
        assertThatThrownBy(() -> appUserService.delete(second))
                .isInstanceOf(UserValidationException.class);
    }

    @Test
    void disabledAdminDoesNotCountTowardsTheGuard() {
        long first = admin("first");
        long second = admin("second");
        AppUserEntity disabledAdmin = userRepository.findById(second).orElseThrow();
        disabledAdmin.setEnabled(false);
        userRepository.save(disabledAdmin);

        assertThat(appUserService.enabledAdminCount()).isEqualTo(1);
        assertThatThrownBy(() -> appUserService.setEnabled(first, false))
                .isInstanceOf(UserValidationException.class);
    }
}
