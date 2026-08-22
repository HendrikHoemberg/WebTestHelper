package dev.hendrikhoemberg.webtesthelper.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real PostgreSQL for every persistence test (spec 15). An in-memory substitute would
 * validate against a dialect production never uses, which is how jsonb and constraint
 * behaviour diverge silently.
 *
 * <p>The container is a JVM-wide singleton, started once and reused by every subclass. This
 * keeps it in step with Spring's shared application-context cache: Spring Boot binds the
 * test {@code DataSource} to the {@link ServiceConnection} present when the context is first
 * created, so a per-class container (fresh port, stopped in {@code @AfterAll}) would leave
 * later classes pointing at a dead database.
 *
 * <p>Because the container (and the Spring context) is shared across the whole suite, test
 * classes must not assume a clean database. {@code @Transactional} test classes get automatic
 * rollback; any non-{@code @Transactional} subclass must clear its own tables — in
 * {@code @BeforeEach}, or once in {@code @BeforeAll} for a
 * {@code @TestInstance(PER_CLASS)} class whose tests all read one expensive fixture.
 *
 * <p>The {@code @BeforeAll} variant rests on surefire running test classes sequentially in a
 * single JVM, so no other class interleaves and sees the shared rows. Enabling JUnit parallel
 * execution would break it.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }
}
