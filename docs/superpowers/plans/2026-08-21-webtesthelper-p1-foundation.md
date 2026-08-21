# WebTestHelper Plan 1 — Foundation and Run Lifecycle

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the bare Spring Boot skeleton into the application's foundation: PostgreSQL with Flyway-owned schema, Spring Modulith skeleton with enforced boundaries, the shared `model` value types, the site catalog, the pure `UrlNormalizer`, and a database-leased run queue with a working claim/heartbeat/complete loop.

**Architecture:** Modular monolith — each direct sub-package of `dev.hendrikhoemberg.webtesthelper` is a Modulith application module with declared allowed dependencies. A `Run` is a row leased by a worker via `SELECT … FOR UPDATE SKIP LOCKED`; one run at a time per site is enforced by a partial unique index. Value types are Java records in the dependency-free `model` package; Lombok is for JPA entities only.

**Tech Stack:** Java 25, Spring Boot 4.1.1, PostgreSQL 17 (Testcontainers in tests), Flyway, Spring Data JPA + `JdbcTemplate`, Spring Modulith.

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md` — read it alongside this plan. Section references like (§6.4) point there.
**Roadmap:** `docs/superpowers/plans/2026-08-21-webtesthelper-phase-1-roadmap.md` — this is plan 1 of 5.

---

## Deviations applying to this plan

- **D1** — A tenth package, `model`, holds shared value types (see Task 1). `checks` and `findings` will depend on it and nothing else.
- **D4** — `UrlNormalizer` lowercases scheme and host only; path and query case are preserved (Task 2).

## Global Constraints

Every task's requirements implicitly include this section.

- **Java 25**, **Spring Boot 4.1.1** (`spring-boot-starter-parent`). Boot 4 starter names:
  `spring-boot-starter-webmvc` (not `-web`), `spring-boot-starter-data-jpa-test`,
  `spring-boot-starter-webmvc-test`, `spring-boot-starter-thymeleaf-test`.
- **`spring.jpa.hibernate.ddl-auto=validate` in every environment, tests included.**
  Flyway owns the schema; a mismatch must fail startup. (§6.5)
- **Repository tests run against real PostgreSQL** via Testcontainers `@ServiceConnection`.
  No H2, no in-memory substitute, ever. (§15)
- **No cross-module JPA associations.** A `Run` row references its site as a plain
  `Long siteId`, not `@ManyToOne SiteEntity`. Foreign keys are still declared in Flyway
  migrations.
- **Java `record`s for value types; Lombok only for JPA entities.** (§6.5)
- **`MuteRule`, `Schedule`, `Journey`, `Credential`, `NotificationRecipient`, the recorder,
  interaction checks and digest content are out of scope.** Do not create tables, entities
  or packages for them. `Run.trigger` stays an enum so `SCHEDULED` exists unused.
- **Commit after every task.** Conventional commit messages; code and commits are English,
  only user-facing strings are German.
- **Repositories that need SQL semantics JPA cannot express use `JdbcTemplate`.** In this
  plan that is the lease queue; later plans add the frontier and occurrence writer. (§6.5)

---

### Task 1: Build foundation — versions, dependencies, Postgres, Flyway, Modulith skeleton

**Files:**
- Modify: `pom.xml` (rewrite `<properties>` and `<dependencies>`)
- Modify: `src/main/resources/application.properties`
- Create: `src/main/resources/db/migration/V1__app_setting.sql`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/model/package-info.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/model/CheckType.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/model/Severity.java`
- Create: `compose.yaml`
- Create: `src/test/java/dev/hendrikhoemberg/webtesthelper/support/AbstractPostgresTest.java`
- Create: `src/test/java/dev/hendrikhoemberg/webtesthelper/ModularityTest.java`
- Create: `src/test/java/dev/hendrikhoemberg/webtesthelper/FlywayMigrationTest.java`
- Create: `src/test/resources/application.properties`
- Delete: `HELP.md`

**Interfaces:**
- Consumes: nothing.
- Produces: `AbstractPostgresTest` (base class every repository/integration test extends),
  `model.CheckType`, `model.Severity`, a running Flyway pipeline, Maven properties
  `playwright.version`, `spring-modulith.version`, `greenmail.version`.

- [ ] **Step 1: Resolve the dependency versions that Spring Boot does not manage**

Boot 4.1.1's BOM already manages Flyway, the PostgreSQL driver and Testcontainers. It does
**not** manage Playwright, Spring Modulith or GreenMail. Resolve them now; do not guess.

```bash
echo "playwright:"      && curl -s https://repo1.maven.org/maven2/com/microsoft/playwright/playwright/maven-metadata.xml            | grep -oP '(?<=<release>)[^<]+'
echo "spring-modulith:" && curl -s https://repo1.maven.org/maven2/org/springframework/modulith/spring-modulith-bom/maven-metadata.xml | grep -oP '(?<=<release>)[^<]+'
echo "greenmail:"       && curl -s https://repo1.maven.org/maven2/com/icegreen/greenmail-junit5/maven-metadata.xml                  | grep -oP '(?<=<release>)[^<]+'
```

At the time this plan was written (2026-08-21) that printed `1.62.0`, `2.1.0` and `2.1.12`.
Use whatever the command prints today. Sanity-check that the Modulith release compiles
against Boot 4.1.x:

```bash
curl -s https://repo1.maven.org/maven2/org/springframework/modulith/spring-modulith-core/<VERSION>/spring-modulith-core-<VERSION>.pom \
  | grep -A2 'spring-boot-autoconfigure'
```

If the printed Modulith release is a milestone/RC (`-M`, `-RC`), step back to the newest
stable release in the same listing. If its Boot line reads `4.0.x`, use it anyway and record
that in the commit message — `ModularityTest` proves it works.

- [ ] **Step 2: Rewrite `pom.xml`**

Remove `org.xerial:sqlite-jdbc` — the spec moved to PostgreSQL (§6.5). Replace the
`<properties>` and `<dependencies>` blocks with the following. Leave the existing
`<parent>`, coordinates and `<build>` block (the Lombok annotation-processor executions)
exactly as they are.

```xml
<properties>
    <java.version>25</java.version>
    <playwright.version>1.62.0</playwright.version>
    <spring-modulith.version>2.1.0</spring-modulith.version>
    <greenmail.version>2.1.12</greenmail.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.modulith</groupId>
            <artifactId>spring-modulith-bom</artifactId>
            <version>${spring-modulith.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- web + view -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-thymeleaf</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>

    <!-- persistence -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-flyway</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- mail, ops, modularity -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-mail</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-starter-core</artifactId>
    </dependency>

    <!-- browser -->
    <dependency>
        <groupId>com.microsoft.playwright</groupId>
        <artifactId>playwright</artifactId>
        <version>${playwright.version}</version>
    </dependency>

    <!-- dev -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-devtools</artifactId>
        <scope>runtime</scope>
        <optional>true</optional>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- test -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-thymeleaf-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-testcontainers</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-postgresql</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>com.icegreen</groupId>
        <artifactId>greenmail-junit5</artifactId>
        <version>${greenmail.version}</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

> Testcontainers 2.x renamed its modules: the artifacts are `testcontainers-postgresql`
> and `testcontainers-junit-jupiter`, and the container class is
> `org.testcontainers.postgresql.PostgreSQLContainer`. The 1.x names still resolve but are
> deprecated shims — do not use them.

- [ ] **Step 3: Verify the dependency tree resolves**

Run: `./mvnw -q dependency:tree -Dincludes=com.microsoft.playwright,org.springframework.modulith,org.testcontainers,com.icegreen`
Expected: all four groups listed, no `[ERROR] Failed to resolve`.

- [ ] **Step 4: Install the Chromium build that matches the pinned Playwright version**

```bash
./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes:$(cat target/cp.txt)" com.microsoft.playwright.CLI install --with-deps chromium
```

Expected: the CLI reports Chromium downloaded (or "is already installed"). If `--with-deps`
fails for lack of root, drop it and install the system libraries by hand — the browser
download is the part that matters. (The browser is not used until Plan 2, but installing it
now surfaces environment problems early.)

- [ ] **Step 5: Write `compose.yaml` for the development database**

```yaml
services:
  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: webtesthelper
      POSTGRES_USER: webtesthelper
      POSTGRES_PASSWORD: webtesthelper
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U webtesthelper"]
      interval: 5s
      timeout: 3s
      retries: 10

volumes:
  pgdata:
```

- [ ] **Step 6: Write the application configuration**

`src/main/resources/application.properties`:

```properties
spring.application.name=webtesthelper

