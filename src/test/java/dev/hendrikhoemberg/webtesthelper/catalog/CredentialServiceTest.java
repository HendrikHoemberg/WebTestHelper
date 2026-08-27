package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class CredentialServiceTest extends AbstractPostgresTest {

    @Autowired
    CredentialService credentialService;

    @Autowired
    SiteService siteService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    SecretBox secretBox;

    @Autowired
    jakarta.persistence.EntityManager entityManager;

    private long siteA;
    private long siteB;

    @BeforeEach
    void setUp() {
        siteA = siteService.create(new SiteForm("Site A", "https://a.test/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        siteB = siteService.create(new SiteForm("Site B", "https://b.test/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
    }

    @Test
    void createdCredentialAppearsInListWithReadableTrue() {
        long id = credentialService.create(siteA, "login", "admin", "secret123");

        List<Credential> list = credentialService.list(siteA);
        assertThat(list).hasSize(1);
        Credential cred = list.get(0);
        assertThat(cred.id()).isEqualTo(id);
        assertThat(cred.siteId()).isEqualTo(siteA);
        assertThat(cred.name()).isEqualTo("login");
        assertThat(cred.username()).isEqualTo("admin");
        assertThat(cred.updatedAt()).isNotNull();
        assertThat(cred.readable()).isTrue();
    }

    @Test
    void corruptedSecretYieldsReadableFalse() {
        long id = credentialService.create(siteA, "login", "admin", "secret123");
        jdbcTemplate.update("UPDATE credential SET secret = 'corrupted-ciphertext' WHERE id = ?", id);
        entityManager.flush();
        entityManager.clear();

        List<Credential> list = credentialService.list(siteA);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).readable()).isFalse();
    }

    @Test
    void secretColumnIsNotPlaintextAndDecryptsToOriginal() {
        long id = credentialService.create(siteA, "db_pass", "user", "supersecretpassword");

        String storedSecret = jdbcTemplate.queryForObject(
                "SELECT secret FROM credential WHERE id = ?", String.class, id);

        assertThat(storedSecret).isNotNull();
        assertThat(storedSecret).isNotEqualTo("supersecretpassword");
        assertThat(storedSecret).doesNotContain("supersecretpassword");
        assertThat(secretBox.decrypt(storedSecret)).isEqualTo("supersecretpassword");
    }

    @Test
    void creatingSamePasswordTwiceYieldsDifferentCiphertexts() {
        long id1 = credentialService.create(siteA, "cred1", "user1", "identicalpassword");
        long id2 = credentialService.create(siteA, "cred2", "user2", "identicalpassword");

        String secret1 = jdbcTemplate.queryForObject(
                "SELECT secret FROM credential WHERE id = ?", String.class, id1);
        String secret2 = jdbcTemplate.queryForObject(
                "SELECT secret FROM credential WHERE id = ?", String.class, id2);

        assertThat(secret1).isNotEqualTo(secret2);
    }

    @Test
    void sameNameOnTwoSitesAcceptedDuplicateOnSameSiteThrows() {
        credentialService.create(siteA, "staging", "userA", "passA");
        long idB = credentialService.create(siteB, "staging", "userB", "passB");

        assertThat(idB).isPositive();

        assertThatThrownBy(() -> credentialService.create(siteA, "staging", "userA2", "passA2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credential.name.duplicate");
    }

    @Test
    void invalidNamesRejected() {
        assertThatThrownBy(() -> credentialService.create(siteA, "Login", "user", "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credential.name.invalid");

        assertThatThrownBy(() -> credentialService.create(siteA, "mein.name", "user", "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credential.name.invalid");

        assertThatThrownBy(() -> credentialService.create(siteA, "", "user", "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credential.name.invalid");

        assertThatThrownBy(() -> credentialService.create(siteA, null, "user", "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credential.name.invalid");
    }

    @Test
    void blankPasswordOnCreateThrows() {
        assertThatThrownBy(() -> credentialService.create(siteA, "test_cred", "user", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credential.password.blank");

        assertThatThrownBy(() -> credentialService.create(siteA, "test_cred", "user", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credential.password.blank");

        assertThatThrownBy(() -> credentialService.create(siteA, "test_cred", "user", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credential.password.blank");
    }

    @Test
    void unknownSiteOnCreateThrows() {
        assertThatThrownBy(() -> credentialService.create(999999L, "test_cred", "user", "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Site existiert nicht: 999999");
    }

    @Test
    void updateWithBlankPasswordPreservesSecretAndChangesUsername() {
        long id = credentialService.create(siteA, "login", "user1", "originalpass");

        credentialService.update(siteA, id, "user2", "");
        entityManager.flush();
        String secretAfterBlank = jdbcTemplate.queryForObject(
                "SELECT secret FROM credential WHERE id = ?", String.class, id);
        assertThat(secretBox.decrypt(secretAfterBlank)).isEqualTo("originalpass");

        List<Credential> list = credentialService.list(siteA);
        assertThat(list.get(0).username()).isEqualTo("user2");

        credentialService.update(siteA, id, "user3", null);
        entityManager.flush();
        String secretAfterNull = jdbcTemplate.queryForObject(
                "SELECT secret FROM credential WHERE id = ?", String.class, id);
        assertThat(secretBox.decrypt(secretAfterNull)).isEqualTo("originalpass");
        assertThat(credentialService.list(siteA).get(0).username()).isEqualTo("user3");
    }

    @Test
    void updateWithNewPasswordChangesSecret() {
        long id = credentialService.create(siteA, "login", "user1", "initialpass");

        credentialService.update(siteA, id, "user1_new", "newpass");
        entityManager.flush();

        String updatedSecret = jdbcTemplate.queryForObject(
                "SELECT secret FROM credential WHERE id = ?", String.class, id);
        assertThat(secretBox.decrypt(updatedSecret)).isEqualTo("newpass");
        assertThat(credentialService.list(siteA).get(0).username()).isEqualTo("user1_new");
    }

    @Test
    void updateAndDeleteWithOtherSiteIdChangeNothing() {
        long idA = credentialService.create(siteA, "login_a", "user_a", "pass_a");

        credentialService.update(siteB, idA, "attacker", "hacked");
        entityManager.flush();
        String secret = jdbcTemplate.queryForObject(
                "SELECT secret FROM credential WHERE id = ?", String.class, idA);
        assertThat(secretBox.decrypt(secret)).isEqualTo("pass_a");
        assertThat(credentialService.list(siteA).get(0).username()).isEqualTo("user_a");

        credentialService.delete(siteB, idA);
        entityManager.flush();
        assertThat(credentialService.list(siteA)).hasSize(1);

        credentialService.delete(siteA, idA);
        entityManager.flush();
        assertThat(credentialService.list(siteA)).isEmpty();
    }

    @Test
    void deletingSiteCascadesAndRemovesCredentialRows() {
        credentialService.create(siteA, "cred1", "u1", "p1");
        credentialService.create(siteA, "cred2", "u2", "p2");

        siteService.delete(siteA);
        entityManager.flush();
        entityManager.clear();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM credential WHERE site_id = ?", Integer.class, siteA);
        assertThat(count).isEqualTo(0);
    }

    @Test
    void credentialRecordHasNoSecretComponent() {
        List<String> componentNames = Arrays.stream(Credential.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames)
                .containsExactly("id", "siteId", "name", "username", "updatedAt", "readable");
    }
}
