package dev.hendrikhoemberg.webtesthelper;

import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationTest extends AbstractPostgresTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationsApplyAndHibernateValidatesAgainstThem() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(1);

        Integer settings = jdbc.queryForObject("SELECT count(*) FROM app_setting", Integer.class);
        assertThat(settings).isGreaterThanOrEqualTo(0);

        Integer notifications = jdbc.queryForObject("SELECT count(*) FROM notification", Integer.class);
        assertThat(notifications).isGreaterThanOrEqualTo(0);

        Integer recipients = jdbc.queryForObject("SELECT count(*) FROM notification_recipient", Integer.class);
        assertThat(recipients).isGreaterThanOrEqualTo(0);
    }
}
