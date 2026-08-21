package dev.hendrikhoemberg.webtesthelper.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real PostgreSQL for every persistence test (spec 15). An in-memory substitute would
 * validate against a dialect production never uses, which is how jsonb and constraint
 * behaviour diverge silently.
 *
 * <p>The container is a JVM-wide singleton, started once and reused by every subclass. This
 * keeps it in step with Spring's shared application-context cache: Spring Boot binds the
 * test {@link DataSource} to the {@link ServiceConnection} present when the context is first
 * created, so a per-class container (fresh port, stopped in {@code @AfterAll}) would leave
 * later classes pointing at a dead database.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }
}
