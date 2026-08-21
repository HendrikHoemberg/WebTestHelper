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
        assertThat(settings).isZero();
    }
}
