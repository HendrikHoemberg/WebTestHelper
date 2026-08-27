package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class CredentialResolutionTest extends AbstractPostgresTest {

    @Autowired
    CredentialService credentialService;

    @Autowired
    SiteService siteService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EntityManager entityManager;

    private long siteId;

    @BeforeEach
    void setUp() {
        siteId = siteService.create(new SiteForm("Resolution Test Site", "https://resolution.test/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
    }

    @Test
    void plainTemplateResolvesToItselfAndIsNotSensitive() {
        SecretText result = credentialService.resolve(siteId, "ordinary plain text");
        assertThat(result.expose()).isEqualTo("ordinary plain text");
        assertThat(result.sensitive()).isFalse();
        assertThat(result.toString()).isEqualTo("ordinary plain text");
    }

    @Test
    void singleReferenceResolvesToStoredPasswordAndLogsAsTemplate() {
        credentialService.create(siteId, "login", "admin", "secret123");

        SecretText result = credentialService.resolve(siteId, "{{cred.login.password}}");
        assertThat(result.expose()).isEqualTo("secret123");
        assertThat(result.sensitive()).isTrue();
        assertThat(result.toString()).isEqualTo("{{cred.login.password}}");
        assertThat(result.toString()).doesNotContain("secret123");
    }

    @Test
    void mixedTemplateSubstitutesReferenceAndKeepsTemplateInToString() {
        credentialService.create(siteId, "login", "admin", "secret123");

        SecretText result = credentialService.resolve(siteId, "Benutzer {{cred.login.username}} meldet sich an");
        assertThat(result.expose()).isEqualTo("Benutzer admin meldet sich an");
        assertThat(result.sensitive()).isTrue();
        assertThat(result.toString()).isEqualTo("Benutzer {{cred.login.username}} meldet sich an");
    }

    @Test
    void multipleReferencesInOneTemplateAreAllResolved() {
        credentialService.create(siteId, "login", "admin", "secret123");

        SecretText result = credentialService.resolve(siteId, "{{cred.login.username}}:{{cred.login.password}}");
        assertThat(result.expose()).isEqualTo("admin:secret123");
        assertThat(result.sensitive()).isTrue();
        assertThat(result.toString()).isEqualTo("{{cred.login.username}}:{{cred.login.password}}");
    }

    @Test
    void unknownCredentialReferenceThrowsWithKeyAndToken() {
        assertThatThrownBy(() -> credentialService.resolve(siteId, "{{cred.unknown.password}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential.reference.unknown")
                .hasMessageContaining("{{cred.unknown.password}}");
    }

    @Test
    void unreadableSecretThrowsWithKeyAndTokenAndNoPlaintextOrCiphertext() {
        long id = credentialService.create(siteId, "corrupt", "admin", "secret123");
        jdbcTemplate.update("UPDATE credential SET secret = 'corrupted-ciphertext' WHERE id = ?", id);
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> credentialService.resolve(siteId, "{{cred.corrupt.password}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credential.reference.unreadable")
                .hasMessageContaining("{{cred.corrupt.password}}")
                .hasMessageNotContaining("corrupted-ciphertext")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void malformedShapesThrowWithMalformedKey() {
        List<String> malformedTemplates = List.of(
                "{{cred.login.pasword}}",
                "{{cred.Login.password}}",
                "{{ cred.login.password }}",
                "{{cred.login.pin}}",
                "{{cred.}}",
                "{{cred.login}}",
                "{{cred}}"
        );

        for (String malformed : malformedTemplates) {
            assertThatThrownBy(() -> credentialService.resolve(siteId, malformed))
                    .as("Expected malformed error for: %s", malformed)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("credential.reference.malformed");
        }
    }

    @Test
    void nullTemplateResolvesToNonSensitiveSecretTextExposingNull() {
        SecretText result = credentialService.resolve(siteId, null);
        assertThat(result.expose()).isNull();
        assertThat(result.sensitive()).isFalse();
        assertThat(result.toString()).isNull();
    }
}
