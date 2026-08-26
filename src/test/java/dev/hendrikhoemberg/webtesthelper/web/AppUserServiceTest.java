package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import dev.hendrikhoemberg.webtesthelper.web.persistence.AppUserEntity;
import dev.hendrikhoemberg.webtesthelper.web.persistence.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppUserServiceTest extends AbstractPostgresTest {

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
    void createAndLoadUserCaseInsensitively() {
        assertThat(appUserService.isEmpty()).isTrue();

        long id = appUserService.create("TestUser", "pAssword123", AppRole.ADMIN);
        assertThat(id).isPositive();
        assertThat(appUserService.isEmpty()).isFalse();

        UserDetails userDetails = appUserService.loadUserByUsername("testuser");
        assertThat(userDetails.getUsername()).isEqualTo("TestUser");
        assertThat(passwordEncoder.matches("pAssword123", userDetails.getPassword())).isTrue();
        assertThat(userDetails.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void loadUserThrowsWhenNotFound() {
        assertThatThrownBy(() -> appUserService.loadUserByUsername("nonexistent"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void loadUserThrowsWhenDisabled() {
        long id = appUserService.create("disabledUser", "password123", AppRole.USER);
        AppUserEntity entity = userRepository.findById(id).orElseThrow();
        entity.setEnabled(false);
        userRepository.save(entity);

        assertThatThrownBy(() -> appUserService.loadUserByUsername("disabledUser"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void createRejectsBlankUsername() {
        assertThatThrownBy(() -> appUserService.create("", "password123", AppRole.USER))
                .isInstanceOfSatisfying(UserValidationException.class,
                        ex -> assertThat(ex.messageKey()).isEqualTo("user.username.blank"));
        assertThatThrownBy(() -> appUserService.create(" ", "password123", AppRole.USER))
                .isInstanceOf(UserValidationException.class);
    }

    @Test
    void createRejectsDuplicateUsernameCaseInsensitively() {
        appUserService.create("TestUser", "password123", AppRole.USER);

        assertThatThrownBy(() -> appUserService.create("testuser", "password123", AppRole.USER))
                .isInstanceOfSatisfying(UserValidationException.class,
                        ex -> {
                            assertThat(ex.messageKey()).isEqualTo("user.username.duplicate");
                            assertThat(ex.args()).containsExactly("testuser");
                        });
    }

    @Test
    void createRejectsPasswordUnderEightCharacters() {
        assertThatThrownBy(() -> appUserService.create("shortpw", "seven77", AppRole.USER))
                .isInstanceOfSatisfying(UserValidationException.class,
                        ex -> {
                            assertThat(ex.messageKey()).isEqualTo("user.password.tooShort");
                            assertThat(ex.args()).containsExactly(8);
                        });
    }

    @Test
    void listOrdersUsersByUsername() {
        appUserService.create("charlie", "password123", AppRole.USER);
        appUserService.create("alice", "password123", AppRole.USER);
        appUserService.create("bob", "password123", AppRole.ADMIN);

        assertThat(appUserService.list())
                .extracting(AppUserSummary::username)
                .containsExactly("alice", "bob", "charlie");
    }
}
