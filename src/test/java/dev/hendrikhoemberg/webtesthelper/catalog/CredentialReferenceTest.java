package dev.hendrikhoemberg.webtesthelper.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialReferenceTest {

    @Test
    void findAllOnPlainStringReturnsEmpty() {
        assertThat(CredentialReference.findAll("plain string without references")).isEmpty();
        assertThat(CredentialReference.findAll("")).isEmpty();
        assertThat(CredentialReference.findAll(null)).isEmpty();
    }

    @Test
    void findAllOnSingleReferenceReturnsParsedNameAndField() {
        List<CredentialReference> refs = CredentialReference.findAll("{{cred.login.password}}");
        assertThat(refs).containsExactly(new CredentialReference("login", CredentialField.PASSWORD));
    }

    @Test
    void findAllOnMultipleReferencesReturnsAllInOrderWithDuplicatesKept() {
        List<CredentialReference> refs = CredentialReference.findAll(
                "{{cred.login.username}}/{{cred.login.password}} and {{cred.login.username}}"
        );
        assertThat(refs).containsExactly(
                new CredentialReference("login", CredentialField.USERNAME),
                new CredentialReference("login", CredentialField.PASSWORD),
                new CredentialReference("login", CredentialField.USERNAME)
        );
    }

    @Test
    void findAllOnMalformedShapesReturnsEmpty() {
        assertThat(CredentialReference.findAll("{{cred.Login.password}}")).isEmpty();
        assertThat(CredentialReference.findAll("{{cred.login.pin}}")).isEmpty();
        assertThat(CredentialReference.findAll("{{ cred.login.password }}")).isEmpty();
        assertThat(CredentialReference.findAll("{{cred.login.pasword}}")).isEmpty();
        assertThat(CredentialReference.findAll("{{cred.}}")).isEmpty();
    }

    @Test
    void tokenRoundTripsReferenceBackToLiteralForm() {
        CredentialReference pwRef = new CredentialReference("login", CredentialField.PASSWORD);
        assertThat(pwRef.token()).isEqualTo("{{cred.login.password}}");

        CredentialReference userRef = new CredentialReference("my-api_key", CredentialField.USERNAME);
        assertThat(userRef.token()).isEqualTo("{{cred.my-api_key.username}}");
    }

    @Test
    void credentialFieldTokenAndParse() {
        assertThat(CredentialField.USERNAME.token()).isEqualTo("username");
        assertThat(CredentialField.PASSWORD.token()).isEqualTo("password");

        assertThat(CredentialField.parse("username")).contains(CredentialField.USERNAME);
        assertThat(CredentialField.parse("password")).contains(CredentialField.PASSWORD);
        assertThat(CredentialField.parse("pin")).isEmpty();
        assertThat(CredentialField.parse("Password")).isEmpty();
        assertThat(CredentialField.parse(null)).isEmpty();
    }

    @Test
    void secretTextPlain() {
        SecretText text = SecretText.plain("plain value");
        assertThat(text.expose()).isEqualTo("plain value");
        assertThat(text.sensitive()).isFalse();
        assertThat(text.toString()).isEqualTo("plain value");

        SecretText nullText = SecretText.plain(null);
        assertThat(nullText.expose()).isNull();
        assertThat(nullText.sensitive()).isFalse();
        assertThat(nullText.toString()).isNull();
    }

    @Test
    void secretTextOf() {
        SecretText text = SecretText.of("supersecret", "{{cred.login.password}}");
        assertThat(text.expose()).isEqualTo("supersecret");
        assertThat(text.sensitive()).isTrue();
        assertThat(text.toString()).isEqualTo("{{cred.login.password}}");
        assertThat(text.toString()).doesNotContain("supersecret");
    }

    @Test
    void secretTextEqualsAndHashCodeComparePlaintextOnly() {
        SecretText st1 = SecretText.of("secret", "{{cred.login.password}}");
        SecretText st2 = SecretText.plain("secret");
        SecretText st3 = SecretText.of("different", "{{cred.login.password}}");

        assertThat(st1).isEqualTo(st2);
        assertThat(st1.hashCode()).isEqualTo(st2.hashCode());
        assertThat(st1).isNotEqualTo(st3);
    }
}