spring.datasource.url=${WTH_DB_URL:jdbc:postgresql://localhost:5432/webtesthelper}
spring.datasource.username=${WTH_DB_USER:webtesthelper}
spring.datasource.password=${WTH_DB_PASSWORD:webtesthelper}

# Flyway owns the schema. validate must never be relaxed (spec 6.5).
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration

spring.messages.basename=messages
spring.messages.encoding=UTF-8
spring.messages.fallback-to-system-locale=false
spring.web.locale=de
spring.web.locale-resolver=fixed

spring.threads.virtual.enabled=false

management.endpoints.web.exposure.include=health,info,metrics
management.endpoint.health.show-details=when-authorized

webtesthelper.data-dir=${WTH_DATA_DIR:./data}
webtesthelper.browser-workers=${WTH_BROWSER_WORKERS:4}
```

> `spring.threads.virtual.enabled` stays **false**. Browser workers must be platform
> threads (§5.4); asset checking opts into virtual threads explicitly with its own
> executor rather than by flipping the servlet container onto them.

`src/test/resources/application.properties`:

```properties
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
spring.main.banner-mode=off
webtesthelper.data-dir=${java.io.tmpdir}/webtesthelper-test
logging.level.org.testcontainers=WARN
logging.level.com.github.dockerjava=WARN
```

- [ ] **Step 7: Write the first migration and the two shared enums**

`src/main/resources/db/migration/V1__app_setting.sql`:

```sql
-- Global key/value settings: SMTP, base URL, concurrency, redirect-all-mail (spec 11.4).
CREATE TABLE app_setting (
    setting_key TEXT PRIMARY KEY,
    setting_value TEXT,
    encrypted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`model/CheckType.java`:

```java
package dev.hendrikhoemberg.webtesthelper.model;

/** Every check the system can run. Persisted by name; never rendered to a user (spec 13.1). */
public enum CheckType {
    PAGE_STATUS,
    PAGE_UNREACHABLE,
    DEAD_LINK,
    REDIRECT_CHAIN,
    IMAGE_BROKEN,
    FILE_DOWNLOAD,
    MEDIA_PLAYABLE,
    IFRAME_EMBED,
    MIXED_CONTENT,
    CONSOLE_ERRORS,
    TLS_CERT,
    HREFLANG,
    SITEMAP_CONSISTENCY
}
```

`model/Severity.java`:

```java
package dev.hendrikhoemberg.webtesthelper.model;

/** Only ERROR triggers notification by default (spec 8). */
public enum Severity {
    ERROR, WARN, INFO;

    /** Highest severity of two, used when occurrences of one subject disagree. */
    public Severity max(Severity other) {
        return this.ordinal() <= other.ordinal() ? this : other;
    }
}
```

`model/package-info.java`:

```java
/**
 * Shared value types. This module depends on nothing — it is what lets {@code checks} and
 * {@code findings} depend only on value types (spec 5.1, plan deviation D1).
 *
 * <p>Deliberately flat: Spring Modulith treats a module's sub-packages as internal, so a
 * type in {@code model.url} would be invisible to {@code crawler}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Value types",
        allowedDependencies = {})
package dev.hendrikhoemberg.webtesthelper.model;
```

- [ ] **Step 8: Write the three foundation tests**

`src/test/java/.../support/AbstractPostgresTest.java` — every repository and integration
test extends this. One container is shared across the whole suite because the field is
`static` and Testcontainers reuses it.

```java
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
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
}
```

`src/test/java/.../FlywayMigrationTest.java`:

```java
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
```

> This test earns its keep on every later task: because `ddl-auto=validate` is on, the
> Spring context only starts if every JPA entity matches the migrated schema. A column an
> entity declares but no migration creates fails here, loudly, at build time.

`src/test/java/.../ModularityTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(WebtesthelperApplication.class);

    @Test
    void modulesRespectTheirDeclaredDependencies() {
        MODULES.verify();
    }

    @Test
    void moduleStructureIsPrintable() {
        MODULES.forEach(module -> System.out.println(module.getDisplayName()));
    }
}
```

- [ ] **Step 9: Run the tests**

Run: `docker compose up -d postgres && ./mvnw test`
Expected: PASS. `WebtesthelperApplicationTests.contextLoads` also passes now that a
database is reachable — if it fails with "Failed to determine a suitable driver class",
the Postgres dependency is missing or `sqlite-jdbc` was not removed.

- [ ] **Step 10: Commit**

```bash
git rm -f HELP.md
git add pom.xml compose.yaml src/main/resources src/main/java src/test
git commit -m "build: move to postgres, flyway, playwright and spring modulith

Removes sqlite-jdbc; the design moved to PostgreSQL with Flyway-managed
migrations and ddl-auto=validate everywhere including tests."
```

---

### Task 2: `UrlNormalizer` — the pure normalisation core

Everything downstream keys off this class. §6.2: *"Without this normalisation the same dead
link fingerprints differently when found on two pages and the diff is worthless."* It is
also the crawl frontier's dedupe key and the external URL cache's primary key, so a bug
here shows up as duplicate crawling, duplicate findings, or findings that flicker between
runs. It has no dependencies and no I/O — it is worth over-testing.

**Files:**
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/model/NormalizedUrl.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/model/UrlNormalizer.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/model/UrlNormalizerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `NormalizedUrl(String scheme, String host, int port, String path, String query)` with
    `value()`, `origin()`, `registrableHost()`, `sameSiteAs(NormalizedUrl)`,
    `hasDefaultPort()`, static `defaultPort(String scheme)`.
  - `UrlNormalizer.normalize(String) -> Optional<NormalizedUrl>`
  - `UrlNormalizer.resolve(String base, String href) -> Optional<NormalizedUrl>`
  - `UrlNormalizer.key(String) -> Optional<String>` (normalised string form, the
    `subjectKey` and cache key)
  - `UrlNormalizer.locationKeyOf(String) -> String` (path + normalised query, the
    `locationKey` of §6.2)
  - `UrlNormalizer.isSameSite(NormalizedUrl, NormalizedUrl) -> boolean`

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/hendrikhoemberg/webtesthelper/model/UrlNormalizerTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UrlNormalizerTest {

    private static String norm(String raw) {
        return UrlNormalizer.key(raw).orElseThrow();
    }

    @Nested
    class Normalisation {

        @Test
        void dropsTheFragment() {
            assertThat(norm("https://example.com/a#kontakt")).isEqualTo("https://example.com/a");
        }

        @Test
        void sortsQueryParametersByNameThenValue() {
            assertThat(norm("https://example.com/s?b=2&a=1&a=0"))
                    .isEqualTo("https://example.com/s?a=0&a=1&b=2");
        }

        @Test
        void stripsTrackingParametersButKeepsRealOnes() {
            assertThat(norm("https://example.com/s?utm_source=news&page=2&fbclid=xyz&utm_medium=mail"))
                    .isEqualTo("https://example.com/s?page=2");
        }

        @Test
        void dropsTheQueryEntirelyWhenOnlyTrackingParametersWerePresent() {
            assertThat(norm("https://example.com/s?utm_source=news")).isEqualTo("https://example.com/s");
        }

        @Test
        void dropsTheDefaultPortAndKeepsAnyOther() {
            assertThat(norm("https://example.com:443/a")).isEqualTo("https://example.com/a");
            assertThat(norm("http://example.com:80/a")).isEqualTo("http://example.com/a");
            assertThat(norm("https://example.com:8443/a")).isEqualTo("https://example.com:8443/a");
        }

        @Test
        void lowercasesSchemeAndHostButNeverThePath() {
            // Deviation D4: lowercasing the path would merge distinct resources on a
            // case-sensitive server and corrupt both the frontier and the URL cache.
            assertThat(norm("HTTPS://Example.COM/Leistungen/PDF")).isEqualTo("https://example.com/Leistungen/PDF");
        }

        @Test
        void stripsTheTrailingSlashExceptAtTheRoot() {
            assertThat(norm("https://example.com/leistungen/")).isEqualTo("https://example.com/leistungen");
            assertThat(norm("https://example.com/")).isEqualTo("https://example.com/");
            assertThat(norm("https://example.com")).isEqualTo("https://example.com/");
        }

        @Test
        void removesDotSegmentsAndCollapsesDuplicateSlashes() {
            assertThat(norm("https://example.com/a/b/../c")).isEqualTo("https://example.com/a/c");
            assertThat(norm("https://example.com/./a")).isEqualTo("https://example.com/a");
            assertThat(norm("https://example.com//a//b")).isEqualTo("https://example.com/a/b");
            assertThat(norm("https://example.com/../..")).isEqualTo("https://example.com/");
        }

        @Test
        void convertsInternationalisedHostsToPunycodeAndDropsTheRootLabel() {
            assertThat(norm("https://müller-bau.de/kontakt")).isEqualTo("https://xn--mller-bau-q9a.de/kontakt");
            assertThat(norm("https://example.com./a")).isEqualTo("https://example.com/a");
        }

        @Test
        void uppercasesPercentEscapesAndDecodesUnreservedOnes() {
            assertThat(norm("https://example.com/a%2fb%7ec%41d")).isEqualTo("https://example.com/a%2Fb~cAd");
        }

        @Test
        void encodesCharactersRealMarkupContainsAndRfc3986Forbids() {
            assertThat(norm("https://example.com/mein dokument.pdf"))
                    .isEqualTo("https://example.com/mein%20dokument.pdf");
            assertThat(norm("https://example.com/über-uns"))
                    .isEqualTo("https://example.com/%C3%BCber-uns");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "mailto:info@example.com", "tel:+4930123456", "javascript:void(0)",
                "data:image/png;base64,iVBOR", "ftp://example.com/x", "#top", "", "   "})
        void rejectsEverythingThatIsNotAnHttpUrl(String raw) {
            assertThat(UrlNormalizer.key(raw)).isEmpty();
        }

        @Test
        void rejectsNull() {
            assertThat(UrlNormalizer.key(null)).isEmpty();
        }

        @Test
        void theSameDeadLinkWrittenTwoWaysNormalisesIdentically() {
            // The motivating case from spec 6.2.
            String fromFooter = norm("HTTP://Partner.example.com:80/angebot/?utm_source=footer&id=7#top");
            String fromBody   = norm("http://partner.example.com/angebot?id=7&utm_campaign=body");
            assertThat(fromFooter).isEqualTo(fromBody);
        }
    }

    @Nested
    class Resolution {

        private static final String BASE = "https://example.com/leistungen/beratung";

        @Test
        void resolvesRelativeReferences() {
            assertThat(UrlNormalizer.resolve(BASE, "../kontakt").orElseThrow().value())
                    .isEqualTo("https://example.com/kontakt");
            assertThat(UrlNormalizer.resolve(BASE, "preise").orElseThrow().value())
                    .isEqualTo("https://example.com/leistungen/preise");
            assertThat(UrlNormalizer.resolve(BASE, "/impressum").orElseThrow().value())
                    .isEqualTo("https://example.com/impressum");
        }

        @Test
        void resolvesProtocolRelativeReferencesAgainstTheBaseScheme() {
            assertThat(UrlNormalizer.resolve(BASE, "//cdn.example.net/logo.png").orElseThrow().value())
                    .isEqualTo("https://cdn.example.net/logo.png");
        }

        @Test
        void passesAbsoluteReferencesThrough() {
            assertThat(UrlNormalizer.resolve(BASE, "https://andere.de/x").orElseThrow().value())
                    .isEqualTo("https://andere.de/x");
        }

        @Test
        void rejectsSamePageAnchorsAndNonWebSchemes() {
            assertThat(UrlNormalizer.resolve(BASE, "#weiter")).isEmpty();
            assertThat(UrlNormalizer.resolve(BASE, "mailto:info@example.com")).isEmpty();
        }

        @Test
        void toleratesWhitespaceAndNewlinesInsideMarkupHrefs() {
            assertThat(UrlNormalizer.resolve(BASE, "  /kon\ntakt  ").orElseThrow().value())
                    .isEqualTo("https://example.com/kontakt");
        }
    }

    @Nested
    class Keys {

        @Test
        void locationKeyIsThePathAndSurvivingQuery() {
            assertThat(UrlNormalizer.locationKeyOf("https://example.com/aktuelles?page=2&utm_source=x"))
                    .isEqualTo("/aktuelles?page=2");
            assertThat(UrlNormalizer.locationKeyOf("https://example.com/")).isEqualTo("/");
        }

        @Test
        void locationKeyOfSomethingUnparseableIsTheInputItself() {
            assertThat(UrlNormalizer.locationKeyOf("nonsense")).isEqualTo("nonsense");
        }

        @Test
        void sameSiteComparisonIgnoresALeadingWww() {
            NormalizedUrl apex = UrlNormalizer.normalize("https://example.com/a").orElseThrow();
            NormalizedUrl www = UrlNormalizer.normalize("https://www.example.com/b").orElseThrow();
            NormalizedUrl other = UrlNormalizer.normalize("https://andere.de/c").orElseThrow();

            assertThat(UrlNormalizer.isSameSite(apex, www)).isTrue();
            assertThat(UrlNormalizer.isSameSite(apex, other)).isFalse();
        }

        @Test
        void normalizedUrlExposesItsParts() {
            NormalizedUrl url = UrlNormalizer.normalize("https://example.com:8443/a?b=1").orElseThrow();
            assertThat(url.scheme()).isEqualTo("https");
            assertThat(url.host()).isEqualTo("example.com");
            assertThat(url.port()).isEqualTo(8443);
            assertThat(url.path()).isEqualTo("/a");
            assertThat(url.query()).isEqualTo("b=1");
            assertThat(url.hasDefaultPort()).isFalse();
            assertThat(url.origin()).isEqualTo("https://example.com:8443");
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=UrlNormalizerTest`
Expected: compilation failure — `NormalizedUrl` and `UrlNormalizer` do not exist.

- [ ] **Step 3: Write `NormalizedUrl`**

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Objects;

/**
 * A URL reduced to the canonical form used as the crawl frontier's dedupe key, the
 * external URL cache's primary key and a finding's {@code subjectKey} (spec 6.2).
 *
 * <p>The fragment is not represented: it is dropped during normalisation and never
 * recovered.
 */
public record NormalizedUrl(String scheme, String host, int port, String path, String query) {

    public NormalizedUrl {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(path, "path");
        if (query != null && query.isEmpty()) {
            query = null;
        }
    }

    public static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }

    public boolean hasDefaultPort() {
        return port == defaultPort(scheme);
    }

    /** The canonical string form. This is what gets stored and compared. */
    public String value() {
        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        if (!hasDefaultPort()) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (query != null) {
            sb.append('?').append(query);
        }
        return sb.toString();
    }

    public String origin() {
        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        if (!hasDefaultPort()) {
            sb.append(':').append(port);
        }
        return sb.toString();
    }

    /** Host with a leading {@code www.} removed, so the apex and the www host compare equal. */
    public String registrableHost() {
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    public boolean sameSiteAs(NormalizedUrl other) {
        return other != null && registrableHost().equals(other.registrableHost());
    }

    /** Path plus surviving query — a finding's {@code locationKey} (spec 6.2). */
    public String locationKey() {
        return query == null ? path : path + "?" + query;
    }

    public boolean isSecure() {
        return "https".equals(scheme);
    }

    @Override
    public String toString() {
        return value();
    }
}
```

- [ ] **Step 4: Write `UrlNormalizer`**

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Pure URL normalisation (spec 6.2). No I/O, no Spring, no logging — every method is a
 * total function returning {@link Optional#empty()} rather than throwing.
 *
 * <p>Normalisation, in order: reject non-http(s) schemes; lowercase scheme and host;
 * punycode the host and drop its root label; supply the default port and then omit it;
 * remove dot segments and collapse duplicate slashes; drop the trailing slash except at
 * the root; canonicalise percent escapes; strip tracking parameters; sort the remainder;
 * drop the fragment.
 *
 * <p>Path and query case is preserved. See plan deviation D4: lowercasing the whole URL,
 * which spec 6.2 says literally, would merge {@code /Kontakt} and {@code /kontakt} — two
 * different resources on a case-sensitive server.
 */
public final class UrlNormalizer {

    private static final Set<String> WEB_SCHEMES = Set.of("http", "https");

    /**
     * Campaign and click-tracking parameters. Deliberately excludes {@code ref} and
     * {@code source}: plenty of sites route real content off them, and merging two real
     * pages costs a missed crawl, which is worse than an extra one.
     */
    private static final Set<String> TRACKING_PARAMS = Set.of(
            "gclid", "gclsrc", "dclid", "fbclid", "msclkid", "yclid", "igshid", "twclid",
            "mc_cid", "mc_eid", "_ga", "_gl", "vero_id", "wickedid", "oly_enc_id", "oly_anon_id",
            "hsa_acc", "hsa_cam", "hsa_grp", "hsa_ad", "hsa_src", "hsa_tgt", "hsa_kw",
            "hsa_mt", "hsa_net", "hsa_ver", "_hsenc", "_hsmi",
            "pk_campaign", "pk_kwd", "pk_source", "pk_medium",
            "mtm_campaign", "mtm_keyword", "mtm_source", "mtm_medium");

    private static final Set<String> TRACKING_PREFIXES = Set.of("utm_", "matomo_", "piwik_");

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private UrlNormalizer() {
    }

    /** Normalises an absolute URL. Empty for anything that is not http(s). */
    public static Optional<NormalizedUrl> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = stripControlCharacters(raw);
        if (cleaned.isEmpty() || cleaned.startsWith("#")) {
            return Optional.empty();
        }
        URI uri = parse(cleaned);
        return (uri == null || !uri.isAbsolute()) ? Optional.empty() : fromUri(uri);
    }

    /** Resolves a possibly relative {@code href} against {@code base}, then normalises. */
    public static Optional<NormalizedUrl> resolve(String base, String href) {
        if (href == null || base == null) {
            return Optional.empty();
        }
        String cleaned = stripControlCharacters(href);
        if (cleaned.isEmpty() || cleaned.startsWith("#")) {
            return Optional.empty();
        }
        String scheme = schemeOf(cleaned);
        if (scheme != null && !WEB_SCHEMES.contains(scheme)) {
            return Optional.empty();   // mailto:, tel:, javascript:, data:, ftp: ...
        }
        URI baseUri = parse(stripControlCharacters(base));
        URI target = parse(cleaned);
        if (baseUri == null || target == null) {
            return Optional.empty();
        }
        try {
            return fromUri(baseUri.resolve(target));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The canonical string form, or empty. This is the cache key and the {@code subjectKey}. */
    public static Optional<String> key(String raw) {
        return normalize(raw).map(NormalizedUrl::value);
    }

    /** {@code locationKey} for a page URL; falls back to the raw input when unparseable. */
    public static String locationKeyOf(String pageUrl) {
        return normalize(pageUrl).map(NormalizedUrl::locationKey).orElse(pageUrl);
    }

    public static boolean isSameSite(NormalizedUrl a, NormalizedUrl b) {
        return a != null && a.sameSiteAs(b);
    }

    // ---------------------------------------------------------------- internals

    private record Authority(String host, int port) {
    }

    private record QueryParam(String name, String value, boolean hadEquals) {
    }

    private static Optional<NormalizedUrl> fromUri(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            return Optional.empty();
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!WEB_SCHEMES.contains(scheme)) {
            return Optional.empty();
        }
        Authority authority = authorityOf(uri, scheme);
        if (authority == null) {
            return Optional.empty();
        }
        return Optional.of(new NormalizedUrl(
                scheme,
                authority.host(),
                authority.port(),
                normalizePath(uri.getRawPath()),
                normalizeQuery(uri.getRawQuery())));
    }

    private static Authority authorityOf(URI uri, String scheme) {
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null) {
            // URI.getHost() returns null for hosts containing an underscore. Parse by hand.
            String raw = uri.getAuthority();
            if (raw == null) {
                return null;
            }
            int at = raw.lastIndexOf('@');
            if (at >= 0) {
                raw = raw.substring(at + 1);
            }
            int colon = raw.lastIndexOf(':');
            if (colon >= 0 && raw.indexOf(']') < colon) {
                try {
                    port = Integer.parseInt(raw.substring(colon + 1));
                } catch (NumberFormatException e) {
                    return null;
                }
                host = raw.substring(0, colon);
            } else {
                host = raw;
            }
        }
        host = canonicalHost(host);
        if (host == null || host.isEmpty()) {
            return null;
        }
        return new Authority(host, port >= 0 ? port : NormalizedUrl.defaultPort(scheme));
    }

    private static String canonicalHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);   // drop the DNS root label
        }
        if (host.isEmpty() || host.startsWith("[")) {
            return host;                                    // IPv6 literal, leave alone
        }
        try {
            return IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            return host;
        }
    }

    private static String normalizePath(String rawPath) {
        String path = (rawPath == null || rawPath.isEmpty()) ? "/" : rawPath;
        path = removeDotSegments(path);
        path = normalizePercentEncoding(path);
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? "/" : path;
    }

    /**
     * RFC 3986 section 5.2.4, plus collapsing of empty segments. {@code //a//b} becomes
     * {@code /a/b}: a doubled slash is an authoring slip that would otherwise cost a
     * duplicate crawl of the same page.
     */
    static String removeDotSegments(String path) {
        boolean trailingSlash = path.endsWith("/");
        Deque<String> out = new ArrayDeque<>();
        for (String segment : path.split("/", -1)) {
            switch (segment) {
                case "", "." -> { }
                case ".." -> out.pollLast();
                default -> out.addLast(segment);
            }
        }
        if (out.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        for (String segment : out) {
            sb.append('/').append(segment);
        }
        if (trailingSlash) {
            sb.append('/');
        }
        return sb.toString();
    }

    /** Uppercases the hex digits of percent escapes and decodes escapes of unreserved characters. */
    static String normalizePercentEncoding(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '%' || i + 2 >= value.length()) {
                sb.append(c);
                continue;
            }
            int hi = Character.digit(value.charAt(i + 1), 16);
            int lo = Character.digit(value.charAt(i + 2), 16);
            if (hi < 0 || lo < 0) {
                sb.append(c);
                continue;
            }
            int decoded = (hi << 4) | lo;
            if (isUnreserved(decoded)) {
                sb.append((char) decoded);
            } else {
                sb.append('%').append(HEX[hi]).append(HEX[lo]);
            }
            i += 2;
        }
        return sb.toString();
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }

    private static String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        List<QueryParam> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            if (isTracking(name)) {
                continue;
            }
            kept.add(new QueryParam(normalizePercentEncoding(name),
                    normalizePercentEncoding(value), eq >= 0));
        }
        if (kept.isEmpty()) {
            return null;
        }
        kept.sort(Comparator.comparing(QueryParam::name).thenComparing(QueryParam::value));
        StringBuilder sb = new StringBuilder();
        for (QueryParam param : kept) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(param.name());
            if (param.hadEquals()) {
                sb.append('=').append(param.value());
            }
        }
        return sb.toString();
    }

    private static boolean isTracking(String rawName) {
        String name = normalizePercentEncoding(rawName).toLowerCase(Locale.ROOT);
        if (TRACKING_PARAMS.contains(name)) {
            return true;
        }
        for (String prefix : TRACKING_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String schemeOf(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String candidate = value.substring(0, colon);
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean legal = (i == 0)
                    ? Character.isLetter(c)
                    : (Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.');
            if (!legal) {
                return null;   // e.g. "/pfad:mit-doppelpunkt" is a relative path, not a scheme
            }
        }
        return candidate.toLowerCase(Locale.ROOT);
    }

    /** WHATWG behaviour: tabs and newlines inside an href are removed, not encoded. */
    private static String stripControlCharacters(String raw) {
        String trimmed = raw.trim();
        if (trimmed.indexOf('\t') < 0 && trimmed.indexOf('\n') < 0 && trimmed.indexOf('\r') < 0) {
            return trimmed;
        }
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c != '\t' && c != '\n' && c != '\r') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException first) {
            try {
                return new URI(percentEncodeIllegal(value));
            } catch (URISyntaxException second) {
                return null;
            }
        }
    }

    /** Percent-encodes what real markup contains and RFC 3986 forbids: spaces, umlauts, quotes. */
    private static String percentEncodeIllegal(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        value.codePoints().forEach(cp -> {
            if (cp <= 0x20 || cp >= 0x7f || "\"<>\\^`{|}".indexOf(cp) >= 0) {
                byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
                }
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=UrlNormalizerTest`
Expected: PASS, all cases.

If `encodesCharactersRealMarkupContainsAndRfc3986Forbids` fails, check the order in
`normalizePath`: `removeDotSegments` runs on the *raw* path, `normalizePercentEncoding`
after it. Reversing them decodes `%2F` into a separator and changes the path's meaning.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/model src/test/java/dev/hendrikhoemberg/webtesthelper/model
git commit -m "feat(model): add UrlNormalizer and NormalizedUrl

Canonical form used as frontier dedupe key, external URL cache key and
finding subjectKey. Scheme and host are lowercased; path case is preserved
(see plan deviation D4)."
```

---

### Task 3: Site catalog

`Site` carries base URL, crawl budget, include/exclude patterns, robots policy, form-test
mode and User-Agent override (§6.1). `SiteCheckSetting` carries per-check enable/config/
severity override. The runner never sees these entities — it receives the immutable
`SiteContext` record.

**Files:**
- Create: `src/main/resources/db/migration/V2__site.sql`
- Create: `src/main/resources/db/migration/V3__site_check_setting.sql`
- Create: `src/main/java/.../model/CrawlBudget.java`
- Create: `src/main/java/.../model/CheckSetting.java`
- Create: `src/main/java/.../model/SiteContext.java`
- Create: `src/main/java/.../model/FormTestMode.java`
- Create: `src/main/java/.../catalog/package-info.java`
- Create: `src/main/java/.../catalog/SiteService.java`
- Create: `src/main/java/.../catalog/SiteSummary.java`
- Create: `src/main/java/.../catalog/SiteForm.java`
- Create: `src/main/java/.../catalog/persistence/SiteEntity.java`
- Create: `src/main/java/.../catalog/persistence/SiteRepository.java`
- Create: `src/main/java/.../catalog/persistence/SiteCheckSettingEntity.java`
- Create: `src/main/java/.../catalog/persistence/SiteCheckSettingRepository.java`
- Test: `src/test/java/.../catalog/SiteServiceTest.java`

**Interfaces:**
- Consumes: `model.NormalizedUrl`, `model.UrlNormalizer`, `model.CheckType`, `model.Severity`.
- Produces:
  - `SiteContext(long siteId, String name, NormalizedUrl baseUrl, CrawlBudget budget,
    List<String> includePatterns, List<String> excludePatterns, List<String> pinnedKeyPages,
    boolean respectRobots, String userAgent, Map<CheckType,CheckSetting> checkSettings)`
    with `enabled(CheckType)`, `settingsFor(CheckType)`, `severityFor(CheckType, Severity)`.
  - `CrawlBudget(int maxPages, int maxDepth, Duration maxDuration)` with `DEFAULT`.
  - `CheckSetting(boolean enabled, Severity severityOverride, Map<String,Object> config)`
    with factories `defaultEnabled()` / `defaultDisabled()` (the accessor `enabled()` is
    generated by the record — a static factory of the same name would not compile).
  - `SiteService.create(SiteForm) -> long`, `update(long, SiteForm)`, `delete(long)`,
    `contextFor(long) -> SiteContext`, `summaries() -> List<SiteSummary>`,
    `summary(long) -> SiteSummary`, `setCheckEnabled(long, CheckType, boolean)`.

- [ ] **Step 1: Write the failing test**

```java
package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
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
                Duration.ofMinutes(30), List.of(), List.of("/intern/*"), true, null);
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
                Duration.ofMinutes(30), List.of(), List.of(), true, null);

        assertThatThrownBy(() -> sites.create(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nicht-mal-eine-url");
    }
}
```

(Import `dev.hendrikhoemberg.webtesthelper.model.CrawlBudget`.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=SiteServiceTest`
Expected: compilation failure — `SiteService` does not exist.

- [ ] **Step 3: Write the migrations**

`V2__site.sql`:

```sql
CREATE TABLE site (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name TEXT NOT NULL,
    base_url TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    max_pages INTEGER NOT NULL DEFAULT 300,
    max_depth INTEGER NOT NULL DEFAULT 5,
    max_duration_seconds INTEGER NOT NULL DEFAULT 1800,
    include_patterns JSONB NOT NULL DEFAULT '[]'::jsonb,
    exclude_patterns JSONB NOT NULL DEFAULT '[]'::jsonb,
    pinned_key_pages JSONB NOT NULL DEFAULT '[]'::jsonb,
    respect_robots BOOLEAN NOT NULL DEFAULT TRUE,
    user_agent TEXT,
    form_test_mode TEXT NOT NULL DEFAULT 'NO_SUBMIT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_site_base_url ON site (base_url);
```

`V3__site_check_setting.sql`:

```sql
CREATE TABLE site_check_setting (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    site_id BIGINT NOT NULL REFERENCES site (id) ON DELETE CASCADE,
    check_type TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    severity_override TEXT,
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ux_site_check UNIQUE (site_id, check_type)
);
```

- [ ] **Step 4: Write the `model` records**

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.time.Duration;

/** Budget guards. Exceeding any of them ends a run cleanly with partial coverage (spec 14). */
public record CrawlBudget(int maxPages, int maxDepth, Duration maxDuration) {

    public static final CrawlBudget DEFAULT = new CrawlBudget(300, 5, Duration.ofMinutes(30));

    public CrawlBudget {
        if (maxPages < 1) throw new IllegalArgumentException("maxPages must be >= 1");
        if (maxDepth < 0) throw new IllegalArgumentException("maxDepth must be >= 0");
        if (maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("maxDuration must be positive");
        }
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Map;

/**
 * A site's configuration for one check. {@code severityOverride} is null when the check's
 * declared default severity applies (spec 8).
 */
public record CheckSetting(boolean enabled, Severity severityOverride, Map<String, Object> config) {

    public CheckSetting {
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public static CheckSetting defaultEnabled() {
        return new CheckSetting(true, null, Map.of());
    }

    public static CheckSetting defaultDisabled() {
        return new CheckSetting(false, null, Map.of());
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

/** Contact-form test modes (spec 7.2). Stored in Phase 1; acted on in Phase 3. */
public enum FormTestMode {
    NO_SUBMIT, SUBMIT, SUBMIT_AND_VERIFY_MAIL
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;
import java.util.Map;

/**
 * Everything a run needs to know about the site it is checking. Immutable, and the only
 * shape in which catalog data crosses a module boundary — the JPA entities never do.
 */
public record SiteContext(
        long siteId,
        String name,
        NormalizedUrl baseUrl,
        CrawlBudget budget,
        List<String> includePatterns,
        List<String> excludePatterns,
        List<String> pinnedKeyPages,
        boolean respectRobots,
        String userAgent,
        Map<CheckType, CheckSetting> checkSettings) {

    public SiteContext {
        includePatterns = List.copyOf(includePatterns);
        excludePatterns = List.copyOf(excludePatterns);
        pinnedKeyPages = List.copyOf(pinnedKeyPages);
        checkSettings = Map.copyOf(checkSettings);
    }

    public boolean enabled(CheckType type) {
        CheckSetting setting = checkSettings.get(type);
        return setting != null && setting.enabled();
    }

    public Map<String, Object> settingsFor(CheckType type) {
        CheckSetting setting = checkSettings.get(type);
        return setting == null ? Map.of() : setting.config();
    }

    public Severity severityFor(CheckType type, Severity declaredDefault) {
        CheckSetting setting = checkSettings.get(type);
        return (setting == null || setting.severityOverride() == null)
                ? declaredDefault
                : setting.severityOverride();
    }

    /** The User-Agent to identify ourselves with, so the company's access logs stay greppable (spec 8). */
    public String effectiveUserAgent() {
        return (userAgent == null || userAgent.isBlank())
                ? "WebTestHelper/1.0 (+internes Website-Monitoring)"
                : userAgent;
    }
}
```

- [ ] **Step 5: Write the catalog module**

`catalog/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Catalog",
        allowedDependencies = {"model"})
package dev.hendrikhoemberg.webtesthelper.catalog;
```

The entities and repositories are mechanical; match the migrations exactly (this is what
`ddl-auto=validate` checks):

**`SiteEntity`** (`@Table("site")`, Lombok `@Getter/@Setter/@NoArgsConstructor`, `@Version long version`):
| Field | Type | Column |
|---|---|---|
| `id` | `Long` | `@Id @GeneratedValue(IDENTITY)` |
| `name` | `String` | `name`, NOT NULL |
| `baseUrl` | `String` | `base_url`, NOT NULL (stored normalised) |
| `enabled` | `boolean` | `enabled`, default TRUE |
| `maxPages` | `int` | `max_pages` |
| `maxDepth` | `int` | `max_depth` |
| `maxDurationSeconds` | `int` | `max_duration_seconds` |
| `includePatterns` / `excludePatterns` / `pinnedKeyPages` | `List<String>` | `@JdbcTypeCode(SqlTypes.JSON)` |
| `respectRobots` | `boolean` | `respect_robots` |
| `userAgent` | `String` | `user_agent`, nullable |
| `formTestMode` | `FormTestMode` | `form_test_mode`, `@Enumerated(STRING)` |
| `createdAt` / `updatedAt` | `Instant` | `created_at` / `updated_at` |

**`SiteCheckSettingEntity`** (`@Table("site_check_setting")`): `id`, `siteId` (`Long`, plain
column `site_id`, NOT NULL — no `@ManyToOne`, see Global Constraints), `checkType`
(`CheckType`, `@Enumerated(STRING)`), `enabled` (boolean), `severityOverride` (`Severity`,
nullable, `@Enumerated(STRING)`), `config` (`Map<String,Object>` via `@JdbcTypeCode(SqlTypes.JSON)`).

**Repositories** — plain `JpaRepository` interfaces:
- `SiteRepository`: `Optional<SiteEntity> findByBaseUrl(String)`.
- `SiteCheckSettingRepository`: `List<SiteCheckSettingEntity> findBySiteId(Long)`,
  `Optional<SiteCheckSettingEntity> findBySiteIdAndCheckType(Long, CheckType)`.

**`SiteForm`** — record mirroring the editable fields: `name, baseUrl, maxPages, maxDepth,
maxDuration, includePatterns, excludePatterns, respectRobots, userAgent`.

**`SiteSummary`** — record: `id, name, baseUrl, enabled, checkCount`, for list screens.

**`SiteService`** (`@Service @Transactional`, consumes `UrlNormalizer`):
- `create(SiteForm)`: validate via `UrlNormalizer.normalize(baseUrl).orElseThrow(...)`
  (message contains the raw input — see the test); persist site with the *normalised*
  base URL; persist one `SiteCheckSettingEntity` per `CheckType` value, all enabled
  except `CONSOLE_ERRORS` and `SITEMAP_CONSISTENCY` (§7.1); return id.
- `contextFor(long)`: load site + settings, build `SiteContext` from `CrawlBudget(
  maxPages, maxDepth, Duration.ofSeconds(maxDurationSeconds))` and the settings map.
- `setCheckEnabled(long, CheckType, boolean)`: upsert the setting row.
- `update(long, SiteForm)`, `delete(long)`, `summaries()`, `summary(long)`: straight CRUD.
- `delete` cascades settings via the FK (`ON DELETE CASCADE`).

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw test -Dtest=SiteServiceTest`
Expected: PASS, all four cases. `FlywayMigrationTest` and `ModularityTest` must still pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration src/main/java/dev/hendrikhoemberg/webtesthelper/{model,catalog} src/test/java/dev/hendrikhoemberg/webtesthelper/catalog
git commit -m "feat(catalog): add site catalog with per-check settings

Sites normalise their base URL through UrlNormalizer and carry a check
setting per CheckType; the two noisy checks ship disabled (spec 7.1).
Runner-facing data crosses the boundary only as the immutable SiteContext."
```

---

### Task 4: `Run` and the leased job queue

A `Run` is a row that a worker leases. Leasing in the database rather than in memory is
what buys orphaned-run recovery after a container restart (§14). Two rules make this
subtle and both are enforced in SQL rather than in Java:

1. **`SELECT … FOR UPDATE SKIP LOCKED`** so two workers polling simultaneously never
   contend for the same row and never block each other.
2. **One run at a time per site** (§5.3). A `NOT EXISTS` guard handles the common case,
   and a **partial unique index** is the backstop — under `READ COMMITTED` two concurrent
   transactions cannot see each other's uncommitted `RUNNING` row, so the guard alone is
   racy and the index is what actually makes it true.

**Files:**
- Create: `src/main/resources/db/migration/V4__run.sql`
- Create: `src/main/java/.../model/RunStatus.java`, `RunTrigger.java`, `RunScope.java`
- Create: `src/main/java/.../runner/package-info.java`
- Create: `src/main/java/.../runner/RunLease.java`
- Create: `src/main/java/.../runner/WorkerIdentity.java`
- Create: `src/main/java/.../runner/RunService.java`
- Create: `src/main/java/.../runner/RunSummary.java`
- Create: `src/main/java/.../runner/persistence/RunEntity.java`
- Create: `src/main/java/.../runner/persistence/RunRepository.java`
- Create: `src/main/java/.../runner/persistence/RunLeaseJdbcRepository.java`
- Test: `src/test/java/.../runner/RunLeaseJdbcRepositoryTest.java`

**Interfaces:**
- Consumes: `model.*`, `catalog.SiteService` (for existence checks only).
- Produces:
  - `RunLease(long runId, long siteId, RunScope scope, RunTrigger trigger, Instant leaseExpiresAt)`
  - `RunLeaseJdbcRepository.claimNext(String owner, Duration leaseFor) -> Optional<RunLease>`
  - `RunLeaseJdbcRepository.heartbeat(long runId, String owner, Duration extendBy) -> boolean`
  - `RunLeaseJdbcRepository.finish(long runId, String owner, RunStatus status, String error) -> boolean`
  - `RunLeaseJdbcRepository.reclaimExpiredLeases() -> List<Long>`
  - `RunService.enqueue(long siteId, RunTrigger, RunScope) -> long`
  - `RunService.recentForSite(long siteId, int limit) -> List<RunSummary>`
  - `RunService.summary(long runId) -> RunSummary`
  - `WorkerIdentity.name() -> String`

- [ ] **Step 1: Write the failing test**

Deliberately **not** `@Transactional`: the whole point is what concurrent, committed
transactions do to each other.

```java
package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunLeaseJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class RunLeaseJdbcRepositoryTest extends AbstractPostgresTest {

    @Autowired
    RunLeaseJdbcRepository leases;

    @Autowired
    JdbcTemplate jdbc;

    private long siteA;
    private long siteB;

    @BeforeEach
    void resetTables() {
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        siteA = insertSite("https://a.example.com/");
        siteB = insertSite("https://b.example.com/");
    }

    private long insertSite(String baseUrl) {
        return jdbc.queryForObject(
                "INSERT INTO site (name, base_url) VALUES (?, ?) RETURNING id",
                Long.class, baseUrl, baseUrl);
    }

    private long queueRun(long siteId) {
        return jdbc.queryForObject("""
                INSERT INTO run (site_id, trigger_type, scope, status)
                VALUES (?, 'MANUAL', 'FULL', 'QUEUED') RETURNING id
                """, Long.class, siteId);
    }

    @Test
    void claimsAQueuedRunAndMarksItRunning() {
        long runId = queueRun(siteA);

        RunLease lease = leases.claimNext("worker-1", Duration.ofMinutes(5)).orElseThrow();

        assertThat(lease.runId()).isEqualTo(runId);
        assertThat(lease.siteId()).isEqualTo(siteA);
        assertThat(lease.scope()).isEqualTo(RunScope.FULL);
        assertThat(lease.trigger()).isEqualTo(RunTrigger.MANUAL);
        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT started_at IS NOT NULL FROM run WHERE id = ?",
                Boolean.class, runId)).isTrue();
    }

    @Test
    void returnsEmptyWhenNothingIsQueued() {
        assertThat(leases.claimNext("worker-1", Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void twoWorkersNeverClaimTheSameRun() throws Exception {
        long runA = queueRun(siteA);
        long runB = queueRun(siteB);

        List<Optional<RunLease>> results = inParallel(8,
                () -> leases.claimNext("worker-" + Thread.currentThread().threadId(), Duration.ofMinutes(5)));

        List<Long> claimed = results.stream().flatMap(Optional::stream).map(RunLease::runId).toList();
        assertThat(claimed).containsExactlyInAnyOrder(runA, runB);
    }

    @Test
    void onlyOneRunPerSiteIsEverRunning() throws Exception {
        queueRun(siteA);
        queueRun(siteA);

        List<Optional<RunLease>> results = inParallel(6,
                () -> leases.claimNext("worker-" + Thread.currentThread().threadId(), Duration.ofMinutes(5)));

        assertThat(results.stream().flatMap(Optional::stream)).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM run WHERE site_id = ? AND status = 'RUNNING'", Integer.class, siteA))
                .isEqualTo(1);
    }

    @Test
    void anExpiredLeaseIsReclaimedByTheNextClaim() {
        long runId = queueRun(siteA);
        leases.claimNext("dead-worker", Duration.ofMinutes(5)).orElseThrow();
        jdbc.update("UPDATE run SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", runId);

        RunLease reclaimed = leases.claimNext("live-worker", Duration.ofMinutes(5)).orElseThrow();

        assertThat(reclaimed.runId()).isEqualTo(runId);
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("live-worker");
    }

    @Test
    void aQueuedRunIsNotClaimedWhileTheSameSiteHasAStaleRunningRun() {
        long stale = queueRun(siteA);
        leases.claimNext("dead-worker", Duration.ofMinutes(5)).orElseThrow();
        jdbc.update("UPDATE run SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", stale);
        long queued = queueRun(siteA);

        RunLease lease = leases.claimNext("live-worker", Duration.ofMinutes(5)).orElseThrow();

        assertThat(lease.runId()).isEqualTo(stale).isNotEqualTo(queued);
    }

    @Test
    void heartbeatExtendsTheLeaseOnlyForTheOwner() {
        long runId = queueRun(siteA);
        leases.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();

        assertThat(leases.heartbeat(runId, "worker-2", Duration.ofMinutes(5))).isFalse();
        assertThat(leases.heartbeat(runId, "worker-1", Duration.ofMinutes(5))).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT lease_expires_at > now() + interval '2 minutes' FROM run WHERE id = ?",
                Boolean.class, runId)).isTrue();
    }

    @Test
    void finishClearsTheLeaseAndRecordsTheOutcome() {
        long runId = queueRun(siteA);
        leases.claimNext("worker-1", Duration.ofMinutes(5)).orElseThrow();

        assertThat(leases.finish(runId, "worker-1", RunStatus.FAILED, "Browser gestorben")).isTrue();

        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM run WHERE id = ?", String.class, runId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT error_message FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("Browser gestorben");
    }

    @Test
    void theStartupSweepRequeuesEveryExpiredLease() {
        long runId = queueRun(siteA);
        leases.claimNext("dead-worker", Duration.ofMinutes(5)).orElseThrow();
        jdbc.update("UPDATE run SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", runId);

        assertThat(leases.reclaimExpiredLeases()).containsExactly(runId);
        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("QUEUED");
    }

    private <T> List<T> inParallel(int threads, Callable<T> work) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<T>> futures = pool.invokeAll(java.util.Collections.nCopies(threads, work));
            List<T> results = new java.util.ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=RunLeaseJdbcRepositoryTest`
Expected: compilation failure — `RunLeaseJdbcRepository` does not exist.

- [ ] **Step 3: Write `V4__run.sql`**

```sql
CREATE TABLE run (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    site_id BIGINT NOT NULL REFERENCES site (id) ON DELETE CASCADE,
    trigger_type TEXT NOT NULL,
    scope TEXT NOT NULL,
    status TEXT NOT NULL,

    queued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,

    lease_owner TEXT,
    lease_expires_at TIMESTAMPTZ,

    pages_visited INTEGER NOT NULL DEFAULT 0,
    pages_failed INTEGER NOT NULL DEFAULT 0,
    findings_total INTEGER NOT NULL DEFAULT 0,

    -- Coverage is load-bearing: resolution applies only within it (spec 6.4).
    covered_check_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    covered_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
    partial_coverage BOOLEAN NOT NULL DEFAULT FALSE,
    budget_stop_reason TEXT,

    -- Soft-404 probe taken at crawl start (spec 7.1). SimHash of the probe page's text.
    soft404_simhash BIGINT,
    soft404_status INTEGER,
    soft404_text_length INTEGER,

    baseline_accepted_at TIMESTAMPTZ,
    error_message TEXT,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX ix_run_queue ON run (status, queued_at);
CREATE INDEX ix_run_site_recent ON run (site_id, queued_at DESC);

-- The backstop for "one run at a time per site" (spec 5.3). Under READ COMMITTED the
-- NOT EXISTS guard in the claim statement cannot see another transaction's uncommitted
-- RUNNING row; this index can.
CREATE UNIQUE INDEX ux_run_single_active_per_site ON run (site_id) WHERE status = 'RUNNING';
```

- [ ] **Step 4: Write the run enums**

```java
package dev.hendrikhoemberg.webtesthelper.model;

public enum RunStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

/** Webhook triggering is out of scope; the enum exists so it can be added later (spec 3). */
public enum RunTrigger { SCHEDULED, MANUAL }
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.EnumSet;
import java.util.Set;

/** Tier scope (spec 9). Only FULL is reachable in Phase 1; the others exist for Phase 2. */
public enum RunScope {

    PULSE, FULL, DEEP;

    /** Which checks a scope is allowed to run. Drives the run's coverage (spec 6.4). */
    public Set<CheckType> checkTypes() {
        return switch (this) {
            case PULSE -> EnumSet.of(CheckType.PAGE_STATUS, CheckType.PAGE_UNREACHABLE,
                    CheckType.DEAD_LINK, CheckType.IMAGE_BROKEN, CheckType.REDIRECT_CHAIN,
                    CheckType.MIXED_CONTENT, CheckType.CONSOLE_ERRORS);
            case FULL, DEEP -> EnumSet.allOf(CheckType.class);
        };
    }

    /** PULSE crawls only the site's pinned key pages, never the discovered frontier (spec 9). */
    public boolean crawlsWholeSite() {
        return this != PULSE;
    }
}
```

- [ ] **Step 5: Write the run entity, repositories and service**

`runner/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Runner",
        allowedDependencies = {"model", "catalog"})
package dev.hendrikhoemberg.webtesthelper.runner;
```

> `crawler`, `checks` and `findings` do not exist yet. Add each name to `allowedDependencies`
> in the plan that creates the module — Spring Modulith fails `ModularityTest` if
> `allowedDependencies` names a module that is not on the classpath.

**`RunEntity`** (`@Table("run")`, Lombok `@Getter/@Setter/@NoArgsConstructor`, `@Version long version`):
| Field | Type | Column |
|---|---|---|
| `id` | `Long` | `@Id @GeneratedValue(IDENTITY)` |
| `siteId` | `Long` | `site_id`, NOT NULL, plain FK — no `@ManyToOne` (Global Constraints) |
| `triggerType` | `RunTrigger` | `trigger_type`, `@Enumerated(STRING)` |
| `scope` | `RunScope` | `scope`, `@Enumerated(STRING)` |
| `status` | `RunStatus` | `status`, `@Enumerated(STRING)`, default `QUEUED` |
| `queuedAt` / `startedAt` / `finishedAt` | `Instant` | `queued_at` (NOT NULL, default now) / `started_at` / `finished_at` |
| `leaseOwner` / `leaseExpiresAt` | `String` / `Instant` | `lease_owner` / `lease_expires_at` |
| `pagesVisited` / `pagesFailed` / `findingsTotal` | `int` | `pages_visited` / `pages_failed` / `findings_total` |
| `coveredCheckTypes` / `coveredUrls` | `List<String>` | `@JdbcTypeCode(SqlTypes.JSON)` |
| `partialCoverage` | `boolean` | `partial_coverage` |
| `budgetStopReason` | `String` | `budget_stop_reason`, nullable |
| `soft404Simhash` / `soft404Status` / `soft404TextLength` | `Long` / `Integer` / `Integer` | `soft404_simhash` / `soft404_status` / `soft404_text_length` |
| `baselineAcceptedAt` | `Instant` | `baseline_accepted_at`, nullable |
| `errorMessage` | `String` | `error_message`, nullable |

**`RunRepository`** — `JpaRepository<RunEntity, Long>`:
```java
List<RunEntity> findBySiteIdOrderByQueuedAtDesc(Long siteId, Limit limit);

Optional<RunEntity> findFirstBySiteIdAndStatusOrderByQueuedAtAsc(Long siteId, RunStatus status);

/** The previous completed run of the same site — the diff's baseline (spec 6.3). */
Optional<RunEntity> findFirstBySiteIdAndStatusAndIdLessThanOrderByIdDesc(
        Long siteId, RunStatus status, Long beforeRunId);
```

**`RunLease`** and **`WorkerIdentity`**:

```java
package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;

import java.time.Instant;

public record RunLease(long runId, long siteId, RunScope scope, RunTrigger trigger,
                       Instant leaseExpiresAt) {
}
```

```java
package dev.hendrikhoemberg.webtesthelper.runner;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/** Stable-per-JVM lease owner, so a restarted container never matches its own stale leases. */
@Component
public class WorkerIdentity {

    private final String name;

    public WorkerIdentity() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        this.name = host + "/" + UUID.randomUUID();
    }

    public String name() {
        return name;
    }
}
```

**`RunSummary`** — record: `id, siteId, status, trigger, scope, queuedAt, startedAt,
finishedAt, pagesVisited, pagesFailed, findingsTotal, partialCoverage, budgetStopReason,
baselineAccepted, errorMessage, coveredCheckTypes`.

**`RunService`** (`@Service @Transactional`, consumes `RunRepository`):
- `enqueue(long siteId, RunTrigger, RunScope) -> long`: return the id of the site's
  existing `QUEUED` run if one exists (clicking "Jetzt prüfen" twice must not build a
  backlog), else save a fresh `QUEUED` run and return its id.
- `recentForSite(long, int) -> List<RunSummary>` via `Limit.of(n)`.
- `summary(long) -> RunSummary`, throwing `IllegalArgumentException("Lauf <id> existiert nicht")` for unknown ids.

- [ ] **Step 6: Write `RunLeaseJdbcRepository` — the `SKIP LOCKED` core**

```java
package dev.hendrikhoemberg.webtesthelper.runner.persistence;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.runner.RunLease;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Lease management for runs. Every statement here is deliberately raw SQL: the semantics
 * depend on {@code FOR UPDATE SKIP LOCKED} and on a partial unique index, neither of which
 * JPA can express.
 */
@Repository
public class RunLeaseJdbcRepository {

    /**
     * Claims the next eligible run.
     *
     * <p>Eligible means QUEUED, or RUNNING with an expired lease — the second case is how
     * a run orphaned by a container crash gets picked back up (spec 14). The NOT EXISTS
     * guard enforces one run per site (spec 5.3) and deliberately does <em>not</em> test
     * {@code lease_expires_at}: a site with a stale RUNNING run must have that run
     * reclaimed rather than a fresh QUEUED one started beside it. The ORDER BY puts stale
     * RUNNING runs first for the same reason.
     *
     * <p>SKIP LOCKED is what lets several workers poll simultaneously without blocking:
     * a row another transaction has locked is passed over instead of waited on.
     */
    private static final String CLAIM_SQL = """
            UPDATE run
               SET status           = 'RUNNING',
                   lease_owner      = ?,
                   lease_expires_at = now() + make_interval(secs => ?),
                   started_at       = COALESCE(started_at, now())
             WHERE id = (
                   SELECT r.id
                     FROM run r
                    WHERE (r.status = 'QUEUED'
                           OR (r.status = 'RUNNING' AND r.lease_expires_at < now()))
                      AND NOT EXISTS (SELECT 1
                                        FROM run other
                                       WHERE other.site_id = r.site_id
                                         AND other.status  = 'RUNNING'
                                         AND other.id     <> r.id)
                    ORDER BY (r.status = 'RUNNING') DESC, r.queued_at, r.id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED)
         RETURNING id, site_id, scope, trigger_type, lease_expires_at
            """;

    private static final String HEARTBEAT_SQL = """
            UPDATE run
               SET lease_expires_at = now() + make_interval(secs => ?)
             WHERE id = ? AND lease_owner = ? AND status = 'RUNNING'
            """;

    private static final String FINISH_SQL = """
            UPDATE run
               SET status           = ?,
                   finished_at      = now(),
                   lease_owner      = NULL,
                   lease_expires_at = NULL,
                   error_message    = ?
             WHERE id = ? AND lease_owner = ?
            """;

    private static final String RECLAIM_SQL = """
            UPDATE run
               SET status           = 'QUEUED',
                   lease_owner      = NULL,
                   lease_expires_at = NULL
             WHERE status = 'RUNNING' AND lease_expires_at < now()
         RETURNING id
            """;

    private static final RowMapper<RunLease> LEASE_MAPPER = (rs, row) -> new RunLease(
            rs.getLong("id"),
            rs.getLong("site_id"),
            RunScope.valueOf(rs.getString("scope")),
            RunTrigger.valueOf(rs.getString("trigger_type")),
            rs.getTimestamp("lease_expires_at").toInstant());

    private final JdbcTemplate jdbc;

    public RunLeaseJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RunLease> claimNext(String owner, Duration leaseFor) {
        try {
            return jdbc.query(CLAIM_SQL, LEASE_MAPPER, owner, (double) leaseFor.toSeconds())
                    .stream().findFirst();
        } catch (DuplicateKeyException raceLostToAnotherWorker) {
            // ux_run_single_active_per_site fired: another transaction committed a RUNNING
            // run for this site between our NOT EXISTS check and our UPDATE. Nothing to
            // claim right now; the caller polls again.
            return Optional.empty();
        }
    }

    public boolean heartbeat(long runId, String owner, Duration extendBy) {
        return jdbc.update(HEARTBEAT_SQL, (double) extendBy.toSeconds(), runId, owner) == 1;
    }

    public boolean finish(long runId, String owner, RunStatus status, String errorMessage) {
        return jdbc.update(FINISH_SQL, status.name(), errorMessage, runId, owner) == 1;
    }

    /** Startup and timer sweep: requeue everything whose worker died holding the lease. */
    public List<Long> reclaimExpiredLeases() {
        return jdbc.queryForList(RECLAIM_SQL, Long.class);
    }
}
```

> `make_interval(secs => ?)` takes a `double`, hence the cast. Passing a `long` binds as
> `bigint` and Postgres rejects it with "function make_interval(secs => bigint) does not
> exist" — a confusing error for a small mistake.

- [ ] **Step 7: Run the test to verify it passes**

Run: `./mvnw test -Dtest=RunLeaseJdbcRepositoryTest`
Expected: PASS, all nine cases.

If `onlyOneRunPerSiteIsEverRunning` fails with a `DuplicateKeyException` escaping instead
of being swallowed, the catch in `claimNext` is missing or catching the wrong type —
Spring translates Postgres SQLSTATE 23505 to `DuplicateKeyException`.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V4__run.sql src/main/java/dev/hendrikhoemberg/webtesthelper/{model,runner} src/test/java/dev/hendrikhoemberg/webtesthelper/runner
git commit -m "feat(runner): add Run entity and lease-based job queue

Claim uses SELECT ... FOR UPDATE SKIP LOCKED; one-run-per-site is enforced
by a partial unique index because the NOT EXISTS guard is racy under READ
COMMITTED."
```

---

### Task 5: The worker loop — Plan 1's working end-to-end

Plan 1 ends with software that demonstrably does something: a worker loop that claims
queued runs, keeps the lease alive, executes the (for now: no-op) run, and completes it.
Plan 2 replaces the no-op executor with the crawler; the loop, the lease mechanics and the
test stay.

**Files:**
- Create: `src/main/java/.../runner/RunExecutor.java`
- Create: `src/main/java/.../runner/NoopRunExecutor.java`
- Create: `src/main/java/.../runner/RunWorker.java`
- Test: `src/test/java/.../runner/RunWorkerTest.java`

**Interfaces:**
- Consumes: `RunLeaseJdbcRepository`, `WorkerIdentity`, `catalog.SiteService`.
- Produces:
  - `RunExecutor.execute(RunLease lease) -> void` — the seam Plan 2 plugs the crawler into.
  - `RunWorker.workOnce() -> boolean` — one claim attempt; returns true if it ran a run.

- [ ] **Step 1: Write the failing test**

```java
package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunLeaseJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RunWorkerTest extends AbstractPostgresTest {

    @Autowired
    RunWorker worker;

    @Autowired
    RunService runs;

    @Autowired
    RunLeaseJdbcRepository leases;

    @Autowired
    JdbcTemplate jdbc;

    private long siteId;

    @BeforeEach
    void setUpSite() {
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site");
        siteId = jdbc.queryForObject(
                "INSERT INTO site (name, base_url) VALUES ('Test', 'https://t.example.com/') RETURNING id",
                Long.class);
    }

    @Test
    void aQueuedRunIsClaimedExecutedAndCompleted() {
        long runId = runs.enqueue(siteId, RunTrigger.MANUAL, dev.hendrikhoemberg.webtesthelper.model.RunScope.FULL);

        assertThat(worker.workOnce()).isTrue();

        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM run WHERE id = ?", String.class, runId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT finished_at IS NOT NULL FROM run WHERE id = ?",
                Boolean.class, runId)).isTrue();
    }

    @Test
    void aFailedExecutionMarksTheRunFailed() {
        long runId = runs.enqueue(siteId, RunTrigger.MANUAL, dev.hendrikhoemberg.webtesthelper.model.RunScope.FULL);
        worker.withExecutorForTest(lease -> { throw new RuntimeException("Kaputt"); });

        assertThat(worker.workOnce()).isTrue();

        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_message FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("Kaputt");
    }

    @Test
    void anEmptyQueueIsANoOp() {
        assertThat(worker.workOnce()).isFalse();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=RunWorkerTest`
Expected: compilation failure — `RunWorker` does not exist.

- [ ] **Step 3: Write the executor seam and the worker**

`RunExecutor.java` — the seam Plan 2 plugs into:

```java
package dev.hendrikhoemberg.webtesthelper.runner;

/** Executes one leased run. Plan 2 replaces the no-op with the crawler pipeline. */
public interface RunExecutor {

    void execute(RunLease lease) throws Exception;
}
```

`NoopRunExecutor.java`:

```java
package dev.hendrikhoemberg.webtesthelper.runner;

import org.springframework.stereotype.Component;

/** Plan 1 placeholder: the run succeeds without doing anything. */
@Component
public class NoopRunExecutor implements RunExecutor {

    @Override
    public void execute(RunLease lease) {
        // Plan 2: crawl + checks + materialise + diff
    }
}
```

`RunWorker.java`:

```java
package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunLeaseJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Polls the queue and executes claimed runs. {@code workOnce()} is one claim attempt and
 * exists so tests can drive the loop deterministically; production wiring (a scheduled
 * poll) arrives with the UI in Plan 5.
 */
@Component
public class RunWorker {

    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);
    private static final Duration LEASE = Duration.ofMinutes(30);

    private final RunLeaseJdbcRepository leases;
    private final WorkerIdentity identity;
    private RunExecutor executor;

    public RunWorker(RunLeaseJdbcRepository leases, WorkerIdentity identity, RunExecutor executor) {
        this.leases = leases;
        this.identity = identity;
        this.executor = executor;
    }

    /** Test seam only. */
    void withExecutorForTest(RunExecutor executor) {
        this.executor = executor;
    }

    /** One claim attempt. Returns true if a run was claimed and executed. */
    public boolean workOnce() {
        return leases.claimNext(identity.name(), LEASE)
                .map(this::executeLeased)
                .orElse(false);
    }

    private boolean executeLeased(RunLease lease) {
        try {
            log.info("Run {} gestartet (site {})", lease.runId(), lease.siteId());
            executor.execute(lease);
            leases.finish(lease.runId(), identity.name(), RunStatus.COMPLETED, null);
            log.info("Run {} abgeschlossen", lease.runId());
        } catch (Exception e) {
            log.error("Run {} fehlgeschlagen", lease.runId(), e);
            leases.finish(lease.runId(), identity.name(), RunStatus.FAILED, e.getMessage());
        }
        return true;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=RunWorkerTest`
Expected: PASS, all three cases.

- [ ] **Step 5: Full suite and commit**

Run: `./mvnw test`
Expected: PASS — `UrlNormalizerTest`, `SiteServiceTest`, `RunLeaseJdbcRepositoryTest`,
`RunWorkerTest`, `FlywayMigrationTest`, `ModularityTest`, `contextLoads`.

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/runner src/test/java/dev/hendrikhoemberg/webtesthelper/runner
git commit -m "feat(runner): add worker loop with executor seam

Claims runs with SKIP LOCKED leases, executes them through the RunExecutor
seam (no-op for now; the crawler plugs in here in Plan 2), and completes or
fails them. Plan 1 is complete: queueing, leasing, recovery and completion
are proven end-to-end."
```

---

## Plan 1 completion check

- [ ] `./mvnw test` passes on real Postgres
- [ ] `docker compose up -d postgres` works on a clean checkout
- [ ] Six commits landed (one per task)
- [ ] `ModularityTest` fails loudly if a module crosses a declared boundary
- [ ] `FlywayMigrationTest` + `ddl-auto=validate` guard the schema
- [ ] Roadmap's Plan 1 row is done — proceed to write `2026-08-21-webtesthelper-p2-crawler.md`
