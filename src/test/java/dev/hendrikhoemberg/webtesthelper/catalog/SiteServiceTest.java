package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.FormTestMode;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class SiteServiceTest extends AbstractPostgresTest {

    @Autowired
    SiteService sites;

    private static SiteForm form() {
        return new SiteForm("Kunde Müller", "https://www.kunde-mueller.de/", 300, 5,
                Duration.ofMinutes(30), List.of(), List.of("/intern/*"), true, null, true);
    }

    @Test
    void createdSiteExposesANormalisedBaseUrlAndItsBudget() {
        long id = sites.create(form());

        SiteContext context = sites.contextFor(id);
        assertThat(context.baseUrl().value()).isEqualTo("https://www.kunde-mueller.de/");
        assertThat(context.budget()).isEqualTo(new CrawlBudget(300, 5, Duration.ofMinutes(30)));
        assertThat(context.excludePatterns()).containsExactly("/intern/*");
        assertThat(context.respectRobots()).isTrue();
    }

    @Test
    void everyCheckGetsADefaultSettingAndTheTwoNoisyOnesAreOff() {
        long id = sites.create(form());

        SiteContext context = sites.contextFor(id);
        assertThat(context.checkSettings()).containsOnlyKeys(CheckType.values());
        assertThat(context.enabled(CheckType.DEAD_LINK)).isTrue();
        assertThat(context.enabled(CheckType.CONSOLE_ERRORS)).isFalse();
        assertThat(context.enabled(CheckType.SITEMAP_CONSISTENCY)).isFalse();
    }

    @Test
    void checkSettingsCanBeToggled() {
        long id = sites.create(form());

        sites.setCheckEnabled(id, CheckType.CONSOLE_ERRORS, true);

        assertThat(sites.contextFor(id).enabled(CheckType.CONSOLE_ERRORS)).isTrue();
    }

    @Test
    void aRejectedBaseUrlIsReportedAsAValidationFailure() {
        SiteForm bad = new SiteForm("Kaputt", "nicht-mal-eine-url", 300, 5,
                Duration.ofMinutes(30), List.of(), List.of(), true, null, true);

        assertThatThrownBy(() -> sites.create(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nicht-mal-eine-url");
    }

    @Test
    void siteCreatedWithExplicitFormTestModeIsExposedInContext() {
        SiteForm custom = new SiteForm("Kunde Mail", "https://www.kunde-mail.de/", 300, 5,
                Duration.ofMinutes(30), List.of(), List.of(), true, null, true, List.of(),
                FormTestMode.SUBMIT_AND_VERIFY_MAIL);
        long id = sites.create(custom);

        SiteContext context = sites.contextFor(id);
        assertThat(context.formTestMode()).isEqualTo(FormTestMode.SUBMIT_AND_VERIFY_MAIL);
    }

    @Test
    void siteCreatedWithOldConstructorArityDefaultsToNoSubmit() {
        long id = sites.create(form());

        SiteContext context = sites.contextFor(id);
        assertThat(context.formTestMode()).isEqualTo(FormTestMode.NO_SUBMIT);
    }
}

