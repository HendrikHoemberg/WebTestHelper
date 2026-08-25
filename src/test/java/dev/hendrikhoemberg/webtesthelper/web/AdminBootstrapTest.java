package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import dev.hendrikhoemberg.webtesthelper.web.persistence.AppUserEntity;
import dev.hendrikhoemberg.webtesthelper.web.persistence.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class AdminBootstrapTest extends AbstractPostgresTest {

    @Autowired
    AppUserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AppUserService appUserService;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void createsAdminFromPropertiesWhenConfigured() throws Exception {
        AdminProperties properties = new AdminProperties("admin", "secret-pass");
        AdminBootstrap bootstrap = new AdminBootstrap(appUserService, properties);

        bootstrap.run(new DefaultApplicationArguments());

        assertThat(userRepository.count()).isEqualTo(1);
        AppUserEntity user = userRepository.findByUsernameIgnoreCase("admin").orElseThrow();
        assertThat(user.getRole()).isEqualTo(AppRole.ADMIN);
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getPasswordHash()).isNotEqualTo("secret-pass");
        assertThat(passwordEncoder.matches("secret-pass", user.getPasswordHash())).isTrue();

        // Running a second time creates nothing
        bootstrap.run(new DefaultApplicationArguments());
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void createsAdminWithGeneratedPasswordWhenPropertyBlank() throws Exception {
        AdminProperties properties = new AdminProperties("admin", "");
        AdminBootstrap bootstrap = new AdminBootstrap(appUserService, properties);

        bootstrap.run(new DefaultApplicationArguments());

        assertThat(userRepository.count()).isEqualTo(1);
        AppUserEntity user = userRepository.findByUsernameIgnoreCase("admin").orElseThrow();
        assertThat(user.getRole()).isEqualTo(AppRole.ADMIN);
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getPasswordHash()).isNotBlank();
        assertThat(user.getPasswordHash()).isNotEqualTo("");
    }
}
