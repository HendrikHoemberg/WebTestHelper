package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class RecipientServiceTest extends AbstractPostgresTest {

    @Autowired
    RecipientService recipientService;

    @Autowired
    SiteService siteService;

    @Autowired
    SiteRepository siteRepository;

    @Autowired
    AppSettings appSettings;

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
    void siteWithTwoRecipientsReturnsBothOrderedByEmail() {
        long id2 = recipientService.add(siteA, "zulu@example.com");
        long id1 = recipientService.add(siteA, "alpha@example.com");

        List<Recipient> list = recipientService.list(siteA);
        assertThat(list).hasSize(2);
        assertThat(list.get(0)).isEqualTo(new Recipient(id1, siteA, "alpha@example.com"));
        assertThat(list.get(1)).isEqualTo(new Recipient(id2, siteA, "zulu@example.com"));
    }

    @Test
    void addStripsAndLowercasesEmail() {
        long id = recipientService.add(siteA, "  Foo.Bar@Example.COM  ");

        List<Recipient> list = recipientService.list(siteA);
        assertThat(list).containsExactly(new Recipient(id, siteA, "foo.bar@example.com"));
    }

    @Test
    void addOfSameAddressInDifferentCaseIsRejected() {
        recipientService.add(siteA, "alert@example.com");

        assertThatThrownBy(() -> recipientService.add(siteA, "ALERT@EXAMPLE.COM"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addOfMalformedAddressIsRejected() {
        assertThatThrownBy(() -> recipientService.add(siteA, "nicht-mal-eine-adresse"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> recipientService.add(siteA, ""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> recipientService.add(siteA, "   "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> recipientService.add(siteA, "foo@bar"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> recipientService.add(siteA, "foo bar@example.com"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> recipientService.add(siteA, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeWithAnotherSitesRecipientIdChangesNothing() {
        long recipientA = recipientService.add(siteA, "dev@a.test");

        recipientService.remove(siteB, recipientA);

        assertThat(recipientService.list(siteA)).hasSize(1);
    }

    @Test
    void removeWithOwnRecipientIdRemovesIt() {
        long recipientA = recipientService.add(siteA, "dev@a.test");

        recipientService.remove(siteA, recipientA);

        assertThat(recipientService.list(siteA)).isEmpty();
    }

    @Test
    void deletingSiteCascadesAndRemovesItsRows() {
        recipientService.add(siteA, "dev@a.test");
        recipientService.add(siteA, "team@a.test");

        siteService.delete(siteA);
        entityManager.flush();
        entityManager.clear();

        assertThat(recipientService.list(siteA)).isEmpty();
    }

    @Test
    void effectiveForWithRecipientsFallbackAndEmpty() {
        recipientService.add(siteA, "alpha@a.test");
        recipientService.add(siteA, "beta@a.test");

        appSettings.saveFallbackRecipients("fallback-1@example.com, fallback-2@example.com");

        long siteC = siteService.create(new SiteForm("Site C", "https://c.test/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));

        Map<Long, List<String>> effective = recipientService.effectiveFor(List.of(siteA, siteB, siteC));

        assertThat(effective.get(siteA)).containsExactly("alpha@a.test", "beta@a.test");
        assertThat(effective.get(siteB)).containsExactly("fallback-1@example.com", "fallback-2@example.com");
        assertThat(effective.get(siteC)).containsExactly("fallback-1@example.com", "fallback-2@example.com");
    }

    @Test
    void effectiveForReturnsEmptyListWhenNoRecipientsAndNoFallback() {
        appSettings.saveFallbackRecipients("");

        Map<Long, List<String>> effective = recipientService.effectiveFor(List.of(siteA));

        assertThat(effective.get(siteA)).isEmpty();
    }

    @Test
    void effectiveForWithEmptyCollectionReturnsEmptyMap() {
        assertThat(recipientService.effectiveFor(Set.of())).isEmpty();
    }
}
