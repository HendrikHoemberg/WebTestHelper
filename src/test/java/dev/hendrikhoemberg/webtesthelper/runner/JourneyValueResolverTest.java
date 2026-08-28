package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.CredentialService;
import dev.hendrikhoemberg.webtesthelper.catalog.SecretBox;
import dev.hendrikhoemberg.webtesthelper.catalog.SecretText;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.CredentialEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.CredentialRepository;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteRepository;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.LocatorStrategy;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JourneyValueResolverTest {

    private CredentialRepository credentials;
    private SiteRepository sites;
    private SecretBox secretBox;
    private CredentialService credentialService;
    private JourneyValueResolver resolver;

    private static final long SITE_ID = 42L;

    @BeforeEach
    void setUp() {
        credentials = mock(CredentialRepository.class);
        sites = mock(SiteRepository.class);
        secretBox = mock(SecretBox.class);
        credentialService = new CredentialService(credentials, sites, secretBox);
        resolver = new JourneyValueResolver(credentialService);
    }

    @Test
    void plainTextValueResolvesToNonSensitiveSecretText() {
        JourneyStep step = stepWithValue("ordinary plain text");

        SecretText result = resolver.resolve(SITE_ID, step);

        assertThat(result.expose()).isEqualTo("ordinary plain text");
        assertThat(result.sensitive()).isFalse();
        assertThat(result.toString()).isEqualTo("ordinary plain text");
    }

    @Test
    void credentialReferenceResolvesToStoredSecretAndLogsAsTemplate() {
        CredentialEntity entity = new CredentialEntity();
        entity.setName("login");
        entity.setUsername("admin");
        entity.setSecret("encrypted-pass");
        when(credentials.findBySiteIdAndName(SITE_ID, "login")).thenReturn(Optional.of(entity));
        when(secretBox.decrypt("encrypted-pass")).thenReturn("secret123");

        JourneyStep step = stepWithValue("{{cred.login.password}}");

        SecretText result = resolver.resolve(SITE_ID, step);

        assertThat(result.expose()).isEqualTo("secret123");
        assertThat(result.sensitive()).isTrue();
        assertThat(result.toString()).isEqualTo("{{cred.login.password}}");
        assertThat(result.toString()).doesNotContain("secret123");
    }

    @Test
    void mixedLiteralAndCredentialReferenceResolvesReferenceAndPreservesLiteral() {
        CredentialEntity entity = new CredentialEntity();
        entity.setName("login");
        entity.setUsername("admin");
        when(credentials.findBySiteIdAndName(SITE_ID, "login")).thenReturn(Optional.of(entity));

        JourneyStep step = stepWithValue("Benutzer {{cred.login.username}} meldet sich an");

        SecretText result = resolver.resolve(SITE_ID, step);

        assertThat(result.expose()).isEqualTo("Benutzer admin meldet sich an");
        assertThat(result.sensitive()).isTrue();
        assertThat(result.toString()).isEqualTo("Benutzer {{cred.login.username}} meldet sich an");
    }

    @Test
    void multipleCredentialReferencesInOneStepAreAllResolved() {
        CredentialEntity entity = new CredentialEntity();
        entity.setName("login");
        entity.setUsername("admin");
        entity.setSecret("encrypted-pass");
        when(credentials.findBySiteIdAndName(SITE_ID, "login")).thenReturn(Optional.of(entity));
        when(secretBox.decrypt("encrypted-pass")).thenReturn("secret123");

        JourneyStep step = stepWithValue("{{cred.login.username}}:{{cred.login.password}}");

        SecretText result = resolver.resolve(SITE_ID, step);

        assertThat(result.expose()).isEqualTo("admin:secret123");
        assertThat(result.sensitive()).isTrue();
        assertThat(result.toString()).isEqualTo("{{cred.login.username}}:{{cred.login.password}}");
    }

    @Test
    void unknownCredentialReferenceThrowsWithGermanKeyAndToken() {
        when(credentials.findBySiteIdAndName(SITE_ID, "unknown")).thenReturn(Optional.empty());

        JourneyStep step = stepWithValue("{{cred.unknown.password}}");

        assertThatThrownBy(() -> resolver.resolve(SITE_ID, step))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential.reference.unknown")
                .hasMessageContaining("{{cred.unknown.password}}");
    }

    @Test
    void malformedCredentialReferenceThrowsWithGermanKey() {
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
            JourneyStep step = stepWithValue(malformed);
            assertThatThrownBy(() -> resolver.resolve(SITE_ID, step))
                    .as("Expected malformed error for: %s", malformed)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("credential.reference.malformed");
        }
    }

    @Test
    void unreadableSecretThrowsWithGermanKeyAndTokenAndNoPlaintextOrCiphertext() {
        CredentialEntity entity = new CredentialEntity();
        entity.setName("corrupt");
        entity.setSecret("corrupted-ciphertext");
        when(credentials.findBySiteIdAndName(SITE_ID, "corrupt")).thenReturn(Optional.of(entity));
        when(secretBox.decrypt("corrupted-ciphertext")).thenThrow(new RuntimeException("Decryption error"));

        JourneyStep step = stepWithValue("{{cred.corrupt.password}}");

        assertThatThrownBy(() -> resolver.resolve(SITE_ID, step))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credential.reference.unreadable")
                .hasMessageContaining("{{cred.corrupt.password}}")
                .hasMessageNotContaining("corrupted-ciphertext")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void nullStepValueResolvesToEmptyNonSensitiveSecretText() {
        JourneyStep step = stepWithNullValue();

        SecretText result = resolver.resolve(SITE_ID, step);

        assertThat(result.expose()).isEqualTo("");
        assertThat(result.sensitive()).isFalse();
        assertThat(result.toString()).isEqualTo("");
    }

    @Test
    void emptyStepValueResolvesToEmptyNonSensitiveSecretText() {
        JourneyStep step = stepWithValue("");

        SecretText result = resolver.resolve(SITE_ID, step);

        assertThat(result.expose()).isEqualTo("");
        assertThat(result.sensitive()).isFalse();
        assertThat(result.toString()).isEqualTo("");
    }

    @Test
    void nullStepThrowsNullPointerException() {
        assertThatThrownBy(() -> resolver.resolve(SITE_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("step");
    }

    @Test
    void nullCredentialServiceInConstructorThrowsNullPointerException() {
        assertThatThrownBy(() -> new JourneyValueResolver(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("credentialService");
    }

    private static JourneyStep stepWithValue(String value) {
        return new JourneyStep(
                UUID.randomUUID(),
                0,
                StepAction.FILL,
                List.of(),
                value,
                null,
                false,
                5000
        );
    }

    private static JourneyStep stepWithNullValue() {
        return new JourneyStep(
                UUID.randomUUID(),
                0,
                StepAction.CLICK,
                List.of(new LocatorCandidate(LocatorStrategy.TEST_ID, "btn", 0)),
                null,
                null,
                false,
                5000
        );
    }
}
