package dev.hendrikhoemberg.webtesthelper.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real PostgreSQL for every persistence test (spec 15). An in-memory substitute would
 * validate against a dialect production never uses, which is how jsonb and constraint
 * behaviour diverge silently.
 *
 * <p>The static container is started and stopped per test class (Testcontainers runs it in
 * beforeAll/afterAll), giving each test class a clean database.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
}
