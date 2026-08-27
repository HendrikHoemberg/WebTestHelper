package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class SiteCheckDefaultsTest extends AbstractPostgresTest {

    @Autowired
    SiteService sites;
    @Autowired
    JdbcTemplate jdbc;

    private static SiteForm form() {
        return new SiteForm("Defaults Test Site", "https://defaults.example.com/", 300, 5,
                Duration.ofMinutes(30), List.of(), List.of(), true, null, true);
    }

    @Test
    void absentSettingResolvesToTheTypeDefault() {
        long id = sites.create(form());
        jdbc.update("DELETE FROM site_check_setting WHERE site_id = ? AND check_type IN (?, ?)",
                id, CheckType.DEAD_LINK.name(), CheckType.CONSOLE_ERRORS.name());

        SiteContext context = sites.contextFor(id);
        assertThat(context.checkSettings()).containsOnlyKeys(CheckType.values());
        assertThat(context.enabled(CheckType.DEAD_LINK)).isTrue();
        assertThat(context.enabled(CheckType.CONSOLE_ERRORS)).isFalse();
    }

    @Test
    void explicitlyDisabledSettingReadsFalse() {
        long id = sites.create(form());
        sites.setCheckEnabled(id, CheckType.DEAD_LINK, false);

        SiteContext context = sites.contextFor(id);
        assertThat(context.enabled(CheckType.DEAD_LINK)).isFalse();
    }

    @Test
    void newlyCreatedSiteHasButtonReachabilityDisabledAndLanguageSwitcherEnabled() {
        long id = sites.create(form());

        SiteContext context = sites.contextFor(id);
        assertThat(context.enabled(CheckType.BUTTON_REACHABILITY)).isFalse();
        assertThat(context.enabled(CheckType.LANGUAGE_SWITCHER)).isTrue();
    }
}
