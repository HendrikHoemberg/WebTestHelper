# WebTestHelper Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the crawl-and-check core of WebTestHelper — a Spring Boot application that crawls a configured customer site once per run, captures an immutable `PageSnapshot` per page, evaluates every layer-1 passive check against those snapshots, fingerprints the results, diffs them against the previous run within coverage, and shows the outcome in a German web UI, plus working SMTP plumbing.

**Architecture:** Modular monolith. Nine-plus Spring Modulith packages with build-enforced dependency direction. A `Run` is a database-leased job; browser workers are thread-confined `Playwright` + `Browser` pairs on platform threads; the crawl frontier is a Postgres table claimed in batches with `SELECT … FOR UPDATE SKIP LOCKED`; asset verification runs on virtual threads behind a per-host semaphore using `java.net.http.HttpClient`. Checks are pure functions over value records and emit transient `CheckFinding`s; persistent `Finding` rows are created only at post-crawl materialisation, which is the only point where site-wide promotion can be computed.

**Tech Stack:** Java 25, Spring Boot 4.1.1, PostgreSQL 17, Flyway, Spring Data JPA + `JdbcTemplate`, Spring Modulith, Playwright for Java, Thymeleaf + HTMX + Alpine.js (vendored), Spring Security, Spring Mail, Testcontainers, GreenMail, JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md` — read it alongside this plan. Every task argues from a section of it; section references like (§6.4) point there.

---

## Deviations and clarifications — read before Task 1

The spec is approved and is not being redesigned. These five points are places where the
spec is silent or where two of its statements pull in different directions. Each is
flagged here with the resolution this plan adopts, so the choice is visible rather than
buried in a task.

**D1 — A tenth package, `model`, holding shared value types.**
§5.1 lists nine packages and requires that `checks` and `findings` "depend only on their
own value types". But `checks` consumes `PageSnapshot` (produced by `crawler`) and emits
`CheckFinding` (consumed by `findings`). With only the nine packages, one of those three
modules must depend on another. This plan adds `model`: a flat package of records and
enums with **zero** application-module dependencies, holding `PageSnapshot` and its
element records, `CheckFinding`, `CheckConfig`, `RunFacts`, `SiteContext`, `RunCoverage`,
`UrlVerdict`, `NormalizedUrl`, `UrlNormalizer`, and the shared enums. `checks` and
`findings` then depend on `model` and nothing else, which is what §5.1 is asking for.
`model` is flat (no sub-packages) because Spring Modulith treats a module's sub-packages
as internal and they would not be visible to other modules.

**D2 — Page checks run in one post-verification pass, not inline in the crawl loop.**
The §5.3 diagram puts "page checks" inside the crawl loop and asset verification after it.
But `DEAD_LINK`, `FILE_DOWNLOAD`, `MEDIA_PLAYABLE`'s source resolution and
`REDIRECT_CHAIN`'s link hops all need verification verdicts that do not exist until asset
verification has run. This plan retains every `PageSnapshot` for the run (they are
retained for site checks regardless, §5.2) and evaluates **all** page checks in a single
pass after asset verification. Nothing observable changes — page checks are pure
functions — only when they execute.

**D3 — `CheckConfig` carries run-scoped facts as well as persisted settings.**
§7.3 fixes the signature `evaluate(PageSnapshot, CheckConfig)`, which leaves no channel
for the soft-404 probe fingerprint (§7.1) or the URL verdicts from D2. Rather than change
the signature, `CheckConfig` is
`record CheckConfig(Map<String,Object> settings, RunFacts facts)`, where `RunFacts` is a
pure interface exposing `soft404()` and `verdict(String)`. Unit tests hand-build both
halves; no check ever touches Spring or the database.

**D4 — "target URL lowercased" is applied to scheme and host only.**
§6.2 says the dead-link `subjectKey` is the "target URL lowercased". Lowercasing the whole
URL would merge `/Kontakt` and `/kontakt`, which are distinct resources on a
case-sensitive server, and would corrupt both the crawl frontier and the external URL
cache (which is keyed on the same normalised form). `UrlNormalizer` lowercases scheme and
host, and leaves path and query case intact. Everything else in §6.2 — default port
dropped, fragment dropped, query sorted, tracking parameters stripped — is applied
literally.

**D5 — Embedded help (§13.6/§13.7) gets a minimal Phase 1 footprint.**
§17's Phase 1 list does not mention `/hilfe`, but §13.7 requires *two* build-failing
tests, the second of which walks the `?` affordances that Task 19 introduces. Task 19
therefore ships the `?` affordance mechanism, three Markdown help topics, and the
help-topic completeness test. Long-form handbook content is Phase 2 work.

---

## Global Constraints

Every task's requirements implicitly include this section.

- **Java 25**, **Spring Boot 4.1.1** (`spring-boot-starter-parent`). Boot 4 starter names:
  `spring-boot-starter-webmvc` (not `-web`), `spring-boot-starter-flyway`,
  `spring-boot-starter-data-jpa-test`, `spring-boot-starter-webmvc-test`,
  `spring-boot-starter-thymeleaf-test`.
- **Playwright's Java API is not thread-safe.** A `Playwright` instance and every object
  derived from it may only be touched by the thread that created it. Every browser worker
  creates its own `Playwright` and `Browser` *inside* its own `run()` method and never
  hands either to another thread. No shared `Browser`, no borrowed contexts. (§5.4)
- **Browser workers are platform threads. Asset checking uses virtual threads** with a
  per-host `Semaphore` and `java.net.http.HttpClient`. Never move browser work onto
  virtual threads. (§5.4)
- **`JdbcTemplate` for `crawl_queue_item` and `finding_occurrence`. JPA everywhere else.** (§6.5)
- **The crawl frontier is claimed and completed in batches of 20**, one SQL statement per
  batch. Findings are *not* incremental — they accumulate in memory and are written once
  at materialisation. (§6.5)
- **Run claim and frontier claim both use `SELECT … FOR UPDATE SKIP LOCKED`.**
- **Java `record`s for value types; Lombok only for JPA entities.** (§6.5)
- **`spring.jpa.hibernate.ddl-auto=validate` in every environment, tests included.**
  Flyway owns the schema. A mismatch must fail startup. (§6.5)
- **Repository tests run against real PostgreSQL** via Testcontainers `@ServiceConnection`.
  No H2, no in-memory substitute, ever. (§15)
- **No cross-module JPA associations.** A `Run` row references its site as a plain
  `Long siteId`, not `@ManyToOne SiteEntity` — the entities live in different Modulith
  modules. Foreign keys are still declared in the Flyway migrations.
- **Checks emit the transient `CheckFinding` record.** The persistent `Finding` entity is
  created only during materialisation. Checks know nothing about fingerprints, lifecycle,
  or persistence. (§7.3)
- **Every check carries `titleKey`, `descriptionKey` and `remediationKey`, and every one of
  those must resolve in `messages_de.properties`.** A build-failing test enforces it. (§13.7)
- **`CONSOLE_ERRORS` and `SITEMAP_CONSISTENCY` ship `enabled = false` by default.** (§7.1)
- **German is the only locale.** No internal identifier reaches the screen — "Tote Links",
  never `DEAD_LINK`. (§12, §13.1)
- **HTMX and Alpine.js are vendored into `src/main/resources/static/vendor/`.** No CDN
  reference anywhere. (§12)
- **`MuteRule`, `Schedule`, `Journey`, `Credential`, `NotificationRecipient`, the recorder,
  interaction checks and digest content are out of scope.** Do not create tables, entities
  or packages for them. `Run.trigger` stays an enum so `SCHEDULED` exists unused.
- **Commit after every task.** Conventional commit messages, German-free (code and commits
  are English; only user-facing strings are German).

---

## File Structure

Root package `dev.hendrikhoemberg.webtesthelper`. Each direct sub-package is a Spring
Modulith application module and carries a `package-info.java` declaring its allowed
dependencies. A module's own sub-packages (`persistence`, `core`) are internal — other
modules may only reference types in the module's base package.

```
model/            zero dependencies. Records, enums, UrlNormalizer. Flat, no sub-packages.
catalog/          Site, SiteCheckSetting, global Setting, AppUser. Depends on: model
  persistence/    JPA entities + repositories
checks/           Check SPI + all layer-1 implementations + registry. Depends on: model
crawler/          Frontier, robots, seeding, snapshot extraction, asset verification,
                  external URL cache. Depends on: model
  persistence/    crawl_queue_item JdbcTemplate repo, external_url_check entity
findings/         Fingerprinting, materialisation, diff, coverage, persistence.
                  Depends on: model
  core/           pure algorithms — no Spring, no JPA, no JDBC
  persistence/    finding entity + repository + JdbcTemplate occurrence writer
runner/           Run entity, job queue, leases, browser worker pool, run orchestrator.
                  Depends on: model, catalog, crawler, checks, findings
  persistence/    run entity/repository, lease JdbcTemplate repo
reporting/        Notification outbox, mail sender, Notifier SPI. Depends on: model, catalog
  persistence/    notification entity + repository
web/              Controllers, SSE, security config. Depends on: model, catalog, runner,
                  findings, reporting
```

Resources:

```
src/main/resources/
  db/migration/V1__app_setting.sql … V8__app_user.sql
  messages_de.properties            all user-facing German copy
  help/*.md                         embedded help topics (Task 19)
  static/vendor/                    htmx.min.js, htmx-ext-sse.js, alpine.min.js
  templates/                        Thymeleaf layout + fragments
src/test/resources/
  fixture-site/                     the static failure-mode site (Task 6)
```

---

### Task 1: Build foundation — versions, dependencies, Postgres, Flyway, Modulith skeleton

**Files:**
- Modify: `pom.xml` (full rewrite of `<properties>` and `<dependencies>`)
- Modify: `src/main/resources/application.properties`
- Create: `src/main/resources/application-dev.properties`
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
Use whatever the command prints today. Two sanity checks before pinning:

```bash
# Spring Modulith must be built against Spring Boot 4.1.x — check the Boot version it compiles against
curl -s https://repo1.maven.org/maven2/org/springframework/modulith/spring-modulith-core/<VERSION>/spring-modulith-core-<VERSION>.pom \
  | grep -A2 'spring-boot-autoconfigure'
```

If the printed Modulith release is a milestone/RC (`-M`, `-RC`), step back to the newest
stable release in the same listing. If its Boot line reads `4.0.x` rather than `4.1.x`,
use it anyway but record that in the commit message — Modulith is source-compatible across
Boot minors and the `ModularityTest` in Step 8 proves it works.

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
> `org.testcontainers.postgresql.PostgreSQLContainer`. The 1.x names
> (`postgresql`, `junit-jupiter`, `org.testcontainers.containers.PostgreSQLContainer`)
> still resolve but are deprecated shims — do not use them.

- [ ] **Step 3: Verify the dependency tree resolves**

Run: `./mvnw -q dependency:tree -Dincludes=com.microsoft.playwright,org.springframework.modulith,org.testcontainers,com.icegreen`
Expected: all four groups listed, no `[ERROR] Failed to resolve`. If Modulith fails to
resolve, re-check Step 1's version.

- [ ] **Step 4: Install the Chromium build that matches the pinned Playwright version**

The application image pins Chromium to the Playwright version and the two are upgraded
together (§16). Install it locally the same way:

```bash
./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes:$(cat target/cp.txt)" com.microsoft.playwright.CLI install --with-deps chromium
```

Expected: the CLI reports Chromium downloaded (or "is already installed"). If
`--with-deps` fails for lack of root, drop it and install the system libraries by hand —
the browser download is the part that matters.

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
test extends this. One container is shared across the whole suite because the class is
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
the Postgres dependency is missing or still `sqlite-jdbc`.

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

import java.util.Optional;

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
            assertThat(norm("https://müller-bau.de/kontakt")).isEqualTo("https://xn--mller-bau-r9a.de/kontakt");
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
    List<String> includePatterns, List<String> excludePatterns, boolean respectRobots,
    String userAgent, List<String> pinnedKeyPages, Map<CheckType,CheckSetting> checkSettings)`
    with `enabled(CheckType)`, `settingsFor(CheckType)`, `severityFor(CheckType, Severity)`.
  - `CrawlBudget(int maxPages, int maxDepth, Duration maxDuration)`
  - `CheckSetting(boolean enabled, Severity severityOverride, Map<String,Object> config)`
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
        assertThat(context.budget()).isEqualTo(new CrawlBudgetHolder().budget());
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

    /** Keeps the expected budget in one place so the assertion above stays readable. */
    private record CrawlBudgetHolder() {
        dev.hendrikhoemberg.webtesthelper.model.CrawlBudget budget() {
            return new dev.hendrikhoemberg.webtesthelper.model.CrawlBudget(300, 5, Duration.ofMinutes(30));
        }
    }
}
```

Add the static import `static org.assertj.core.api.Assertions.assertThatThrownBy;`.

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

`model/CrawlBudget.java`:

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

`model/CheckSetting.java`:

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

    public static CheckSetting enabled() {
        return new CheckSetting(true, null, Map.of());
    }

    public static CheckSetting disabled() {
        return new CheckSetting(false, null, Map.of());
    }
}
```

`model/FormTestMode.java`:

```java
package dev.hendrikhoemberg.webtesthelper.model;

/** Contact-form test modes (spec 7.2). Stored in Phase 1; acted on in Phase 3. */
public enum FormTestMode {
    NO_SUBMIT, SUBMIT, SUBMIT_AND_VERIFY_MAIL
}
```

`model/SiteContext.java`:

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

`catalog/persistence/SiteEntity.java` — Lombok, because JPA entities cannot be records (§6.5):

```java
package dev.hendrikhoemberg.webtesthelper.catalog.persistence;

import dev.hendrikhoemberg.webtesthelper.model.FormTestMode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "site")
@Getter
@Setter
@NoArgsConstructor
public class SiteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "max_pages", nullable = false)
    private int maxPages = 300;

    @Column(name = "max_depth", nullable = false)
    private int maxDepth = 5;

    @Column(name = "max_duration_seconds", nullable = false)
    private int maxDurationSeconds = 1800;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "include_patterns", nullable = false)
    private List<String> includePatterns = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exclude_patterns", nullable = false)
    private List<String> excludePatterns = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pinned_key_pages", nullable = false)
    private List<String> pinnedKeyPages = new ArrayList<>();

    @Column(name = "respect_robots", nullable = false)
    private boolean respectRobots = true;

    @Column(name = "user_agent")
    private String userAgent;

    @Enumerated(EnumType.STRING)
    @Column(name = "form_test_mode", nullable = false)
    private FormTestMode formTestMode = FormTestMode.NO_SUBMIT;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;
}
```

`catalog/persistence/SiteCheckSettingEntity.java`:

```java
package dev.hendrikhoemberg.webtesthelper.catalog.persistence;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "site_check_setting")
@Getter
@Setter
@NoArgsConstructor
public class SiteCheckSettingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Plain id, not an association: Modulith forbids a JPA edge across module boundaries. */
    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false)
    private CheckType checkType;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity_override")
    private Severity severityOverride;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> config = new LinkedHashMap<>();

    @Version
    private long version;
}
```

`catalog/persistence/SiteRepository.java` and `SiteCheckSettingRepository.java`:

```java
package dev.hendrikhoemberg.webtesthelper.catalog.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SiteRepository extends JpaRepository<SiteEntity, Long> {
    List<SiteEntity> findAllByOrderByNameAsc();
}
```

```java
package dev.hendrikhoemberg.webtesthelper.catalog.persistence;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SiteCheckSettingRepository extends JpaRepository<SiteCheckSettingEntity, Long> {
    List<SiteCheckSettingEntity> findBySiteId(Long siteId);

    Optional<SiteCheckSettingEntity> findBySiteIdAndCheckType(Long siteId, CheckType checkType);

    void deleteBySiteId(Long siteId);
}
```

`catalog/SiteForm.java` and `catalog/SiteSummary.java`:

```java
package dev.hendrikhoemberg.webtesthelper.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.time.Duration;
import java.util.List;

public record SiteForm(
        @NotBlank String name,
        @NotBlank String baseUrl,
        @Positive int maxPages,
        int maxDepth,
        Duration maxDuration,
        List<String> includePatterns,
        List<String> excludePatterns,
        boolean respectRobots,
        String userAgent) {
}
```

```java
package dev.hendrikhoemberg.webtesthelper.catalog;

import java.util.List;

public record SiteSummary(long id, String name, String baseUrl, boolean enabled,
                          List<String> enabledCheckNames) {
}
```

`catalog/SiteService.java`:

```java
package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.*;
import dev.hendrikhoemberg.webtesthelper.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class SiteService {

    /** Ships disabled, deliberately: both are noisy on real sites (spec 7.1). */
    private static final Set<CheckType> DISABLED_BY_DEFAULT =
            EnumSet.of(CheckType.CONSOLE_ERRORS, CheckType.SITEMAP_CONSISTENCY);

    private final SiteRepository sites;
    private final SiteCheckSettingRepository settings;

    public SiteService(SiteRepository sites, SiteCheckSettingRepository settings) {
        this.sites = sites;
        this.settings = settings;
    }

    public long create(SiteForm form) {
        SiteEntity entity = new SiteEntity();
        apply(form, entity);
        Long id = sites.save(entity).getId();
        for (CheckType type : CheckType.values()) {
            SiteCheckSettingEntity setting = new SiteCheckSettingEntity();
            setting.setSiteId(id);
            setting.setCheckType(type);
            setting.setEnabled(!DISABLED_BY_DEFAULT.contains(type));
            settings.save(setting);
        }
        return id;
    }

    public void update(long id, SiteForm form) {
        apply(form, require(id));
    }

    public void delete(long id) {
        settings.deleteBySiteId(id);
        sites.deleteById(id);
    }

    public void setCheckEnabled(long siteId, CheckType type, boolean enabled) {
        SiteCheckSettingEntity setting = settings.findBySiteIdAndCheckType(siteId, type)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unbekannte Prüfung " + type + " für Website " + siteId));
        setting.setEnabled(enabled);
    }

    @Transactional(readOnly = true)
    public SiteContext contextFor(long id) {
        SiteEntity site = require(id);
        Map<CheckType, CheckSetting> checkSettings = new EnumMap<>(CheckType.class);
        for (SiteCheckSettingEntity setting : settings.findBySiteId(id)) {
            checkSettings.put(setting.getCheckType(), new CheckSetting(
                    setting.isEnabled(), setting.getSeverityOverride(), setting.getConfig()));
        }
        return new SiteContext(
                site.getId(),
                site.getName(),
                UrlNormalizer.normalize(site.getBaseUrl()).orElseThrow(
                        () -> new IllegalStateException("Ungültige Basis-URL: " + site.getBaseUrl())),
                new CrawlBudget(site.getMaxPages(), site.getMaxDepth(),
                        Duration.ofSeconds(site.getMaxDurationSeconds())),
                site.getIncludePatterns(),
                site.getExcludePatterns(),
                site.getPinnedKeyPages(),
                site.isRespectRobots(),
                site.getUserAgent(),
                checkSettings);
    }

    @Transactional(readOnly = true)
    public List<SiteSummary> summaries() {
        return sites.findAllByOrderByNameAsc().stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public SiteSummary summary(long id) {
        return toSummary(require(id));
    }

    private SiteSummary toSummary(SiteEntity site) {
        List<String> enabled = settings.findBySiteId(site.getId()).stream()
                .filter(SiteCheckSettingEntity::isEnabled)
                .map(s -> s.getCheckType().name())
                .sorted()
                .toList();
        return new SiteSummary(site.getId(), site.getName(), site.getBaseUrl(), site.isEnabled(), enabled);
    }

    private void apply(SiteForm form, SiteEntity entity) {
        NormalizedUrl base = UrlNormalizer.normalize(form.baseUrl()).orElseThrow(
                () -> new IllegalArgumentException("Keine gültige http(s)-Adresse: " + form.baseUrl()));
        entity.setName(form.name().trim());
        entity.setBaseUrl(base.value());
        entity.setMaxPages(form.maxPages());
        entity.setMaxDepth(form.maxDepth());
        entity.setMaxDurationSeconds((int) form.maxDuration().toSeconds());
        entity.setIncludePatterns(new ArrayList<>(form.includePatterns()));
        entity.setExcludePatterns(new ArrayList<>(form.excludePatterns()));
        entity.setRespectRobots(form.respectRobots());
        entity.setUserAgent(form.userAgent());
        entity.setUpdatedAt(Instant.now());
    }

    private SiteEntity require(long id) {
        return sites.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Website " + id + " existiert nicht"));
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw test -Dtest=SiteServiceTest,FlywayMigrationTest,ModularityTest`
Expected: PASS. If `FlywayMigrationTest` fails with a Hibernate validation error naming
`include_patterns`, the `@JdbcTypeCode(SqlTypes.JSON)` annotation is missing — Hibernate
would otherwise expect a `varchar` column.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration src/main/java/dev/hendrikhoemberg/webtesthelper/{model,catalog} src/test/java/dev/hendrikhoemberg/webtesthelper/catalog
git commit -m "feat(catalog): add Site and SiteCheckSetting with SiteContext projection

CONSOLE_ERRORS and SITEMAP_CONSISTENCY are created disabled (spec 7.1)."
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
- Consumes: `model.*`, `catalog.SiteService`.
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

- [ ] **Step 5: Write the run entity and repositories**

`runner/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Runner",
        allowedDependencies = {"model", "catalog", "crawler", "checks", "findings"})
package dev.hendrikhoemberg.webtesthelper.runner;
```

> `crawler`, `checks` and `findings` do not exist yet. Add each name to this array in the
> task that creates the module (5, 8 and 16 respectively) — Spring Modulith fails
> `ModularityTest` if `allowedDependencies` names a module that is not on the classpath.
> For now write `allowedDependencies = {"model", "catalog"}`.

`runner/persistence/RunEntity.java`:

```java
package dev.hendrikhoemberg.webtesthelper.runner.persistence;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "run")
@Getter
@Setter
@NoArgsConstructor
public class RunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private RunTrigger triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunScope scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status = RunStatus.QUEUED;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "lease_owner")
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "pages_visited", nullable = false)
    private int pagesVisited;

    @Column(name = "pages_failed", nullable = false)
    private int pagesFailed;

    @Column(name = "findings_total", nullable = false)
    private int findingsTotal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "covered_check_types", nullable = false)
    private List<String> coveredCheckTypes = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "covered_urls", nullable = false)
    private List<String> coveredUrls = new ArrayList<>();

    @Column(name = "partial_coverage", nullable = false)
    private boolean partialCoverage;

    @Column(name = "budget_stop_reason")
    private String budgetStopReason;

    @Column(name = "soft404_simhash")
    private Long soft404Simhash;

    @Column(name = "soft404_status")
    private Integer soft404Status;

    @Column(name = "soft404_text_length")
    private Integer soft404TextLength;

    @Column(name = "baseline_accepted_at")
    private Instant baselineAcceptedAt;

    @Column(name = "error_message")
    private String errorMessage;

    @Version
    private long version;
}
```

`runner/persistence/RunRepository.java`:

```java
package dev.hendrikhoemberg.webtesthelper.runner.persistence;

import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RunRepository extends JpaRepository<RunEntity, Long> {

    List<RunEntity> findBySiteIdOrderByQueuedAtDesc(Long siteId, Limit limit);

    Optional<RunEntity> findFirstBySiteIdAndStatusOrderByQueuedAtAsc(Long siteId, RunStatus status);

    /** The previous completed run of the same site — the diff's baseline (spec 6.3). */
    Optional<RunEntity> findFirstBySiteIdAndStatusAndIdLessThanOrderByIdDesc(
            Long siteId, RunStatus status, Long beforeRunId);
}
```

`runner/RunLease.java` and `runner/WorkerIdentity.java`:

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

- [ ] **Step 7: Write `RunService` and `RunSummary`**

```java
package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;

import java.time.Instant;
import java.util.List;

public record RunSummary(long id, long siteId, RunStatus status, RunTrigger trigger, RunScope scope,
                         Instant queuedAt, Instant startedAt, Instant finishedAt,
                         int pagesVisited, int pagesFailed, int findingsTotal,
                         boolean partialCoverage, String budgetStopReason,
                         boolean baselineAccepted, String errorMessage,
                         List<String> coveredCheckTypes) {
}
```

```java
package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunEntity;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class RunService {

    private final RunRepository runs;

    public RunService(RunRepository runs) {
        this.runs = runs;
    }

    /**
     * Queues a run. If one is already queued for this site, its id is returned instead of
     * a second one being created — clicking "Jetzt prüfen" twice must not build a backlog.
     */
    public long enqueue(long siteId, RunTrigger trigger, RunScope scope) {
        return runs.findFirstBySiteIdAndStatusOrderByQueuedAtAsc(siteId, RunStatus.QUEUED)
                .map(RunEntity::getId)
                .orElseGet(() -> {
                    RunEntity run = new RunEntity();
                    run.setSiteId(siteId);
                    run.setTriggerType(trigger);
                    run.setScope(scope);
                    run.setStatus(RunStatus.QUEUED);
                    return runs.save(run).getId();
                });
    }

    @Transactional(readOnly = true)
    public List<RunSummary> recentForSite(long siteId, int limit) {
        return runs.findBySiteIdOrderByQueuedAtDesc(siteId, Limit.of(limit)).stream()
                .map(RunService::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public RunSummary summary(long runId) {
        return toSummary(runs.findById(runId).orElseThrow(
                () -> new IllegalArgumentException("Lauf " + runId + " existiert nicht")));
    }

    static RunSummary toSummary(RunEntity run) {
        return new RunSummary(run.getId(), run.getSiteId(), run.getStatus(), run.getTriggerType(),
                run.getScope(), run.getQueuedAt(), run.getStartedAt(), run.getFinishedAt(),
                run.getPagesVisited(), run.getPagesFailed(), run.getFindingsTotal(),
                run.isPartialCoverage(), run.getBudgetStopReason(),
                run.getBaselineAcceptedAt() != null, run.getErrorMessage(),
                List.copyOf(run.getCoveredCheckTypes()));
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./mvnw test -Dtest=RunLeaseJdbcRepositoryTest`
Expected: PASS, all nine cases.

If `onlyOneRunPerSiteIsEverRunning` fails with a `DuplicateKeyException` escaping instead
of being swallowed, the catch in `claimNext` is missing or catching the wrong type —
Spring translates Postgres SQLSTATE 23505 to `DuplicateKeyException`.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/db/migration/V4__run.sql src/main/java/dev/hendrikhoemberg/webtesthelper/{model,runner} src/test/java/dev/hendrikhoemberg/webtesthelper/runner
git commit -m "feat(runner): add Run entity and lease-based job queue

Claim uses SELECT ... FOR UPDATE SKIP LOCKED; one-run-per-site is enforced
by a partial unique index because the NOT EXISTS guard is racy under READ
COMMITTED."
```

---

### Task 5: Crawl frontier with batched claim and complete

The frontier is a table, not an in-memory queue (§14) — that is what buys live progress and
resumability. But it must not cost one statement per URL: workers **claim 20 URLs in one
statement and report 20 completions in one statement**, so 15,000 pages cost hundreds of
statements rather than tens of thousands (§6.5).

The completion statement is the non-obvious one. `JdbcTemplate.batchUpdate` would send
15,000 separate statements in one round trip — better than 15,000 round trips, but still
15,000 statements. Joining against an inline `VALUES` list is what actually collapses the
statement count.

**Files:**
- Create: `src/main/resources/db/migration/V5__crawl_queue_item.sql`
- Create: `src/main/java/.../crawler/package-info.java`
- Create: `src/main/java/.../crawler/CrawlItemState.java`
- Create: `src/main/java/.../crawler/FrontierCandidate.java`
- Create: `src/main/java/.../crawler/CrawlQueueItem.java`
- Create: `src/main/java/.../crawler/CrawlItemResult.java`
- Create: `src/main/java/.../crawler/FrontierStats.java`
- Create: `src/main/java/.../crawler/CrawlFrontier.java`
- Create: `src/main/java/.../crawler/UrlScope.java`
- Modify: `src/main/java/.../runner/package-info.java` (add `"crawler"`)
- Test: `src/test/java/.../crawler/CrawlFrontierTest.java`
- Test: `src/test/java/.../crawler/UrlScopeTest.java`

**Interfaces:**
- Consumes: `model.NormalizedUrl`, `model.SiteContext`, `model.UrlNormalizer`.
- Produces:
  - `CrawlFrontier.enqueue(long runId, List<FrontierCandidate>) -> int`
  - `CrawlFrontier.claimBatch(long runId, String owner, int batchSize) -> List<CrawlQueueItem>`
  - `CrawlFrontier.completeBatch(long runId, List<CrawlItemResult>) -> void`
  - `CrawlFrontier.stats(long runId) -> FrontierStats`
  - `CrawlFrontier.visitedUrls(long runId) -> List<String>`
  - `CrawlFrontier.releaseStaleClaims(long runId, Duration olderThan) -> int`
  - `UrlScope.forSite(SiteContext) -> UrlScope`, `UrlScope.evaluate(NormalizedUrl, int depth) -> UrlScope.Decision`

- [ ] **Step 1: Write the failing tests**

`src/test/java/.../crawler/UrlScopeTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UrlScopeTest {

    private static SiteContext site(List<String> include, List<String> exclude, int maxDepth) {
        return new SiteContext(1L, "Test",
                UrlNormalizer.normalize("https://example.com/").orElseThrow(),
                new CrawlBudget(300, maxDepth, Duration.ofMinutes(30)),
                include, exclude, List.of(), true, null, Map.of());
    }

    private static NormalizedUrl url(String raw) {
        return UrlNormalizer.normalize(raw).orElseThrow();
    }

    @Test
    void acceptsAnInternalUrlWithinDepth() {
        UrlScope scope = UrlScope.forSite(site(List.of(), List.of(), 3));
        assertThat(scope.evaluate(url("https://example.com/leistungen"), 2))
                .isEqualTo(UrlScope.Decision.ACCEPT);
    }

    @Test
    void treatsTheWwwHostAsTheSameSite() {
        UrlScope scope = UrlScope.forSite(site(List.of(), List.of(), 3));
        assertThat(scope.evaluate(url("https://www.example.com/a"), 1))
                .isEqualTo(UrlScope.Decision.ACCEPT);
    }

    @Test
    void rejectsOtherHosts() {
        UrlScope scope = UrlScope.forSite(site(List.of(), List.of(), 3));
        assertThat(scope.evaluate(url("https://andere.de/a"), 1)).isEqualTo(UrlScope.Decision.OFF_SITE);
    }

    @Test
    void rejectsBeyondMaxDepth() {
        UrlScope scope = UrlScope.forSite(site(List.of(), List.of(), 2));
        assertThat(scope.evaluate(url("https://example.com/a"), 3)).isEqualTo(UrlScope.Decision.TOO_DEEP);
    }

    @Test
    void appliesGlobExcludePatterns() {
        UrlScope scope = UrlScope.forSite(site(List.of(), List.of("/intern/*", "*.zip"), 5));
        assertThat(scope.evaluate(url("https://example.com/intern/preise"), 1))
                .isEqualTo(UrlScope.Decision.EXCLUDED);
        assertThat(scope.evaluate(url("https://example.com/downloads/archiv.zip"), 1))
                .isEqualTo(UrlScope.Decision.EXCLUDED);
        assertThat(scope.evaluate(url("https://example.com/internes-schulungsvideo"), 1))
                .isEqualTo(UrlScope.Decision.ACCEPT);
    }

    @Test
    void anIncludeListTurnsIntoAnAllowList() {
        UrlScope scope = UrlScope.forSite(site(List.of("/shop/*"), List.of(), 5));
        assertThat(scope.evaluate(url("https://example.com/shop/artikel"), 1))
                .isEqualTo(UrlScope.Decision.ACCEPT);
        assertThat(scope.evaluate(url("https://example.com/blog"), 1))
                .isEqualTo(UrlScope.Decision.NOT_INCLUDED);
    }

    @Test
    void excludeBeatsInclude() {
        UrlScope scope = UrlScope.forSite(site(List.of("/shop/*"), List.of("/shop/warenkorb*"), 5));
        assertThat(scope.evaluate(url("https://example.com/shop/warenkorb"), 1))
                .isEqualTo(UrlScope.Decision.EXCLUDED);
    }
}
```

`src/test/java/.../crawler/CrawlFrontierTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class CrawlFrontierTest extends AbstractPostgresTest {

    @Autowired
    CrawlFrontier frontier;

    @Autowired
    JdbcTemplate jdbc;

    private long runId;

    @BeforeEach
    void seedRun() {
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        Long siteId = jdbc.queryForObject(
                "INSERT INTO site (name, base_url) VALUES ('T', 'https://example.com/') RETURNING id",
                Long.class);
        runId = jdbc.queryForObject("""
                INSERT INTO run (site_id, trigger_type, scope, status)
                VALUES (?, 'MANUAL', 'FULL', 'RUNNING') RETURNING id
                """, Long.class, siteId);
    }

    private static FrontierCandidate candidate(int i, int depth) {
        String url = "https://example.com/seite-" + i;
        return new FrontierCandidate(url, url, depth, "https://example.com/");
    }

    @Test
    void enqueuesABatchInOneStatementAndIgnoresDuplicates() {
        assertThat(frontier.enqueue(runId, List.of(candidate(1, 0), candidate(2, 0)))).isEqualTo(2);
        assertThat(frontier.enqueue(runId, List.of(candidate(2, 1), candidate(3, 1)))).isEqualTo(1);

        assertThat(frontier.stats(runId).pending()).isEqualTo(3);
    }

    @Test
    void enqueuingNothingIsANoOp() {
        assertThat(frontier.enqueue(runId, List.of())).isZero();
    }

    @Test
    void claimsShallowUrlsFirstAndMarksThemClaimed() {
        frontier.enqueue(runId, List.of(candidate(1, 2), candidate(2, 0), candidate(3, 1)));

        List<CrawlQueueItem> claimed = frontier.claimBatch(runId, "worker-1", 2);

        assertThat(claimed).extracting(CrawlQueueItem::depth).containsExactly(0, 1);
        assertThat(frontier.stats(runId).pending()).isEqualTo(1);
        assertThat(frontier.stats(runId).claimed()).isEqualTo(2);
    }

    @Test
    void twoWorkersClaimingConcurrentlyNeverOverlap() throws Exception {
        List<FrontierCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            candidates.add(candidate(i, 0));
        }
        frontier.enqueue(runId, candidates);

        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<List<CrawlQueueItem>>> futures = pool.invokeAll(Collections.nCopies(4,
                    () -> frontier.claimBatch(runId, "w-" + Thread.currentThread().threadId(), 10)));
            List<Long> ids = new ArrayList<>();
            for (Future<List<CrawlQueueItem>> future : futures) {
                future.get().forEach(item -> ids.add(item.id()));
            }
            assertThat(ids).hasSize(40).doesNotHaveDuplicates();
        }
    }

    @Test
    void completesAWholeBatchInOneStatement() {
        frontier.enqueue(runId, List.of(candidate(1, 0), candidate(2, 0), candidate(3, 0)));
        List<CrawlQueueItem> claimed = frontier.claimBatch(runId, "worker-1", 3);

        frontier.completeBatch(runId, List.of(
                new CrawlItemResult(claimed.get(0).id(), CrawlItemState.DONE, 200, null),
                new CrawlItemResult(claimed.get(1).id(), CrawlItemState.DONE, 404, null),
                new CrawlItemResult(claimed.get(2).id(), CrawlItemState.FAILED, null, "Timeout nach 30s")));

        FrontierStats stats = frontier.stats(runId);
        assertThat(stats.done()).isEqualTo(2);
        assertThat(stats.failed()).isEqualTo(1);
        assertThat(stats.claimed()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT error_message FROM crawl_queue_item WHERE id = ?", String.class,
                claimed.get(2).id())).isEqualTo("Timeout nach 30s");
    }

    @Test
    void coverageCountsPagesActuallyVisitedAndNotThoseThatFailedToLoad() {
        // A page that could not be loaded proves nothing about the findings recorded at it,
        // so it must stay outside coverage (spec 6.4).
        frontier.enqueue(runId, List.of(candidate(1, 0), candidate(2, 0)));
        List<CrawlQueueItem> claimed = frontier.claimBatch(runId, "worker-1", 2);
        frontier.completeBatch(runId, List.of(
                new CrawlItemResult(claimed.get(0).id(), CrawlItemState.DONE, 200, null),
                new CrawlItemResult(claimed.get(1).id(), CrawlItemState.FAILED, null, "Timeout")));

        assertThat(frontier.visitedUrls(runId)).containsExactly(claimed.get(0).normalizedUrl());
    }

    @Test
    void staleClaimsFromADeadWorkerReturnToPending() {
        frontier.enqueue(runId, List.of(candidate(1, 0)));
        frontier.claimBatch(runId, "dead-worker", 1);
        jdbc.update("UPDATE crawl_queue_item SET claimed_at = now() - interval '10 minutes'");

        assertThat(frontier.releaseStaleClaims(runId, Duration.ofMinutes(5))).isEqualTo(1);
        assertThat(frontier.stats(runId).pending()).isEqualTo(1);
    }

    @Test
    void drainedMeansNothingPendingAndNothingInFlight() {
        frontier.enqueue(runId, List.of(candidate(1, 0)));
        assertThat(frontier.stats(runId).drained()).isFalse();

        List<CrawlQueueItem> claimed = frontier.claimBatch(runId, "worker-1", 1);
        assertThat(frontier.stats(runId).drained()).isFalse();

        frontier.completeBatch(runId,
                List.of(new CrawlItemResult(claimed.getFirst().id(), CrawlItemState.DONE, 200, null)));
        assertThat(frontier.stats(runId).drained()).isTrue();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=UrlScopeTest,CrawlFrontierTest`
Expected: compilation failure — the `crawler` package does not exist.

- [ ] **Step 3: Write `V5__crawl_queue_item.sql`**

```sql
CREATE TABLE crawl_queue_item (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run (id) ON DELETE CASCADE,
    url TEXT NOT NULL,
    normalized_url TEXT NOT NULL,
    depth INTEGER NOT NULL,
    state TEXT NOT NULL DEFAULT 'PENDING',
    claimed_by TEXT,
    claimed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    discovered_from TEXT,
    http_status INTEGER,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The dedupe key. ON CONFLICT DO NOTHING on batch insert relies on it.
CREATE UNIQUE INDEX ux_cqi_run_url ON crawl_queue_item (run_id, normalized_url);

-- Supports the claim statement's ORDER BY depth, id under the run_id/state filter.
CREATE INDEX ix_cqi_claim ON crawl_queue_item (run_id, state, depth, id);
```

- [ ] **Step 4: Write the frontier value types**

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

public enum CrawlItemState { PENDING, CLAIMED, DONE, FAILED, SKIPPED }
```

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

/** A URL offered to the frontier. Rejected silently if the run already knows it. */
public record FrontierCandidate(String url, String normalizedUrl, int depth, String discoveredFrom) {
}
```

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

public record CrawlQueueItem(long id, String url, String normalizedUrl, int depth) {
}
```

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

public record CrawlItemResult(long id, CrawlItemState state, Integer httpStatus, String errorMessage) {
}
```

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

public record FrontierStats(int pending, int claimed, int done, int failed, int skipped) {

    public int total() {
        return pending + claimed + done + failed + skipped;
    }

    public int finished() {
        return done + failed + skipped;
    }

    /** Nothing left to hand out and nothing still in a worker's hands. */
    public boolean drained() {
        return pending == 0 && claimed == 0;
    }
}
```

- [ ] **Step 5: Write `UrlScope`**

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides whether a discovered URL belongs in the frontier. Pure — the crawl loop consults
 * it before enqueuing, so a rejected URL never costs a database round trip.
 */
public final class UrlScope {

    public enum Decision {
        ACCEPT, OFF_SITE, TOO_DEEP, EXCLUDED, NOT_INCLUDED;

        public boolean accepted() {
            return this == ACCEPT;
        }
    }

    private final NormalizedUrl base;
    private final List<Pattern> include;
    private final List<Pattern> exclude;
    private final int maxDepth;

    private UrlScope(NormalizedUrl base, List<Pattern> include, List<Pattern> exclude, int maxDepth) {
        this.base = base;
        this.include = include;
        this.exclude = exclude;
        this.maxDepth = maxDepth;
    }

    public static UrlScope forSite(SiteContext site) {
        return new UrlScope(site.baseUrl(),
                compile(site.includePatterns()),
                compile(site.excludePatterns()),
                site.budget().maxDepth());
    }

    public Decision evaluate(NormalizedUrl candidate, int depth) {
        if (!UrlNormalizer.isSameSite(base, candidate)) {
            return Decision.OFF_SITE;
        }
        if (depth > maxDepth) {
            return Decision.TOO_DEEP;
        }
        String path = candidate.locationKey();
        if (matchesAny(exclude, path)) {
            return Decision.EXCLUDED;   // exclude always beats include
        }
        if (!include.isEmpty() && !matchesAny(include, path)) {
            return Decision.NOT_INCLUDED;
        }
        return Decision.ACCEPT;
    }

    private static boolean matchesAny(List<Pattern> patterns, String path) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    private static List<Pattern> compile(List<String> globs) {
        return globs.stream().filter(g -> !g.isBlank()).map(UrlScope::globToRegex).toList();
    }

    /**
     * Employees write {@code /intern/*}, not {@code ^/intern/.*$}. {@code *} matches any run
     * of characters, {@code ?} matches one; everything else is literal.
     */
    static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                }
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }
}
```

- [ ] **Step 6: Write `CrawlFrontier`**

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The crawl frontier (spec 6.5, 14). Deliberately JdbcTemplate and not JPA: this table sees
 * tens of thousands of rows per run and every operation is a set operation.
 *
 * <p>Batching is the point. {@link #enqueue} is one multi-row INSERT, {@link #claimBatch}
 * one UPDATE ... RETURNING over a SKIP LOCKED subquery, and {@link #completeBatch} one
 * UPDATE joined against an inline VALUES list.
 */
@Component
public class CrawlFrontier {

    /** Default claim size. 20 URLs per statement, per spec 6.5. */
    public static final int DEFAULT_BATCH_SIZE = 20;

    private static final String CLAIM_SQL = """
            UPDATE crawl_queue_item AS q
               SET state      = 'CLAIMED',
                   claimed_by = ?,
                   claimed_at = now()
             WHERE q.id IN (SELECT c.id
                              FROM crawl_queue_item c
                             WHERE c.run_id = ? AND c.state = 'PENDING'
                             ORDER BY c.depth, c.id
                             LIMIT ?
                             FOR UPDATE SKIP LOCKED)
         RETURNING q.id, q.url, q.normalized_url, q.depth
            """;

    private static final String STATS_SQL = """
            SELECT count(*) FILTER (WHERE state = 'PENDING') AS pending,
                   count(*) FILTER (WHERE state = 'CLAIMED') AS claimed,
                   count(*) FILTER (WHERE state = 'DONE')    AS done,
                   count(*) FILTER (WHERE state = 'FAILED')  AS failed,
                   count(*) FILTER (WHERE state = 'SKIPPED') AS skipped
              FROM crawl_queue_item
             WHERE run_id = ?
            """;

    private static final String VISITED_SQL = """
            SELECT normalized_url FROM crawl_queue_item
             WHERE run_id = ? AND state = 'DONE'
             ORDER BY id
            """;

    private static final String RELEASE_STALE_SQL = """
            UPDATE crawl_queue_item
               SET state = 'PENDING', claimed_by = NULL, claimed_at = NULL
             WHERE run_id = ? AND state = 'CLAIMED'
               AND claimed_at < now() - make_interval(secs => ?)
            """;

    private static final RowMapper<CrawlQueueItem> ITEM_MAPPER = (rs, row) -> new CrawlQueueItem(
            rs.getLong("id"), rs.getString("url"), rs.getString("normalized_url"), rs.getInt("depth"));

    private final JdbcTemplate jdbc;

    public CrawlFrontier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One INSERT for the whole batch. Returns how many were genuinely new. */
    public int enqueue(long runId, List<FrontierCandidate> candidates) {
        if (candidates.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("""
                INSERT INTO crawl_queue_item (run_id, url, normalized_url, depth, state, discovered_from)
                VALUES """);
        List<Object> args = new ArrayList<>(candidates.size() * 5);
        for (int i = 0; i < candidates.size(); i++) {
            sql.append(i == 0 ? " " : ", ").append("(?, ?, ?, ?, 'PENDING', ?)");
            FrontierCandidate candidate = candidates.get(i);
            args.add(runId);
            args.add(candidate.url());
            args.add(candidate.normalizedUrl());
            args.add(candidate.depth());
            args.add(candidate.discoveredFrom());
        }
        sql.append(" ON CONFLICT (run_id, normalized_url) DO NOTHING");
        return jdbc.update(sql.toString(), args.toArray());
    }

    public List<CrawlQueueItem> claimBatch(long runId, String owner, int batchSize) {
        return jdbc.query(CLAIM_SQL, ITEM_MAPPER, owner, runId, batchSize);
    }

    /**
     * One UPDATE for the whole batch, joined against an inline VALUES list. The casts on the
     * first placeholder of each column are required: Postgres has no other way to infer the
     * column types of a bare VALUES list used as a relation.
     */
    public void completeBatch(long runId, List<CrawlItemResult> results) {
        if (results.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("""
                UPDATE crawl_queue_item AS q
                   SET state        = v.state,
                       http_status  = v.http_status,
                       error_message = v.error_message,
                       completed_at = now(),
                       claimed_by   = NULL
                  FROM (VALUES """);
        List<Object> args = new ArrayList<>(results.size() * 4 + 1);
        for (int i = 0; i < results.size(); i++) {
            sql.append(i == 0 ? " (?::bigint, ?::text, ?::int, ?::text)" : ", (?, ?, ?, ?)");
            CrawlItemResult result = results.get(i);
            args.add(result.id());
            args.add(result.state().name());
            args.add(result.httpStatus());
            args.add(result.errorMessage());
        }
        sql.append(") AS v(id, state, http_status, error_message) WHERE q.id = v.id AND q.run_id = ?");
        args.add(runId);
        jdbc.update(sql.toString(), args.toArray());
    }

    public FrontierStats stats(long runId) {
        return jdbc.queryForObject(STATS_SQL, (rs, row) -> new FrontierStats(
                rs.getInt("pending"), rs.getInt("claimed"), rs.getInt("done"),
                rs.getInt("failed"), rs.getInt("skipped")), runId);
    }

    /**
     * The URLs this run actually loaded — the {@code visitedUrls} half of run coverage
     * (spec 6.4). FAILED pages are excluded on purpose: a page we could not load proves
     * nothing about the findings recorded at it, so resolving them would be a lie.
     */
    public List<String> visitedUrls(long runId) {
        return jdbc.queryForList(VISITED_SQL, String.class, runId);
    }

    public int releaseStaleClaims(long runId, Duration olderThan) {
        return jdbc.update(RELEASE_STALE_SQL, runId, (double) olderThan.toSeconds());
    }
}
```

`crawler/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Crawler",
        allowedDependencies = {"model"})
package dev.hendrikhoemberg.webtesthelper.crawler;
```

- [ ] **Step 7: Add `"crawler"` to the runner's allowed dependencies**

In `runner/package-info.java`, change `allowedDependencies` to
`{"model", "catalog", "crawler"}`.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=UrlScopeTest,CrawlFrontierTest,ModularityTest`
Expected: PASS.

If `completesAWholeBatchInOneStatement` fails with "column v.http_status is of type text",
the `::int` cast on the first tuple is missing — Postgres types the whole VALUES column
from its first row.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/db/migration/V5__crawl_queue_item.sql src/main/java/dev/hendrikhoemberg/webtesthelper/{crawler,runner} src/test/java/dev/hendrikhoemberg/webtesthelper/crawler
git commit -m "feat(crawler): add batched crawl frontier and URL scope

Claim, complete and enqueue are one statement per batch of 20 (spec 6.5).
Coverage counts DONE pages only; a page that failed to load proves nothing."
```

---

### Task 6: The fixture site harness

*"The fixture site is the highest-value asset in the project"* (§15). Every check from
Task 9 onward is developed against it with a real Chromium, and a false positive found in
production must be reproducible here in seconds. Nothing in CI ever touches a real
customer site.

It is served by `com.sun.net.httpserver.HttpServer` on an ephemeral port — no dependency,
no container, and reachable from the Chromium that Playwright launches in the same
machine. Static pages come from `src/test/resources/fixture-site/`; the failure modes that
need real HTTP behaviour (status codes, redirects, content types, magic bytes) are
dynamic handlers.

**Files:**
- Create: `src/test/java/.../support/FixtureSite.java`
- Create: `src/test/resources/fixture-site/index.html`
- Create: `src/test/resources/fixture-site/bilder.html`
- Create: `src/test/resources/fixture-site/tote-links.html`
- Create: `src/test/resources/fixture-site/downloads.html`
- Create: `src/test/resources/fixture-site/medien.html`
- Create: `src/test/resources/fixture-site/karte.html`
- Create: `src/test/resources/fixture-site/weiterleitungen.html`
- Create: `src/test/resources/fixture-site/gemischt.html`
- Create: `src/test/resources/fixture-site/konsole.html`
- Create: `src/test/resources/fixture-site/kontakt.html`
- Create: `src/test/resources/fixture-site/de/index.html`
- Create: `src/test/resources/fixture-site/en/index.html`
- Create: `src/test/resources/fixture-site/sitemap.xml`
- Create: `src/test/resources/fixture-site/robots.txt`
- Test: `src/test/java/.../support/FixtureSiteTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `FixtureSite` — a JUnit 5 `@RegisterExtension` resource with
  `baseUrl() -> String` (e.g. `http://127.0.0.1:41234/`) and `url(String path) -> String`.

- [ ] **Step 1: Write the failing test**

```java
package dev.hendrikhoemberg.webtesthelper.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureSiteTest {

    @RegisterExtension
    static final FixtureSite SITE = new FixtureSite();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private static HttpResponse<byte[]> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(SITE.url(path))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void servesTheStaticStartPage() throws Exception {
        HttpResponse<byte[]> response = get("/index.html");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("Musterfirma");
    }

    @Test
    void missingImageIsAHardFourOhFour() throws Exception {
        assertThat(get("/bilder/fehlt.png").statusCode()).isEqualTo(404);
    }

    @Test
    void unknownPathsAnswerTwoHundredWithANotFoundPage() throws Exception {
        // The soft 404: the failure mode the random-uuid probe of spec 7.1 exists to catch.
        HttpResponse<byte[]> response = get("/gibt-es-nicht-" + java.util.UUID.randomUUID());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("Seite nicht gefunden");
    }

    @Test
    void realPdfHasThePdfMagicBytesAndTheRightContentType() throws Exception {
        HttpResponse<byte[]> response = get("/dokumente/handbuch.pdf");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type")).hasValue("application/pdf");
        assertThat(new String(response.body(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(response.body().length).isGreaterThan(1024);
    }

    @Test
    void theFakePdfIsAnHtmlLoginWall() throws Exception {
        HttpResponse<byte[]> response = get("/dokumente/preisliste.pdf");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type")).hasValue("text/html; charset=utf-8");
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("Bitte anmelden");
    }

    @Test
    void redirectChainIsThreeHopsAndTheLoopBouncesForever() throws Exception {
        assertThat(get("/weiterleitung/1").statusCode()).isEqualTo(302);
        assertThat(get("/weiterleitung/1").headers().firstValue("location")).hasValue("/weiterleitung/2");
        assertThat(get("/weiterleitung/3").headers().firstValue("location")).hasValue("/weiterleitung/ziel");
        assertThat(get("/weiterleitung/ziel").statusCode()).isEqualTo(200);

        assertThat(get("/schleife/a").headers().firstValue("location")).hasValue("/schleife/b");
        assertThat(get("/schleife/b").headers().firstValue("location")).hasValue("/schleife/a");
    }

    @Test
    void servesAPlayableAudioFileAndAMissingOne() throws Exception {
        HttpResponse<byte[]> wav = get("/medien/ton.wav");
        assertThat(wav.statusCode()).isEqualTo(200);
        assertThat(new String(wav.body(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("RIFF");
        assertThat(get("/medien/fehlt.mp3").statusCode()).isEqualTo(404);
    }

    @Test
    void servesARealPngForTheWorkingImage() throws Exception {
        HttpResponse<byte[]> png = get("/bilder/logo.png");
        assertThat(png.statusCode()).isEqualTo(200);
        assertThat(png.headers().firstValue("content-type")).hasValue("image/png");
    }

    @Test
    void servesSitemapAndRobots() throws Exception {
        assertThat(new String(get("/sitemap.xml").body(), StandardCharsets.UTF_8))
                .contains("/leistungen").contains("/gibt-es-nicht-laut-sitemap");
        assertThat(new String(get("/robots.txt").body(), StandardCharsets.UTF_8))
                .contains("Disallow: /intern/");
    }

    @Test
    void anExplicitFourOhFourPathReturnsFourOhFour() throws Exception {
        assertThat(get("/echte-404").statusCode()).isEqualTo(404);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=FixtureSiteTest`
Expected: compilation failure — `FixtureSite` does not exist.

- [ ] **Step 3: Write `FixtureSite`**

```java
package dev.hendrikhoemberg.webtesthelper.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * The fixture site (spec 15): one static site containing one of every failure mode on the
 * manual checklist, served on an ephemeral loopback port.
 *
 * <p>Register it per test class:
 * {@code @RegisterExtension static final FixtureSite SITE = new FixtureSite();}
 */
public final class FixtureSite implements BeforeAllCallback, AfterAllCallback {

    /** 1x1 opaque PNG — small, but with a real naturalWidth so IMAGE_BROKEN passes it. */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "xml", "application/xml; charset=utf-8",
            "txt", "text/plain; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8",
            "png", "image/png");

    private HttpServer server;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::dispatch);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (server != null) {
            server.stop(0);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    public String url(String path) {
        return baseUrl() + (path.startsWith("/") ? path.substring(1) : path);
    }

    // ------------------------------------------------------------------ routing

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            switch (path) {
                case "/", "" -> serveResource(exchange, "index.html");

                // Hard 404s.
                case "/bilder/fehlt.png", "/medien/fehlt.mp3", "/echte-404",
                     "/gibt-es-nicht-laut-sitemap" -> notFound(exchange);

                // Working assets.
                case "/bilder/logo.png", "/bilder/logo@2x.png", "/bilder/hintergrund.png" ->
                        respond(exchange, 200, "image/png", PNG);
                case "/medien/ton.wav" -> respond(exchange, 200, "audio/wav", wav(1200));

                // A real PDF, and an HTML login wall wearing a .pdf extension (spec 7.1).
                case "/dokumente/handbuch.pdf" -> respond(exchange, 200, "application/pdf", pdf());
                case "/dokumente/preisliste.pdf" -> respond(exchange, 200, "text/html; charset=utf-8",
                        html("Preisliste", "<h1>Bitte anmelden</h1><p>Zugriff nur für Kunden.</p>"));

                // Redirect chain and redirect loop.
                case "/weiterleitung/1" -> redirect(exchange, "/weiterleitung/2");
                case "/weiterleitung/2" -> redirect(exchange, "/weiterleitung/3");
                case "/weiterleitung/3" -> redirect(exchange, "/weiterleitung/ziel");
                case "/weiterleitung/ziel" -> respond(exchange, 200, "text/html; charset=utf-8",
                        html("Ziel", "<h1>Angekommen</h1>"));
                case "/schleife/a" -> redirect(exchange, "/schleife/b");
                case "/schleife/b" -> redirect(exchange, "/schleife/a");

                // The Google-Maps stand-in: an iframe that paints nothing and logs the
                // provider's billing error to the console (spec 7.1).
                case "/karte-embed" -> respond(exchange, 200, "text/html; charset=utf-8",
                        html("Karte", """
                                <div id="map" style="width:100%;height:300px;background:#e5e3df"></div>
                                <script>console.error('ApiNotActivatedMapError: Google Maps JavaScript API '
                                    + 'error. For development purposes only');</script>
                                """));
                case "/karte-embed-ok" -> respond(exchange, 200, "text/html; charset=utf-8",
                        html("Karte", "<canvas width='400' height='300'></canvas>"));

                default -> serveOrSoftNotFound(exchange, path);
            }
        } catch (RuntimeException e) {
            respond(exchange, 500, "text/plain; charset=utf-8",
                    ("Fixture-Fehler: " + e).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Anything not matched above is looked up as a static resource; if there is none, the
     * site answers 200 with a not-found page. That is the soft 404 the runner's random-uuid
     * probe has to learn.
     */
    private void serveOrSoftNotFound(HttpExchange exchange, String path) throws IOException {
        String resource = path.substring(1);
        if (resource.isEmpty()) {
            resource = "index.html";
        } else if (!resource.contains(".")) {
            resource = resource + ".html";
        }
        InputStream stream = FixtureSite.class.getResourceAsStream("/fixture-site/" + resource);
        if (stream == null) {
            respond(exchange, 200, "text/html; charset=utf-8", html("Seite nicht gefunden", """
                    <h1>Seite nicht gefunden</h1>
                    <p>Die gewünschte Seite existiert leider nicht. Zurück zur
                    <a href="/">Startseite</a>.</p>
                    """));
            return;
        }
        try (stream) {
            respond(exchange, 200, contentTypeOf(resource), stream.readAllBytes());
        }
    }

    private void serveResource(HttpExchange exchange, String resource) throws IOException {
        try (InputStream stream = FixtureSite.class.getResourceAsStream("/fixture-site/" + resource)) {
            if (stream == null) {
                notFound(exchange);
                return;
            }
            respond(exchange, 200, contentTypeOf(resource), stream.readAllBytes());
        }
    }

    private static String contentTypeOf(String resource) {
        int dot = resource.lastIndexOf('.');
        String extension = dot < 0 ? "" : resource.substring(dot + 1);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void notFound(HttpExchange exchange) throws IOException {
        respond(exchange, 404, "text/html; charset=utf-8",
                html("404", "<h1>404 – nicht vorhanden</h1>"));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] html(String title, String body) {
        return ("""
                <!doctype html><html lang="de"><head><meta charset="utf-8">
                <title>%s</title></head><body>%s</body></html>
                """.formatted(title, body)).getBytes(StandardCharsets.UTF_8);
    }

    /** 8-bit mono PCM sine wave — small, and Chromium reports a real duration for it. */
    private static byte[] wav(int millis) {
        int sampleRate = 8000;
        int sampleCount = sampleRate * millis / 1000;
        byte[] samples = new byte[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = (byte) (128 + 40 * Math.sin(2 * Math.PI * 440 * i / sampleRate));
        }
        ByteBuffer buffer = ByteBuffer.allocate(44 + samples.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples.length);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        buffer.putShort((short) 1).putShort((short) 1).putInt(sampleRate).putInt(sampleRate)
                .putShort((short) 1).putShort((short) 8);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples.length).put(samples);
        return buffer.array();
    }

    /**
     * A PDF with the right magic bytes, a plausible object graph and enough padding to clear
     * the "non-trivial size" rule of FILE_DOWNLOAD (spec 7.1).
     */
    private static byte[] pdf() {
        String body = """
                %PDF-1.4
                1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
                2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
                3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]>>endobj
                trailer<</Root 1 0 R/Size 4>>
                """
                + ("% Fülltext, damit die Datei die Mindestgröße überschreitet.\n".repeat(20))
                + "%%EOF\n";
        return body.getBytes(StandardCharsets.ISO_8859_1);
    }
}
```

- [ ] **Step 4: Write the static fixture pages**

`index.html` — the hub every other page hangs off, so a crawl reaches everything:

```html
<!doctype html>
<html lang="de">
<head>
    <meta charset="utf-8">
    <title>Musterfirma – Startseite</title>
    <link rel="alternate" hreflang="de" href="/de/index.html">
    <link rel="alternate" hreflang="en" href="/en/index.html">
</head>
<body>
<h1>Musterfirma</h1>
<nav>
    <a href="/leistungen">Leistungen</a>
    <a href="/kontakt">Kontakt</a>
    <a href="/bilder">Bilder</a>
    <a href="/tote-links">Tote Links</a>
    <a href="/downloads">Downloads</a>
    <a href="/medien">Medien</a>
    <a href="/karte">Karte</a>
    <a href="/weiterleitungen">Weiterleitungen</a>
    <a href="/gemischt">Gemischte Inhalte</a>
    <a href="/konsole">Konsole</a>
    <a href="/de/index.html">Deutsch</a>
    <a href="/en/index.html">English</a>
</nav>
<img src="/bilder/logo.png" alt="Logo">
</body>
</html>
```

`leistungen.html` — a plain healthy page, so "no findings" has a witness:

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Leistungen</title></head>
<body><h1>Leistungen</h1><p>Beratung, Planung, Umsetzung.</p>
<a href="/">Zurück</a></body>
</html>
```

`bilder.html` — the three image origins of `IMAGE_BROKEN` (§7.1):

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Bilder</title>
<style>.held { width:100px; height:100px; background-image:url('/bilder/hintergrund.png'); }
       .kaputt { width:100px; height:100px; background-image:url('/bilder/fehlt.png'); }</style>
</head>
<body>
<h1>Bilder</h1>
<img id="ok" src="/bilder/logo.png" alt="Funktionierendes Logo">
<img id="kaputt" src="/bilder/fehlt.png" alt="Fehlendes Bild">
<img id="srcset" src="/bilder/logo.png" srcset="/bilder/logo.png 1x, /bilder/fehlt.png 2x" alt="Mit srcset">
<div class="held"></div>
<div class="kaputt"></div>
<a href="/">Zurück</a>
</body>
</html>
```

`tote-links.html`:

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Tote Links</title></head>
<body>
<h1>Verweise</h1>
<a href="/leistungen">Interner Link, funktioniert</a>
<a href="/echte-404">Interner Link, 404</a>
<a href="/gibt-es-nicht-mehr">Interner Link, weicher 404</a>
<a href="mailto:info@musterfirma.de">E-Mail</a>
<a href="tel:+493012345">Telefon</a>
<a href="#oben">Sprungmarke</a>
<a href="/">Zurück</a>
</body>
</html>
```

`downloads.html`:

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Downloads</title></head>
<body>
<h1>Downloads</h1>
<a href="/dokumente/handbuch.pdf">Handbuch (PDF)</a>
<a href="/dokumente/preisliste.pdf">Preisliste (PDF)</a>
<a href="/">Zurück</a>
</body>
</html>
```

`medien.html`:

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Medien</title></head>
<body>
<h1>Medien</h1>
<audio id="funktioniert" controls preload="metadata"><source src="/medien/ton.wav" type="audio/wav"></audio>
<video id="kaputt" controls preload="metadata"><source src="/medien/fehlt.mp3" type="video/mp4"></video>
<a href="/">Zurück</a>
</body>
</html>
```

`karte.html`:

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Karte</title></head>
<body>
<h1>Anfahrt</h1>
<iframe id="karte-kaputt" src="/karte-embed" width="600" height="300" title="Karte"></iframe>
<iframe id="karte-ok" src="/karte-embed-ok" width="600" height="300" title="Karte funktioniert"></iframe>
<a href="/">Zurück</a>
</body>
</html>
```

`weiterleitungen.html`:

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Weiterleitungen</title></head>
<body>
<h1>Weiterleitungen</h1>
<a href="/weiterleitung/1">Kette über drei Sprünge</a>
<a href="/schleife/a">Endlosschleife</a>
<a href="/">Zurück</a>
</body>
</html>
```

`gemischt.html` — the subresource is deliberately absolute `http://`:

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Gemischte Inhalte</title></head>
<body>
<h1>Gemischte Inhalte</h1>
<img src="http://beispiel.invalid/unsicher.png" alt="Unsichere Ressource">
<a href="http://beispiel.invalid/unsicher">Unsicherer Link</a>
<a href="/">Zurück</a>
</body>
</html>
```

`konsole.html`:

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Konsole</title></head>
<body>
<h1>Konsolenfehler</h1>
<script>
    console.error('TypeError: kaputtesSkript ist nicht definiert');
    undefinierteFunktion();
</script>
<a href="/">Zurück</a>
</body>
</html>
```

`kontakt.html` — used by Phase 3, present now so the crawl sees a form:

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Kontakt</title></head>
<body>
<h1>Kontakt</h1>
<form action="/kontakt-absenden" method="post">
    <label for="name">Name</label><input id="name" name="name" type="text" required autocomplete="name">
    <label for="mail">E-Mail</label><input id="mail" name="email" type="email" required autocomplete="email">
    <label for="text">Nachricht</label><textarea id="text" name="nachricht" required></textarea>
    <button type="submit">Absenden</button>
</form>
<button id="tut-nichts" type="button">Mehr erfahren</button>
<a href="/">Zurück</a>
</body>
</html>
```

`de/index.html` and `en/index.html` — the switcher that changes the URL and serves German
anyway, plus a non-reciprocating hreflang:

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Deutsch</title>
<link rel="alternate" hreflang="de" href="/de/index.html">
<link rel="alternate" hreflang="en" href="/en/index.html">
</head>
<body><h1>Willkommen bei der Musterfirma</h1>
<a href="/en/index.html">English</a> <a href="/">Start</a></body>
</html>
```

```html
<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><title>English</title>
<link rel="alternate" hreflang="en" href="/en/index.html">
</head>
<body><h1>Willkommen bei der Musterfirma</h1>
<a href="/de/index.html">Deutsch</a> <a href="/">Start</a></body>
</html>
```

> `en/index.html` is the motivating failure of `LANGUAGE_SWITCHER` (§7.2): the URL changed,
> `<html lang>` changed, and the visible text did not. It is also the `HREFLANG` failure of
> §7.1 — `/de` points at `/en`, `/en` does not point back.

`sitemap.xml` — one entry that 404s, and `/konsole` deliberately missing:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    <url><loc>/</loc></url>
    <url><loc>/leistungen</loc></url>
    <url><loc>/kontakt</loc></url>
    <url><loc>/downloads</loc></url>
    <url><loc>/gibt-es-nicht-laut-sitemap</loc></url>
</urlset>
```

> The `<loc>` values are relative, which a real sitemap may not be. The seeder resolves them
> against the site base URL, which keeps the fixture portable across its random port.

`robots.txt`:

```
User-agent: *
Disallow: /intern/
Allow: /

Sitemap: /sitemap.xml
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=FixtureSiteTest`
Expected: PASS, all eleven cases.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/dev/hendrikhoemberg/webtesthelper/support src/test/resources/fixture-site
git commit -m "test: add the fixture site harness

One static site containing one of every failure mode on the manual
checklist (spec 15), served on an ephemeral loopback port."
```

---

### Task 7: `PageSnapshot`, the thread-confined browser worker, and extraction

Three things at once, because they only make sense together: the value type every check
consumes, the pool that produces it, and the extraction that fills it.

**The thread-confinement rule is the part that is easy to get wrong.** Playwright's Java
API is not thread-safe (§5.4). A `Playwright` instance and everything derived from it —
`Browser`, `BrowserContext`, `Page`, `Response` — belongs to the thread that created it.
So a worker creates its `Playwright` *inside* its own `run()` method, keeps it in a
thread-local, and every task submitted to the pool executes on the worker thread that owns
the session it is handed. Four browser workers means four Chromium **processes**, not four
contexts in one browser, which is what drives the container's memory sizing (§16).

The pool takes a `BrowserSessionFactory` rather than creating Playwright directly. That is
the seam that lets the confinement rules be unit-tested without launching a browser.

**Files:**
- Create: `src/main/java/.../model/PageSnapshot.java`
- Create: `src/main/java/.../model/LinkRef.java`, `ImageRef.java`, `ImageOrigin.java`,
  `MediaRef.java`, `MediaKind.java`, `IframeRef.java`, `FormRef.java`, `FormFieldRef.java`,
  `HreflangRef.java`, `SubresourceRef.java`, `ConsoleMessageRef.java`,
  `NetworkFailureRef.java`, `RedirectHop.java`, `RunSnapshots.java`
- Create: `src/main/java/.../runner/BrowserSession.java`, `BrowserSessionFactory.java`,
  `BrowserTask.java`, `BrowserWorkerPool.java`, `PlaywrightSession.java`,
  `BrowserPoolProperties.java`, `BrowserPoolConfiguration.java`
- Create: `src/main/java/.../crawler/PageSnapshotExtractor.java`
- Create: `src/main/java/.../crawler/CaptureOptions.java`
- Create: `src/main/java/.../crawler/PageCapture.java`
- Test: `src/test/java/.../runner/BrowserWorkerPoolTest.java`
- Test: `src/test/java/.../crawler/PageSnapshotExtractorIT.java`

**Interfaces:**
- Consumes: `model.UrlNormalizer`, `support.FixtureSite` (test only).
- Produces:
  - `PageSnapshot(String requestedUrl, String finalUrl, int httpStatus,
    Map<String,String> responseHeaders, List<RedirectHop> redirectChain, long loadTimeMillis,
    String title, String htmlLang, String canonicalUrl, String textContent,
    List<LinkRef> links, List<ImageRef> images, List<MediaRef> media, List<IframeRef> iframes,
    List<FormRef> forms, List<HreflangRef> hreflangs, List<SubresourceRef> subresources,
    List<ConsoleMessageRef> consoleMessages, List<NetworkFailureRef> networkFailures,
    String screenshotPath, Instant capturedAt)` with `isSecure()`, `locationKey()`,
    and `PageSnapshot.builderFor(String requestedUrl)`.
  - `RunSnapshots(List<PageSnapshot> snapshots)` with `byLocationKey()`, `size()`, `stream()`.
  - `BrowserWorkerPool(String name, int size, BrowserSessionFactory factory)` with
    `submit(BrowserTask<T>) -> CompletableFuture<T>`, `size()`, `close()`.
  - `BrowserSession` with `workerIndex()`, `owner()`, `assertOwningThread()`,
    `newContext(String userAgent) -> BrowserContext`.
  - `PageSnapshotExtractor.capture(Page page, String requestedUrl, CaptureOptions) -> PageCapture`.

- [ ] **Step 1: Write the failing pool test**

`src/test/java/.../runner/BrowserWorkerPoolTest.java` — no browser involved, entirely about
thread confinement:

```java
package dev.hendrikhoemberg.webtesthelper.runner;

import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserWorkerPoolTest {

    /** Stand-in session: records its owning thread, never launches a browser. */
    private static final class FakeSession implements BrowserSession {
        private final int index;
        private final Thread owner = Thread.currentThread();

        FakeSession(int index) {
            this.index = index;
        }

        @Override public int workerIndex() { return index; }
        @Override public Thread owner() { return owner; }
        @Override public BrowserContext newContext(String userAgent) {
            assertOwningThread();
            throw new UnsupportedOperationException("kein Browser im Test");
        }
        @Override public void close() { }
    }

    @Test
    void everyTaskRunsOnTheThreadThatOwnsTheSessionItReceives() throws Exception {
        try (BrowserWorkerPool pool = new BrowserWorkerPool("test", 2, FakeSession::new)) {
            Set<String> mismatches = ConcurrentHashMap.newKeySet();
            CompletableFuture<?>[] futures = new CompletableFuture<?>[40];
            for (int i = 0; i < futures.length; i++) {
                futures[i] = pool.submit(session -> {
                    if (session.owner() != Thread.currentThread()) {
                        mismatches.add(session.owner().getName() + " != " + Thread.currentThread().getName());
                    }
                    session.assertOwningThread();
                    return session.workerIndex();
                });
            }
            CompletableFuture.allOf(futures).join();
            assertThat(mismatches).isEmpty();
        }
    }

    @Test
    void aSizeOnePoolReusesTheSameSessionForEveryTask() throws Exception {
        try (BrowserWorkerPool pool = new BrowserWorkerPool("test", 1, FakeSession::new)) {
            BrowserSession first = pool.submit(session -> session).get();
            BrowserSession second = pool.submit(session -> session).get();
            assertThat(first).isSameAs(second);
        }
    }

    @Test
    void everyWorkerGetsItsOwnSession() throws Exception {
        try (BrowserWorkerPool pool = new BrowserWorkerPool("test", 3, FakeSession::new)) {
            Set<BrowserSession> seen = ConcurrentHashMap.newKeySet();
            CompletableFuture<?>[] futures = new CompletableFuture<?>[60];
            for (int i = 0; i < futures.length; i++) {
                futures[i] = pool.submit(session -> {
                    seen.add(session);
                    Thread.sleep(5);
                    return null;
                });
            }
            CompletableFuture.allOf(futures).join();
            assertThat(seen).hasSize(3);
            assertThat(seen).extracting(BrowserSession::workerIndex).containsExactlyInAnyOrder(0, 1, 2);
        }
    }

    @Test
    void touchingASessionFromAForeignThreadIsRejected() throws Exception {
        try (BrowserWorkerPool pool = new BrowserWorkerPool("test", 1, FakeSession::new)) {
            BrowserSession escaped = pool.submit(session -> session).get();

            assertThatThrownBy(escaped::assertOwningThread)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Playwright");
        }
    }

    @Test
    void aFailingTaskCompletesItsFutureExceptionallyAndTheWorkerKeepsGoing() throws Exception {
        try (BrowserWorkerPool pool = new BrowserWorkerPool("test", 1, FakeSession::new)) {
            CompletableFuture<Object> failed = pool.submit(session -> {
                throw new IllegalStateException("kaputt");
            });

            assertThatThrownBy(failed::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseMessage("kaputt");

            assertThat(pool.submit(session -> "weiter").get()).isEqualTo("weiter");
        }
    }

    @Test
    void submittingAfterCloseFailsTheFutureRatherThanHanging() throws Exception {
        BrowserWorkerPool pool = new BrowserWorkerPool("test", 1, FakeSession::new);
        pool.close();

        assertThatThrownBy(() -> pool.submit(session -> "x").get())
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=BrowserWorkerPoolTest`
Expected: compilation failure — `BrowserWorkerPool` does not exist.

- [ ] **Step 3: Write the pool**

```java
package dev.hendrikhoemberg.webtesthelper.runner;

@FunctionalInterface
public interface BrowserTask<T> {
    T run(BrowserSession session) throws Exception;
}
```

```java
package dev.hendrikhoemberg.webtesthelper.runner;

@FunctionalInterface
public interface BrowserSessionFactory {

    /** Called on the worker thread. Must create every Playwright object on the calling thread. */
    BrowserSession create(int workerIndex);
}
```

```java
package dev.hendrikhoemberg.webtesthelper.runner;

import com.microsoft.playwright.BrowserContext;

/**
 * A thread-confined browser. Playwright's Java API is not thread-safe (spec 5.4): every
 * object reachable from a session belongs to {@link #owner()} and touching it from anywhere
 * else is undefined behaviour, which is why {@link #assertOwningThread()} exists and is
 * called on every entry point rather than trusted to discipline.
 */
public interface BrowserSession extends AutoCloseable {

    int workerIndex();

    Thread owner();

    BrowserContext newContext(String userAgent);

    @Override
    void close();

    default void assertOwningThread() {
        Thread current = Thread.currentThread();
        if (current != owner()) {
            throw new IllegalStateException(
                    "Playwright-Sitzung von Worker " + workerIndex() + " gehört Thread '"
                            + owner().getName() + "', wurde aber von '" + current.getName()
                            + "' benutzt. Die Playwright-API ist nicht thread-sicher.");
        }
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A fixed pool of thread-confined browser workers.
 *
 * <p>Each worker is a <strong>platform</strong> thread that creates its own
 * {@link BrowserSession} inside {@link #workerLoop} and never lets it escape to another
 * thread. Virtual threads are wrong here: Playwright pins native resources and its objects
 * have hard thread affinity (spec 5.4). Asset checking, which is pure blocking I/O, uses
 * virtual threads instead — see Task 12.
 */
public final class BrowserWorkerPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrowserWorkerPool.class);

    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final List<Thread> workers = new ArrayList<>();
    private final int size;
    private volatile boolean running = true;

    public BrowserWorkerPool(String name, int size, BrowserSessionFactory factory) {
        if (size < 1) {
            throw new IllegalArgumentException("Pool-Größe muss >= 1 sein");
        }
        this.size = size;
        for (int i = 0; i < size; i++) {
            int index = i;
            Thread worker = new Thread(() -> workerLoop(index, factory), name + "-browser-" + index);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
    }

    public int size() {
        return size;
    }

    /**
     * Queues a task. It will run on some worker thread, holding that worker's session; the
     * session must not outlive the task.
     */
    public <T> CompletableFuture<T> submit(BrowserTask<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!running) {
            future.completeExceptionally(new IllegalStateException("Browser-Pool ist geschlossen"));
            return future;
        }
        queue.add(() -> {
            BrowserSession session = CURRENT.get();
            try {
                future.complete(task.run(session));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public void close() {
        running = false;
        for (Thread worker : workers) {
            worker.interrupt();
        }
        for (Thread worker : workers) {
            try {
                worker.join(TimeUnit.SECONDS.toMillis(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final ThreadLocal<BrowserSession> CURRENT = new ThreadLocal<>();

    private void workerLoop(int index, BrowserSessionFactory factory) {
        BrowserSession session = null;
        try {
            // Created here, on this thread, on purpose. Creating it in the constructor would
            // bind every session to the thread that built the pool.
            session = factory.create(index);
            CURRENT.set(session);
            while (running) {
                Runnable job = queue.poll(200, TimeUnit.MILLISECONDS);
                if (job != null) {
                    job.run();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("Browser-Worker {} beendet sich nach einem Fehler", index, e);
        } finally {
            CURRENT.remove();
            if (session != null) {
                try {
                    session.close();
                } catch (RuntimeException e) {
                    log.warn("Browser-Worker {} konnte nicht sauber schließen", index, e);
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run the pool test to verify it passes**

Run: `./mvnw test -Dtest=BrowserWorkerPoolTest`
Expected: PASS, all six cases.

- [ ] **Step 5: Write the real `PlaywrightSession` and its Spring wiring**

```java
package dev.hendrikhoemberg.webtesthelper.runner;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * One Chromium process per worker. Not a context borrowed from a shared browser (spec 5.4)
 * — which is why memory sizing budgets ~500 MB per worker (spec 16).
 */
public final class PlaywrightSession implements BrowserSession {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightSession.class);

    private static final List<String> LAUNCH_ARGS = List.of(
            "--no-sandbox",              // required in the container; it runs unprivileged anyway
            "--disable-dev-shm-usage",   // /dev/shm is tiny in Docker; without this Chromium crashes
            "--disable-gpu");

    private final int workerIndex;
    private final Thread owner;
    private final Playwright playwright;
    private Browser browser;

    private PlaywrightSession(int workerIndex, Playwright playwright) {
        this.workerIndex = workerIndex;
        this.owner = Thread.currentThread();
        this.playwright = playwright;
    }

    /** Must be called on the worker thread that will use the session. */
    public static PlaywrightSession createOnCurrentThread(int workerIndex) {
        return new PlaywrightSession(workerIndex, Playwright.create());
    }

    @Override
    public int workerIndex() {
        return workerIndex;
    }

    @Override
    public Thread owner() {
        return owner;
    }

    /**
     * Fresh context per page batch: bounds memory and starts each page with clean cookies,
     * so runs are reproducible (spec 5.4).
     */
    @Override
    public BrowserContext newContext(String userAgent) {
        assertOwningThread();
        return browser().newContext(new Browser.NewContextOptions()
                .setUserAgent(userAgent)
                .setViewportSize(1366, 900)
                .setIgnoreHTTPSErrors(true)
                .setLocale("de-DE")
                .setTimezoneId("Europe/Berlin"));
    }

    /** Relaunches if the browser process died; the run then resumes from the persisted frontier (spec 14). */
    private Browser browser() {
        assertOwningThread();
        if (browser == null || !browser.isConnected()) {
            if (browser != null) {
                log.warn("Chromium von Worker {} ist gestorben, wird neu gestartet", workerIndex);
            }
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true).setArgs(LAUNCH_ARGS));
        }
        return browser;
    }

    @Override
    public void close() {
        assertOwningThread();
        if (browser != null) {
            browser.close();
            browser = null;
        }
        playwright.close();
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "webtesthelper")
public record BrowserPoolProperties(
        Integer browserWorkers,
        Duration navigationTimeout,
        Integer crawlBatchSize) {

    public int workers() {
        return browserWorkers == null ? 4 : browserWorkers;
    }

    public Duration navigation() {
        return navigationTimeout == null ? Duration.ofSeconds(30) : navigationTimeout;
    }

    public int batchSize() {
        return crawlBatchSize == null ? 20 : crawlBatchSize;
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.runner;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BrowserPoolProperties.class)
class BrowserPoolConfiguration {

    @Bean(destroyMethod = "close")
    BrowserWorkerPool browserWorkerPool(BrowserPoolProperties properties) {
        return new BrowserWorkerPool("runner", properties.workers(),
                PlaywrightSession::createOnCurrentThread);
    }
}
```

> The recorder pool of §5.4 is a second, separate `BrowserWorkerPool` bean in Phase 4. It
> is deliberately not created here — an idle recording session must never be able to halve
> crawl throughput.

- [ ] **Step 6: Write the `PageSnapshot` value types**

All in `model`, all records. Keep each file to its one record.

```java
package dev.hendrikhoemberg.webtesthelper.model;

public record LinkRef(String href, String resolvedUrl, String anchorText, String rel, String target) {
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

public enum ImageOrigin { IMG, SRCSET, CSS_BACKGROUND }
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

/**
 * {@code naturalWidth}/{@code naturalHeight} are null for anything but {@link ImageOrigin#IMG}:
 * only a rendered {@code <img>} has measurable intrinsic dimensions. Non-IMG origins are
 * judged by fetching their URL instead (spec 7.1).
 */
public record ImageRef(String source, String resolvedUrl, ImageOrigin origin,
                       Integer naturalWidth, Integer naturalHeight, String alt) {

    public boolean measurable() {
        return origin == ImageOrigin.IMG && naturalWidth != null;
    }

    public boolean rendered() {
        return measurable() && naturalWidth > 0;
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

public enum MediaKind { VIDEO, AUDIO }
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;

/**
 * {@code readyState >= 1} plus {@code duration > 0} is what "plays" means (spec 7.1) —
 * a 200 on the source file does not imply a decodable stream.
 */
public record MediaRef(MediaKind kind, List<String> sources, int readyState, double duration,
                       String error) {

    public MediaRef {
        sources = List.copyOf(sources);
    }

    public boolean metadataLoaded() {
        return readyState >= 1 && duration > 0;
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

/**
 * {@code canvasArea} is what distinguishes a working Google Maps embed from the grey
 * "For development purposes only" watermark: the iframe loads either way, but only a
 * working map paints a canvas (spec 7.1).
 */
public record IframeRef(String src, String resolvedUrl, String title, boolean frameAttached,
                        boolean bodyNonEmpty, int contentTextLength, int canvasArea,
                        int width, int height, String frameOptionsHeader, String cspHeader) {

    public boolean blockedByHeaders() {
        return (frameOptionsHeader != null && !frameOptionsHeader.isBlank())
                || (cspHeader != null && cspHeader.toLowerCase().contains("frame-ancestors"));
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

public record FormFieldRef(String name, String type, String label, String autocomplete,
                           boolean required) {
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;

public record FormRef(String action, String resolvedAction, String method, List<FormFieldRef> fields) {

    public FormRef {
        fields = List.copyOf(fields);
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

public record HreflangRef(String lang, String href, String resolvedUrl) {
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Map;

/** One network response the page made. Headers are captured for documents only. */
public record SubresourceRef(String url, String resourceType, int status, Map<String, String> headers) {

    public SubresourceRef {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

public record ConsoleMessageRef(String level, String text, String location) {

    public boolean isError() {
        return "error".equalsIgnoreCase(level) || "pageerror".equalsIgnoreCase(level);
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

public record NetworkFailureRef(String url, String method, String failureText, String resourceType) {
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

public record RedirectHop(String url, int status, String location) {
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything one page visit produced, frozen. Every page check is a pure function of this
 * record (spec 5.2) — one navigation, many checks.
 */
public record PageSnapshot(
        String requestedUrl,
        String finalUrl,
        int httpStatus,
        Map<String, String> responseHeaders,
        List<RedirectHop> redirectChain,
        long loadTimeMillis,
        String title,
        String htmlLang,
        String canonicalUrl,
        String textContent,
        List<LinkRef> links,
        List<ImageRef> images,
        List<MediaRef> media,
        List<IframeRef> iframes,
        List<FormRef> forms,
        List<HreflangRef> hreflangs,
        List<SubresourceRef> subresources,
        List<ConsoleMessageRef> consoleMessages,
        List<NetworkFailureRef> networkFailures,
        String screenshotPath,
        Instant capturedAt) {

    public PageSnapshot {
        responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
        redirectChain = copy(redirectChain);
        links = copy(links);
        images = copy(images);
        media = copy(media);
        iframes = copy(iframes);
        forms = copy(forms);
        hreflangs = copy(hreflangs);
        subresources = copy(subresources);
        consoleMessages = copy(consoleMessages);
        networkFailures = copy(networkFailures);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public boolean isSecure() {
        return finalUrl != null && finalUrl.startsWith("https://");
    }

    public String locationKey() {
        return UrlNormalizer.locationKeyOf(finalUrl);
    }

    public boolean redirected() {
        return !redirectChain.isEmpty();
    }

    public static Builder builderFor(String requestedUrl) {
        return new Builder(requestedUrl);
    }

    /**
     * Mutable builder — the extractor fills a snapshot in stages, and unit tests hand-build
     * one with only the two or three fields the check under test reads.
     */
    public static final class Builder {
        private final String requestedUrl;
        private String finalUrl;
        private int httpStatus = 200;
        private Map<String, String> responseHeaders = Map.of();
        private List<RedirectHop> redirectChain = new ArrayList<>();
        private long loadTimeMillis;
        private String title = "";
        private String htmlLang = "";
        private String canonicalUrl;
        private String textContent = "";
        private List<LinkRef> links = new ArrayList<>();
        private List<ImageRef> images = new ArrayList<>();
        private List<MediaRef> media = new ArrayList<>();
        private List<IframeRef> iframes = new ArrayList<>();
        private List<FormRef> forms = new ArrayList<>();
        private List<HreflangRef> hreflangs = new ArrayList<>();
        private List<SubresourceRef> subresources = new ArrayList<>();
        private List<ConsoleMessageRef> consoleMessages = new ArrayList<>();
        private List<NetworkFailureRef> networkFailures = new ArrayList<>();
        private String screenshotPath;
        private Instant capturedAt = Instant.now();

        private Builder(String requestedUrl) {
            this.requestedUrl = requestedUrl;
            this.finalUrl = requestedUrl;
        }

        public Builder finalUrl(String value) { this.finalUrl = value; return this; }
        public Builder httpStatus(int value) { this.httpStatus = value; return this; }
        public Builder responseHeaders(Map<String, String> value) { this.responseHeaders = value; return this; }
        public Builder redirectChain(List<RedirectHop> value) { this.redirectChain = value; return this; }
        public Builder loadTimeMillis(long value) { this.loadTimeMillis = value; return this; }
        public Builder title(String value) { this.title = value; return this; }
        public Builder htmlLang(String value) { this.htmlLang = value; return this; }
        public Builder canonicalUrl(String value) { this.canonicalUrl = value; return this; }
        public Builder textContent(String value) { this.textContent = value; return this; }
        public Builder links(List<LinkRef> value) { this.links = value; return this; }
        public Builder images(List<ImageRef> value) { this.images = value; return this; }
        public Builder media(List<MediaRef> value) { this.media = value; return this; }
        public Builder iframes(List<IframeRef> value) { this.iframes = value; return this; }
        public Builder forms(List<FormRef> value) { this.forms = value; return this; }
        public Builder hreflangs(List<HreflangRef> value) { this.hreflangs = value; return this; }
        public Builder subresources(List<SubresourceRef> value) { this.subresources = value; return this; }
        public Builder consoleMessages(List<ConsoleMessageRef> value) { this.consoleMessages = value; return this; }
        public Builder networkFailures(List<NetworkFailureRef> value) { this.networkFailures = value; return this; }
        public Builder screenshotPath(String value) { this.screenshotPath = value; return this; }
        public Builder capturedAt(Instant value) { this.capturedAt = value; return this; }

        public PageSnapshot build() {
            return new PageSnapshot(requestedUrl, finalUrl, httpStatus, responseHeaders, redirectChain,
                    loadTimeMillis, title, htmlLang, canonicalUrl, textContent, links, images, media,
                    iframes, forms, hreflangs, subresources, consoleMessages, networkFailures,
                    screenshotPath, capturedAt);
        }
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Every snapshot a run produced — the input to a {@code SiteCheck} (spec 5.2). */
public record RunSnapshots(List<PageSnapshot> snapshots) {

    public RunSnapshots {
        snapshots = List.copyOf(snapshots);
    }

    public int size() {
        return snapshots.size();
    }

    public Stream<PageSnapshot> stream() {
        return snapshots.stream();
    }

    /** Keyed by normalised location; the first snapshot wins if a URL somehow appears twice. */
    public Map<String, PageSnapshot> byLocationKey() {
        Map<String, PageSnapshot> index = new LinkedHashMap<>();
        for (PageSnapshot snapshot : snapshots) {
            index.putIfAbsent(snapshot.locationKey(), snapshot);
        }
        return index;
    }

    /** Normalised final URLs of every page in the run — one half of run coverage (spec 6.4). */
    public java.util.Set<String> normalizedUrls() {
        return snapshots.stream()
                .map(snapshot -> UrlNormalizer.key(snapshot.finalUrl()).orElse(snapshot.finalUrl()))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
```

- [ ] **Step 7: Write the failing extractor test**

`src/test/java/.../crawler/PageSnapshotExtractorIT.java` — real Chromium against the fixture
site. Named `…IT` so it is obvious this one starts a browser.

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.hendrikhoemberg.webtesthelper.model.*;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageSnapshotExtractorIT {

    @RegisterExtension
    static final FixtureSite SITE = new FixtureSite();

    static Playwright playwright;
    static Browser browser;

    @TempDir
    Path artifacts;

    final PageSnapshotExtractor extractor = new PageSnapshotExtractor();

    @BeforeAll
    static void launch() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true).setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));
    }

    @AfterAll
    static void shutdown() {
        browser.close();
        playwright.close();
    }

    private PageCapture capture(String path) {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            return extractor.capture(page, SITE.url(path), new CaptureOptions(
                    Duration.ofSeconds(20), artifacts, true, 20000));
        }
    }

    @Test
    void capturesStatusTitleAndLinks() {
        PageSnapshot snapshot = capture("/index.html").snapshot();

        assertThat(snapshot.httpStatus()).isEqualTo(200);
        assertThat(snapshot.title()).isEqualTo("Musterfirma – Startseite");
        assertThat(snapshot.htmlLang()).isEqualTo("de");
        assertThat(snapshot.links()).extracting(LinkRef::anchorText).contains("Leistungen", "Kontakt");
        assertThat(snapshot.links()).extracting(LinkRef::resolvedUrl)
                .anyMatch(url -> url.endsWith("/leistungen"));
        assertThat(snapshot.screenshotPath()).isNotNull();
        assertThat(Path.of(snapshot.screenshotPath())).exists();
    }

    @Test
    void capturesTheThreeImageOriginsAndMeasuresOnlyRenderedImgElements() {
        PageSnapshot snapshot = capture("/bilder.html").snapshot();

        assertThat(snapshot.images()).extracting(ImageRef::origin)
                .contains(ImageOrigin.IMG, ImageOrigin.SRCSET, ImageOrigin.CSS_BACKGROUND);

        ImageRef working = snapshot.images().stream()
                .filter(image -> image.origin() == ImageOrigin.IMG && image.resolvedUrl().endsWith("/logo.png"))
                .findFirst().orElseThrow();
        assertThat(working.rendered()).isTrue();

        ImageRef broken = snapshot.images().stream()
                .filter(image -> image.origin() == ImageOrigin.IMG && image.resolvedUrl().endsWith("/fehlt.png"))
                .findFirst().orElseThrow();
        assertThat(broken.measurable()).isTrue();
        assertThat(broken.rendered()).isFalse();

        assertThat(snapshot.images()).filteredOn(image -> image.origin() == ImageOrigin.SRCSET)
                .allSatisfy(image -> assertThat(image.measurable()).isFalse());
    }

    @Test
    void waitsForMediaMetadataBeforeReadingReadyState() {
        PageSnapshot snapshot = capture("/medien.html").snapshot();

        MediaRef audio = snapshot.media().stream().filter(m -> m.kind() == MediaKind.AUDIO)
                .findFirst().orElseThrow();
        assertThat(audio.metadataLoaded()).isTrue();
        assertThat(audio.duration()).isGreaterThan(0.5);

        MediaRef video = snapshot.media().stream().filter(m -> m.kind() == MediaKind.VIDEO)
                .findFirst().orElseThrow();
        assertThat(video.metadataLoaded()).isFalse();
    }

    @Test
    void distinguishesAPaintedMapCanvasFromAGreyPlaceholder() {
        PageSnapshot snapshot = capture("/karte.html").snapshot();

        IframeRef broken = snapshot.iframes().stream()
                .filter(f -> f.resolvedUrl().endsWith("/karte-embed")).findFirst().orElseThrow();
        IframeRef working = snapshot.iframes().stream()
                .filter(f -> f.resolvedUrl().endsWith("/karte-embed-ok")).findFirst().orElseThrow();

        assertThat(broken.frameAttached()).isTrue();
        assertThat(broken.canvasArea()).isZero();
        assertThat(working.canvasArea()).isGreaterThan(0);
    }

    @Test
    void capturesConsoleErrorsIncludingUncaughtExceptions() {
        PageSnapshot snapshot = capture("/konsole.html").snapshot();

        assertThat(snapshot.consoleMessages()).filteredOn(ConsoleMessageRef::isError)
                .extracting(ConsoleMessageRef::text)
                .anyMatch(text -> text.contains("kaputtesSkript"))
                .anyMatch(text -> text.contains("undefinierteFunktion"));
    }

    @Test
    void capturesTheRedirectChainAndTheFinalUrl() {
        PageSnapshot snapshot = capture("/weiterleitung/1").snapshot();

        assertThat(snapshot.finalUrl()).endsWith("/weiterleitung/ziel");
        assertThat(snapshot.redirectChain()).hasSize(3);
        assertThat(snapshot.redirectChain()).extracting(RedirectHop::status).containsOnly(302);
        assertThat(snapshot.redirected()).isTrue();
    }

    @Test
    void capturesFormsWithTheirFieldsAndLabels() {
        PageSnapshot snapshot = capture("/kontakt.html").snapshot();

        FormRef form = snapshot.forms().getFirst();
        assertThat(form.method()).isEqualTo("POST");
        assertThat(form.fields()).extracting(FormFieldRef::name).contains("name", "email", "nachricht");
        assertThat(form.fields()).extracting(FormFieldRef::label).contains("Name", "E-Mail", "Nachricht");
        assertThat(form.fields()).filteredOn(field -> field.name().equals("email"))
                .allSatisfy(field -> assertThat(field.autocomplete()).isEqualTo("email"));
    }

    @Test
    void capturesHreflangAlternates() {
        PageSnapshot snapshot = capture("/de/index.html").snapshot();

        assertThat(snapshot.hreflangs()).extracting(HreflangRef::lang).containsExactlyInAnyOrder("de", "en");
    }

    @Test
    void recordsSubresourcesIncludingTheInsecureOneOnAMixedContentPage() {
        PageSnapshot snapshot = capture("/gemischt.html").snapshot();

        assertThat(snapshot.links()).extracting(LinkRef::resolvedUrl)
                .anyMatch(url -> url.startsWith("http://beispiel.invalid/"));
        assertThat(snapshot.images()).extracting(ImageRef::resolvedUrl)
                .anyMatch(url -> url.startsWith("http://beispiel.invalid/"));
    }

    @Test
    void anUnreachableUrlProducesAFailedCaptureRatherThanAnException() {
        PageCapture capture = extractorCaptureOf("http://127.0.0.1:1/tot");

        assertThat(capture.state()).isEqualTo(CrawlItemState.FAILED);
        assertThat(capture.errorMessage()).isNotBlank();
        assertThat(capture.snapshot()).isNull();
    }

    private PageCapture extractorCaptureOf(String url) {
        try (BrowserContext context = browser.newContext()) {
            return extractor.capture(context.newPage(), url,
                    new CaptureOptions(Duration.ofSeconds(5), artifacts, false, 20000));
        }
    }
}
```

- [ ] **Step 8: Write the extractor**

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import java.nio.file.Path;
import java.time.Duration;

public record CaptureOptions(Duration navigationTimeout, Path artifactDirectory,
                             boolean captureScreenshot, int maxTextLength) {
}
```

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;

/** Either a snapshot, or the reason there is none. One bad page must never kill a run (spec 14). */
public record PageCapture(PageSnapshot snapshot, CrawlItemState state, String errorMessage) {

    public static PageCapture ok(PageSnapshot snapshot) {
        return new PageCapture(snapshot, CrawlItemState.DONE, null);
    }

    public static PageCapture failed(String message) {
        return new PageCapture(null, CrawlItemState.FAILED, message);
    }

    public boolean succeeded() {
        return state == CrawlItemState.DONE;
    }
}
```

`crawler/PageSnapshotExtractor.java` — the one class that touches the DOM:

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import dev.hendrikhoemberg.webtesthelper.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Turns one navigation into one {@link PageSnapshot} (spec 5.2). This is the only place in
 * the system that reads the DOM; every check downstream is a pure function of the result,
 * which is what makes ~15,000 page visits per sweep affordable and check unit tests
 * browser-free.
 */
@Component
public class PageSnapshotExtractor {

    private static final Logger log = LoggerFactory.getLogger(PageSnapshotExtractor.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One async evaluate for the whole DOM, returning JSON so Jackson can map it into records.
     *
     * <p>Every backslash below is doubled: Java text blocks process escape sequences, and
     * {@code \s} is a legal Java escape (space) that would silently corrupt these regexes.
     */
    private static final String EXTRACT_SCRIPT = """
            async () => {
              const abs = (u) => { try { return new URL(u, document.baseURI).href; } catch (e) { return null; } };
              const trim = (s, n) => (s || '').replace(/\\s+/g, ' ').trim().slice(0, n);

              const mediaEls = Array.from(document.querySelectorAll('video,audio'));
              await Promise.all(mediaEls.map(el => new Promise(resolve => {
                if (el.readyState >= 1) { resolve(); return; }
                const done = () => { clearTimeout(timer); resolve(); };
                const timer = setTimeout(done, 4000);
                el.addEventListener('loadedmetadata', done, { once: true });
                el.addEventListener('error', done, { once: true });
                try { el.load(); } catch (e) { done(); }
              })));

              const links = Array.from(document.querySelectorAll('a[href]')).map(a => ({
                href: a.getAttribute('href'), resolved: abs(a.getAttribute('href')),
                text: trim(a.textContent, 200), rel: a.getAttribute('rel') || '',
                target: a.getAttribute('target') || ''
              }));

              const images = [];
              for (const img of document.querySelectorAll('img')) {
                images.push({ source: img.getAttribute('src'),
                              resolved: img.currentSrc || abs(img.getAttribute('src')),
                              origin: 'IMG', naturalWidth: img.naturalWidth,
                              naturalHeight: img.naturalHeight, alt: img.getAttribute('alt') || '' });
                const srcset = img.getAttribute('srcset');
                if (srcset) {
                  for (const candidate of srcset.split(',')) {
                    const url = candidate.trim().split(/\\s+/)[0];
                    if (url) images.push({ source: url, resolved: abs(url), origin: 'SRCSET',
                                           naturalWidth: null, naturalHeight: null, alt: '' });
                  }
                }
              }
              let inspected = 0;
              for (const el of document.querySelectorAll('*')) {
                if (++inspected > 3000) break;   // getComputedStyle is not free on huge pages
                const bg = getComputedStyle(el).backgroundImage;
                if (!bg || bg === 'none') continue;
                for (const match of bg.matchAll(/url\\((['"]?)(.*?)\\1\\)/g)) {
                  const url = match[2];
                  if (!url || url.startsWith('data:')) continue;
                  images.push({ source: url, resolved: abs(url), origin: 'CSS_BACKGROUND',
                                naturalWidth: null, naturalHeight: null, alt: '' });
                }
              }

              const media = mediaEls.map(el => ({
                kind: el.tagName.toUpperCase(),
                sources: [el.getAttribute('src')]
                  .concat(Array.from(el.querySelectorAll('source')).map(s => s.getAttribute('src')))
                  .filter(Boolean).map(abs).filter(Boolean),
                readyState: el.readyState,
                duration: Number.isFinite(el.duration) ? el.duration : 0,
                error: el.error ? ('MEDIA_ERR_' + el.error.code) : null
              }));

              const iframes = Array.from(document.querySelectorAll('iframe')).map(f => {
                let sameOrigin = false, bodyNonEmpty = false;
                try {
                  const doc = f.contentDocument;
                  if (doc) { sameOrigin = true;
                             bodyNonEmpty = !!(doc.body && doc.body.innerHTML.trim().length > 0); }
                } catch (e) { sameOrigin = false; }
                const rect = f.getBoundingClientRect();
                return { src: f.getAttribute('src'), resolved: abs(f.getAttribute('src') || ''),
                         title: f.getAttribute('title') || '', sameOrigin, bodyNonEmpty,
                         width: Math.round(rect.width), height: Math.round(rect.height) };
              });

              const labelOf = (el) => {
                if (el.id) {
                  const label = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
                  if (label) return trim(label.textContent, 100);
                }
                const parent = el.closest('label');
                return parent ? trim(parent.textContent, 100) : '';
              };
              const forms = Array.from(document.querySelectorAll('form')).map(f => ({
                action: f.getAttribute('action'),
                resolved: abs(f.getAttribute('action') || location.href),
                method: (f.getAttribute('method') || 'get').toUpperCase(),
                fields: Array.from(f.querySelectorAll('input,select,textarea')).map(i => ({
                  name: i.getAttribute('name') || '',
                  type: (i.getAttribute('type') || i.tagName).toLowerCase(),
                  label: labelOf(i), autocomplete: i.getAttribute('autocomplete') || '',
                  required: i.hasAttribute('required')
                }))
              }));

              const hreflangs = Array.from(document.querySelectorAll('link[rel~="alternate"][hreflang]'))
                .map(l => ({ lang: l.getAttribute('hreflang'), href: l.getAttribute('href'),
                             resolved: abs(l.getAttribute('href')) }));

              const canonical = document.querySelector('link[rel="canonical"]');

              return JSON.stringify({
                title: document.title || '',
                htmlLang: document.documentElement.getAttribute('lang') || '',
                canonical: canonical ? abs(canonical.getAttribute('href')) : null,
                text: trim(document.body ? document.body.innerText : '', 20000),
                links, images, media, iframes, forms, hreflangs
              });
            }
            """;

    private static final String CANVAS_AREA_SCRIPT = """
            () => {
              let area = 0, text = 0;
              for (const c of document.querySelectorAll('canvas')) { area += c.width * c.height; }
              if (document.body) { text = (document.body.innerText || '').trim().length; }
              return { area, text };
            }
            """;

    public PageCapture capture(Page page, String requestedUrl, CaptureOptions options) {
        List<ConsoleMessageRef> console = new CopyOnWriteArrayList<>();
        List<NetworkFailureRef> failures = new CopyOnWriteArrayList<>();
        List<SubresourceRef> subresources = new CopyOnWriteArrayList<>();
        Map<String, Map<String, String>> documentHeaders = new java.util.concurrent.ConcurrentHashMap<>();

        page.onConsoleMessage(message -> console.add(
                new ConsoleMessageRef(message.type(), truncate(message.text(), 2000), message.location())));
        page.onPageError(error -> console.add(
                new ConsoleMessageRef("pageerror", truncate(error, 2000), "")));
        page.onRequestFailed(request -> failures.add(new NetworkFailureRef(
                request.url(), request.method(), request.failure(), request.resourceType())));
        page.onResponse(response -> {
            if (subresources.size() >= 500) {
                return;
            }
            String type = response.request().resourceType();
            Map<String, String> headers = Map.of();
            if ("document".equals(type)) {
                // Only documents: these are the headers IFRAME_EMBED needs (spec 7.1).
                headers = lowerCased(response.allHeaders());
                documentHeaders.put(response.url(), headers);
            }
            subresources.add(new SubresourceRef(response.url(), type, response.status(), headers));
        });

        long start = System.nanoTime();
        Response response;
        try {
            response = page.navigate(requestedUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.LOAD)
                    .setTimeout(options.navigationTimeout().toMillis()));
        } catch (PlaywrightException e) {
            return PageCapture.failed(truncate(e.getMessage(), 1000));
        }
        if (response == null) {
            return PageCapture.failed("Keine HTTP-Antwort für " + requestedUrl);
        }

        // Best effort: a page whose third-party scripts poll forever must not fail the crawl.
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(5000));
        } catch (PlaywrightException ignored) {
            log.debug("networkidle nicht erreicht für {}", requestedUrl);
        }

        String json;
        try {
            json = (String) page.evaluate(EXTRACT_SCRIPT);
        } catch (PlaywrightException e) {
            return PageCapture.failed("DOM-Auswertung fehlgeschlagen: " + truncate(e.getMessage(), 500));
        }

        RawDom dom;
        try {
            dom = JSON.readValue(json, RawDom.class);
        } catch (Exception e) {
            return PageCapture.failed("DOM-JSON nicht lesbar: " + truncate(e.getMessage(), 500));
        }

        long loadMillis = (System.nanoTime() - start) / 1_000_000;
        String screenshot = options.captureScreenshot() ? screenshot(page, options) : null;

        PageSnapshot snapshot = PageSnapshot.builderFor(requestedUrl)
                .finalUrl(response.url())
                .httpStatus(response.status())
                .responseHeaders(lowerCased(response.allHeaders()))
                .redirectChain(redirectChain(response))
                .loadTimeMillis(loadMillis)
                .title(dom.title())
                .htmlLang(dom.htmlLang())
                .canonicalUrl(dom.canonical())
                .textContent(truncate(dom.text(), options.maxTextLength()))
                .links(dom.links().stream()
                        .map(l -> new LinkRef(l.href(), l.resolved(), l.text(), l.rel(), l.target())).toList())
                .images(dom.images().stream()
                        .map(i -> new ImageRef(i.source(), i.resolved(), ImageOrigin.valueOf(i.origin()),
                                i.naturalWidth(), i.naturalHeight(), i.alt())).toList())
                .media(dom.media().stream()
                        .map(m -> new MediaRef(MediaKind.valueOf(m.kind()), m.sources(), m.readyState(),
                                m.duration(), m.error())).toList())
                .iframes(iframes(page, dom, documentHeaders))
                .forms(dom.forms().stream()
                        .map(f -> new FormRef(f.action(), f.resolved(), f.method(),
                                f.fields().stream().map(field -> new FormFieldRef(field.name(), field.type(),
                                        field.label(), field.autocomplete(), field.required())).toList()))
                        .toList())
                .hreflangs(dom.hreflangs().stream()
                        .map(h -> new HreflangRef(h.lang(), h.href(), h.resolved())).toList())
                .subresources(List.copyOf(subresources))
                .consoleMessages(List.copyOf(console))
                .networkFailures(List.copyOf(failures))
                .screenshotPath(screenshot)
                .capturedAt(Instant.now())
                .build();

        return PageCapture.ok(snapshot);
    }

    /**
     * Cross-origin iframes are opaque to the parent document's JavaScript, but not to
     * Playwright: {@link Page#frames()} reaches into them. That is how a Google Maps embed
     * gets judged on whether its canvas painted rather than on whether the iframe loaded.
     */
    private List<IframeRef> iframes(Page page, RawDom dom, Map<String, Map<String, String>> documentHeaders) {
        List<IframeRef> result = new ArrayList<>();
        for (RawIframe raw : dom.iframes()) {
            Frame frame = raw.resolved() == null ? null : page.frames().stream()
                    .filter(candidate -> candidate != page.mainFrame())
                    .filter(candidate -> candidate.url().equals(raw.resolved()))
                    .findFirst().orElse(null);

            int canvasArea = 0;
            int textLength = 0;
            boolean bodyNonEmpty = raw.bodyNonEmpty();
            if (frame != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> measured = (Map<String, Object>) frame.evaluate(CANVAS_AREA_SCRIPT);
                    canvasArea = ((Number) measured.getOrDefault("area", 0)).intValue();
                    textLength = ((Number) measured.getOrDefault("text", 0)).intValue();
                    bodyNonEmpty = bodyNonEmpty || canvasArea > 0 || textLength > 0;
                } catch (PlaywrightException e) {
                    log.debug("iframe {} nicht auswertbar: {}", raw.resolved(), e.getMessage());
                }
            }
            Map<String, String> headers = documentHeaders.getOrDefault(raw.resolved(), Map.of());
            result.add(new IframeRef(raw.src(), raw.resolved(), raw.title(), frame != null,
                    bodyNonEmpty, textLength, canvasArea, raw.width(), raw.height(),
                    headers.get("x-frame-options"), headers.get("content-security-policy")));
        }
        return result;
    }

    private static List<RedirectHop> redirectChain(Response response) {
        Deque<RedirectHop> hops = new ArrayDeque<>();
        Request request = response.request().redirectedFrom();
        while (request != null && hops.size() < 20) {
            Response hopResponse = request.response();
            hops.addFirst(new RedirectHop(request.url(),
                    hopResponse == null ? 0 : hopResponse.status(),
                    hopResponse == null ? null : lowerCased(hopResponse.allHeaders()).get("location")));
            request = request.redirectedFrom();
        }
        return new ArrayList<>(hops);
    }

    private String screenshot(Page page, CaptureOptions options) {
        try {
            Path target = options.artifactDirectory()
                    .resolve(UUID.randomUUID().toString().substring(0, 8) + ".png");
            java.nio.file.Files.createDirectories(options.artifactDirectory());
            page.screenshot(new Page.ScreenshotOptions().setPath(target).setFullPage(false));
            return target.toString();
        } catch (Exception e) {
            log.debug("Screenshot fehlgeschlagen: {}", e.getMessage());
            return null;
        }
    }

    private static Map<String, String> lowerCased(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
        return result;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ------------------------------------------------- JSON shapes, internal to this class

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawDom(String title, String htmlLang, String canonical, String text,
                          List<RawLink> links, List<RawImage> images, List<RawMedia> media,
                          List<RawIframe> iframes, List<RawForm> forms, List<RawHreflang> hreflangs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawLink(String href, String resolved, String text, String rel, String target) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawImage(String source, String resolved, String origin, Integer naturalWidth,
                            Integer naturalHeight, String alt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawMedia(String kind, List<String> sources, int readyState, double duration, String error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawIframe(String src, String resolved, String title, boolean sameOrigin,
                             boolean bodyNonEmpty, int width, int height) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawForm(String action, String resolved, String method, List<RawField> fields) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawField(String name, String type, String label, String autocomplete, boolean required) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawHreflang(String lang, String href, String resolved) {
    }
}
```

- [ ] **Step 9: Run the extractor test to verify it passes**

Run: `./mvnw test -Dtest=PageSnapshotExtractorIT`
Expected: PASS, all ten cases. First run downloads nothing if Task 1 Step 4 succeeded.

If `waitsForMediaMetadataBeforeReadingReadyState` is flaky, raise the in-script
`setTimeout(done, 4000)` — the audio fixture is ~1 s of 8 kHz PCM and should decode in
milliseconds, so persistent failure means the WAV header is malformed rather than slow.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/{model,runner,crawler} src/test/java/dev/hendrikhoemberg/webtesthelper/{runner,crawler}
git commit -m "feat: add PageSnapshot, thread-confined browser pool and DOM extraction

Each worker creates its own Playwright and Browser on its own platform
thread and never shares them (spec 5.4). One evaluate() per page produces
the whole snapshot; every check downstream is a pure function of it."
```

---

### Task 8: The check SPI, the registry, and the build-failing documentation test

Three evaluation contracts over one shared descriptor (§7.3). Checks emit the transient
`CheckFinding`; the persistent `Finding` entity is created only at materialisation (Task 16),
which is the only point where fingerprints and site-wide promotion can be computed. Keeping
the two types apart is what stops checks from needing any knowledge of identity or lifecycle.

`CheckConfig` carries both the site's persisted settings **and** the run's facts — see
deviation D3. That is what lets `evaluate(PageSnapshot, CheckConfig)` stay exactly as §7.3
writes it while `PAGE_STATUS` still sees the soft-404 probe and `DEAD_LINK` still sees the
verification verdicts.

`InteractionCheck` is **not** defined here. Its signature takes a live Playwright `Page`,
which would put the browser on `checks`'s classpath for no Phase 1 benefit; it arrives with
Phase 3.

**Files:**
- Create: `src/main/java/.../model/Evidence.java`, `CheckFinding.java`, `CheckConfig.java`,
  `RunFacts.java`, `UrlOutcome.java`, `UrlVerdict.java`, `Soft404Probe.java`,
  `TextFingerprint.java`
- Create: `src/main/java/.../checks/package-info.java`, `CheckDescriptor.java`,
  `PageCheck.java`, `SiteCheck.java`, `CheckRegistry.java`
- Create: `src/main/resources/messages_de.properties`
- Modify: `src/main/java/.../runner/package-info.java` (add `"checks"`)
- Test: `src/test/java/.../checks/CheckDocumentationTest.java`
- Test: `src/test/java/.../checks/CheckPurityTest.java`
- Test: `src/test/java/.../model/TextFingerprintTest.java`

**Interfaces:**
- Consumes: `model.CheckType`, `model.Severity`, `model.PageSnapshot`, `model.RunSnapshots`,
  `model.SiteContext`.
- Produces:
  - `CheckDescriptor` with `type()`, `defaultSeverity()`, and default `titleKey()`,
    `descriptionKey()`, `remediationKey()`, `emittedMessageKeys()`.
  - `PageCheck.evaluate(PageSnapshot, CheckConfig) -> List<CheckFinding>`
  - `SiteCheck.evaluate(RunSnapshots, SiteContext, CheckConfig) -> List<CheckFinding>`
  - `CheckFinding(CheckType, Severity, String subjectKey, String observedPageUrl,
    String messageKey, List<String> messageArgs, Evidence evidence)`
  - `CheckConfig(Map<String,Object> settings, RunFacts facts)` with `string/integer/flag/number/strings`
  - `RunFacts` with `soft404()`, `verdict(String)`; `RunFacts.NONE`, `RunFacts.of(...)`
  - `UrlVerdict`, `UrlOutcome`, `Soft404Probe`, `TextFingerprint.simhash/hammingDistance`
  - `CheckRegistry.pageChecks()`, `siteChecks()`, `descriptors()`, `descriptor(CheckType)`

- [ ] **Step 1: Write the failing tests**

`src/test/java/.../model/TextFingerprintTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextFingerprintTest {

    @Test
    void identicalTextHasIdenticalFingerprints() {
        long a = TextFingerprint.simhash("Die gewünschte Seite existiert leider nicht.");
        long b = TextFingerprint.simhash("Die gewünschte Seite existiert leider nicht.");
        assertThat(TextFingerprint.hammingDistance(a, b)).isZero();
    }

    @Test
    void nearlyIdenticalTextStaysClose() {
        // The realistic soft-404 case: same template, different path echoed back.
        long probe = TextFingerprint.simhash(
                "Seite nicht gefunden Die gewünschte Seite /a1b2c3 existiert leider nicht. Zurück zur Startseite");
        long candidate = TextFingerprint.simhash(
                "Seite nicht gefunden Die gewünschte Seite /leistungen-alt existiert leider nicht. Zurück zur Startseite");
        assertThat(TextFingerprint.hammingDistance(probe, candidate)).isLessThanOrEqualTo(8);
    }

    @Test
    void unrelatedTextIsFarApart() {
        long probe = TextFingerprint.simhash(
                "Seite nicht gefunden Die gewünschte Seite existiert leider nicht");
        long real = TextFingerprint.simhash(
                "Leistungen Beratung Planung Umsetzung Wir begleiten Ihr Bauvorhaben von Anfang an");
        assertThat(TextFingerprint.hammingDistance(probe, real)).isGreaterThan(12);
    }

    @Test
    void emptyTextFingerprintsToZero() {
        assertThat(TextFingerprint.simhash("   ")).isZero();
    }
}
```

`src/test/java/.../checks/CheckDocumentationTest.java` — **this is one of the two
build-failing enforcement tests of §13.7**:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Spec 13.1 and 13.7: adding a check without its plain-language explanation must be
 * impossible. This test is the mechanism. If it fails, write the German copy — do not
 * weaken the test.
 */
class CheckDocumentationTest extends AbstractPostgresTest {

    /** German is the only supported locale (spec 12); the loop exists so adding one is safe. */
    private static final List<Locale> SUPPORTED = List.of(Locale.GERMAN);

    @Autowired
    MessageSource messages;

    @Autowired
    CheckRegistry registry;

    @ParameterizedTest
    @EnumSource(CheckType.class)
    void everyCheckTypeHasTitleDescriptionAndRemediationInEveryLocale(CheckType type) {
        for (Locale locale : SUPPORTED) {
            for (String suffix : List.of("title", "description", "remediation")) {
                String key = "check." + type.name() + "." + suffix;
                assertThatCode(() -> messages.getMessage(key, null, locale))
                        .describedAs("Fehlender Text für %s", key)
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void everyRegisteredCheckResolvesItsThreeKeys() {
        assertThat(registry.descriptors()).allSatisfy(descriptor -> {
            for (Locale locale : SUPPORTED) {
                messages.getMessage(descriptor.titleKey(), null, locale);
                messages.getMessage(descriptor.descriptionKey(), null, locale);
                messages.getMessage(descriptor.remediationKey(), null, locale);
            }
        });
    }

    @Test
    void everyMessageKeyACheckSaysItCanEmitAlsoResolves() {
        assertThat(registry.descriptors()).allSatisfy(descriptor ->
                descriptor.emittedMessageKeys().forEach(key -> {
                    for (Locale locale : SUPPORTED) {
                        try {
                            messages.getMessage(key, new Object[]{"a", "b", "c", "d"}, locale);
                        } catch (NoSuchMessageException e) {
                            throw new AssertionError("Fehlender Text für " + key
                                    + " (deklariert von " + descriptor.type() + ")", e);
                        }
                    }
                }));
    }

    @Test
    void noTwoChecksClaimTheSameCheckType() {
        assertThat(registry.descriptors()).extracting(CheckDescriptor::type).doesNotHaveDuplicates();
    }
}
```

`src/test/java/.../checks/CheckPurityTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Spec 5.1: checks and findings "depend only on their own value types — no Spring, no
 * database, no browser". Spring stereotype annotations are exempt (auto-registration by
 * interface is a spec 7.3 requirement); Spring's data, JDBC and web machinery is not.
 */
class CheckPurityTest {

    private static final JavaClasses CLASSES =
            new ClassFileImporter().importPackages("dev.hendrikhoemberg.webtesthelper");

    private static final String[] FORBIDDEN = {
            "jakarta.persistence..", "org.springframework.data..", "org.springframework.jdbc..",
            "org.springframework.web..", "org.springframework.transaction..",
            "com.microsoft.playwright.."};

    @Test
    void checksStayPure() {
        noClasses().that().resideInAPackage("..webtesthelper.checks..")
                .should().dependOnClassesThat().resideInAnyPackage(FORBIDDEN)
                .because("Prüfungen müssen ohne Datenbank und ohne Browser testbar bleiben")
                .check(CLASSES);
    }

    @Test
    void findingAlgorithmsStayPure() {
        noClasses().that().resideInAPackage("..webtesthelper.findings.core..")
                .should().dependOnClassesThat().resideInAnyPackage(FORBIDDEN)
                .because("Fingerprinting und Diff müssen reine Funktionen bleiben")
                .check(CLASSES);
    }

    @Test
    void valueTypesDependOnNoFrameworkAtAll() {
        noClasses().that().resideInAPackage("..webtesthelper.model..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "com.microsoft.playwright..")
                .check(CLASSES);
    }
}
```

> `findingAlgorithmsStayPure` passes vacuously until Task 14 creates `findings.core`. That
> is fine — it is a guard rail placed before the code it guards, not a coverage claim.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=TextFingerprintTest,CheckDocumentationTest,CheckPurityTest`
Expected: compilation failure — `TextFingerprint` and the `checks` package do not exist.

- [ ] **Step 3: Write the shared check value types**

`model/TextFingerprint.java`:

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SimHash over word trigrams. Used for soft-404 detection (spec 7.1): the runner fetches
 * {@code {baseUrl}/{random-uuid}} at crawl start and any later 200 whose text is close to
 * that probe is a soft 404.
 *
 * <p>SimHash rather than an exact hash because a real not-found page usually echoes the
 * requested path back, so no two are byte-identical; and rather than a stored shingle set
 * because 64 bits fit in one column and comparison is a {@code Long.bitCount}.
 */
public final class TextFingerprint {

    private static final int SHINGLE_SIZE = 3;

    private TextFingerprint() {
    }

    public static long simhash(String text) {
        List<String> shingles = shingles(text);
        if (shingles.isEmpty()) {
            return 0L;
        }
        int[] weights = new int[64];
        for (String shingle : shingles) {
            long hash = fnv1a64(shingle);
            for (int bit = 0; bit < 64; bit++) {
                weights[bit] += ((hash >>> bit) & 1L) == 1L ? 1 : -1;
            }
        }
        long fingerprint = 0L;
        for (int bit = 0; bit < 64; bit++) {
            if (weights[bit] > 0) {
                fingerprint |= 1L << bit;
            }
        }
        return fingerprint;
    }

    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    static List<String> shingles(String text) {
        if (text == null) {
            return List.of();
        }
        String cleaned = text.toLowerCase(Locale.GERMAN)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        String[] words = cleaned.split(" +");
        List<String> shingles = new ArrayList<>();
        if (words.length < SHINGLE_SIZE) {
            shingles.add(String.join(" ", words));
            return shingles;
        }
        for (int i = 0; i + SHINGLE_SIZE <= words.length; i++) {
            shingles.add(words[i] + " " + words[i + 1] + " " + words[i + 2]);
        }
        return shingles;
    }

    private static long fnv1a64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
```

`model/Soft404Probe.java`:

```java
package dev.hendrikhoemberg.webtesthelper.model;

/**
 * What the site's not-found page looks like, learned at crawl start by requesting
 * {@code {baseUrl}/{random-uuid}} (spec 7.1).
 */
public record Soft404Probe(String probeUrl, int httpStatus, long simhash, int textLength) {

    /** Default Hamming distance below which a 200 page counts as the not-found template. */
    public static final int DEFAULT_DISTANCE_THRESHOLD = 8;

    /** Only usable if the site answered 200 — a site returning a real 404 needs no heuristic. */
    public boolean usable() {
        return httpStatus == 200 && textLength > 0;
    }

    public boolean resembles(String text, int distanceThreshold) {
        if (!usable() || text == null || text.isBlank()) {
            return false;
        }
        if (TextFingerprint.hammingDistance(simhash, TextFingerprint.simhash(text)) > distanceThreshold) {
            return false;
        }
        // A long article that happens to hash close to a short error page is not a soft 404.
        double ratio = (double) text.length() / Math.max(1, textLength);
        return ratio > 0.4 && ratio < 2.5;
    }
}
```

`model/UrlOutcome.java` and `model/UrlVerdict.java`:

```java
package dev.hendrikhoemberg.webtesthelper.model;

/**
 * {@code UNVERIFIABLE} is deliberately distinct from {@code DEAD} (spec 8): a 403, 429 or
 * 999 means the target blocked our checker, not that the customer's link is broken.
 */
public enum UrlOutcome { OK, DEAD, UNVERIFIABLE }
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.time.Instant;
import java.util.Locale;

public record UrlVerdict(String normalizedUrl, UrlOutcome outcome, Integer httpStatus,
                         String finalUrl, String contentType, Long contentLength,
                         String magicBytes, int redirectHops, String errorMessage,
                         Instant checkedAt) {

    public static UrlVerdict ok(String url, int status, String finalUrl, String contentType,
                                Long length, String magicBytes, int hops) {
        return new UrlVerdict(url, UrlOutcome.OK, status, finalUrl, contentType, length,
                magicBytes, hops, null, Instant.now());
    }

    public static UrlVerdict dead(String url, Integer status, String error) {
        return new UrlVerdict(url, UrlOutcome.DEAD, status, null, null, null, null, 0,
                error, Instant.now());
    }

    public static UrlVerdict unverifiable(String url, Integer status, String error) {
        return new UrlVerdict(url, UrlOutcome.UNVERIFIABLE, status, null, null, null, null, 0,
                error, Instant.now());
    }

    public boolean ok() {
        return outcome == UrlOutcome.OK;
    }

    /** Content-type without its parameters, lowercased. */
    public String baseContentType() {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon))
                .trim().toLowerCase(Locale.ROOT);
    }

    /** A link returning 200 text/html is a login wall, not a PDF (spec 7.1). */
    public boolean looksLikePdf() {
        return magicBytes != null && magicBytes.startsWith("%PDF-");
    }
}
```

`model/Evidence.java`:

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What lets an employee judge a finding in five seconds instead of re-checking it by hand
 * (spec 8). Stored as jsonb, rendered above the technical detail on the finding screen.
 */
public record Evidence(Map<String, String> entries) {

    public static final String SCREENSHOT = "screenshot";
    public static final String HTTP_STATUS = "httpStatus";
    public static final String FINAL_URL = "finalUrl";
    public static final String CONTENT_TYPE = "contentType";
    public static final String CONTENT_LENGTH = "contentLength";
    public static final String REQUEST = "request";
    public static final String RESPONSE = "response";
    public static final String CONSOLE = "console";
    public static final String DETAIL = "detail";

    public Evidence {
        entries = entries == null ? Map.of() : Map.copyOf(entries);
    }

    public static Evidence empty() {
        return new Evidence(Map.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    public static final class Builder {
        private final Map<String, String> entries = new LinkedHashMap<>();

        public Builder put(String key, String value) {
            if (value != null && !value.isBlank()) {
                entries.put(key, value);
            }
            return this;
        }

        public Builder put(String key, Number value) {
            return value == null ? this : put(key, String.valueOf(value));
        }

        public Evidence build() {
            return new Evidence(entries);
        }
    }
}
```

`model/CheckFinding.java`:

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;

/**
 * What a check emits. Transient by design: it carries no fingerprint, no lifecycle and no
 * id, because a check cannot know whether its subject is site-wide — only materialisation
 * can, and only after the crawl (spec 6.2, 7.3).
 *
 * @param subjectKey      the broken thing, already normalised (a URL via
 *                        {@link UrlNormalizer#key}, or another stable identifier)
 * @param observedPageUrl the page it was seen on; becomes an occurrence and feeds
 *                        site-wide promotion
 */
public record CheckFinding(CheckType checkType, Severity severity, String subjectKey,
                           String observedPageUrl, String messageKey, List<String> messageArgs,
                           Evidence evidence) {

    public CheckFinding {
        messageArgs = messageArgs == null ? List.of() : List.copyOf(messageArgs);
        evidence = evidence == null ? Evidence.empty() : evidence;
    }

    public String locationKey() {
        return UrlNormalizer.locationKeyOf(observedPageUrl);
    }
}
```

`model/RunFacts.java` and `model/CheckConfig.java`:

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Map;
import java.util.Optional;

/**
 * Run-scoped facts a check may need but cannot derive from a single snapshot: the soft-404
 * probe (spec 7.1) and the URL verification verdicts (spec 5.3).
 *
 * <p>See plan deviations D2 and D3. Keeping these behind a tiny interface is what lets the
 * spec 7.3 signature {@code evaluate(PageSnapshot, CheckConfig)} stand unchanged, and lets
 * a unit test hand-build exactly the facts the check under test reads.
 */
public interface RunFacts {

    RunFacts NONE = new RunFacts() {
        @Override public Optional<Soft404Probe> soft404() { return Optional.empty(); }
        @Override public Optional<UrlVerdict> verdict(String normalizedUrl) { return Optional.empty(); }
    };

    Optional<Soft404Probe> soft404();

    Optional<UrlVerdict> verdict(String normalizedUrl);

    static RunFacts of(Soft404Probe probe, Map<String, UrlVerdict> verdicts) {
        Map<String, UrlVerdict> copy = Map.copyOf(verdicts);
        return new RunFacts() {
            @Override public Optional<Soft404Probe> soft404() { return Optional.ofNullable(probe); }
            @Override public Optional<UrlVerdict> verdict(String normalizedUrl) {
                return normalizedUrl == null ? Optional.empty() : Optional.ofNullable(copy.get(normalizedUrl));
            }
        };
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;
import java.util.Map;

/**
 * A check's configuration for this site, plus this run's facts (plan deviation D3). The
 * settings half is the jsonb column of {@code site_check_setting}; typed accessors keep
 * checks free of casting.
 */
public record CheckConfig(Map<String, Object> settings, RunFacts facts) {

    public CheckConfig {
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        facts = facts == null ? RunFacts.NONE : facts;
    }

    public static CheckConfig empty() {
        return new CheckConfig(Map.of(), RunFacts.NONE);
    }

    public static CheckConfig of(Map<String, Object> settings) {
        return new CheckConfig(settings, RunFacts.NONE);
    }

    public CheckConfig withFacts(RunFacts newFacts) {
        return new CheckConfig(settings, newFacts);
    }

    public String string(String key, String fallback) {
        Object value = settings.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public int integer(String key, int fallback) {
        Object value = settings.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public double number(String key, double fallback) {
        Object value = settings.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean flag(String key, boolean fallback) {
        Object value = settings.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value).trim());
    }

    /** Ignore-pattern lists arrive as JSON arrays; a comma-separated string is accepted too. */
    public List<String> strings(String key) {
        Object value = settings.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.split("\\s*,\\s*"));
        }
        return List.of();
    }
}
```

- [ ] **Step 4: Write the SPI and registry**

`checks/CheckDescriptor.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.Set;

/**
 * The contract every check of every kind shares (spec 7.3). The three message keys are what
 * {@code CheckDocumentationTest} walks — spec 13.7's defence against documentation rot.
 *
 * <p>The keys are derived from the type rather than typed out per check: a typo in a hand-
 * written key would resolve to a missing bundle entry, which is exactly the failure the
 * enforcement test exists to prevent, so removing the opportunity beats catching it.
 */
public interface CheckDescriptor {

    CheckType type();

    Severity defaultSeverity();

    default String titleKey() {
        return "check." + type().name() + ".title";
    }

    default String descriptionKey() {
        return "check." + type().name() + ".description";
    }

    default String remediationKey() {
        return "check." + type().name() + ".remediation";
    }

    /**
     * Every {@code finding.*} key this check can put on a {@code CheckFinding}. Declaring
     * them lets the documentation test verify the finding copy too, not just the check copy.
     */
    default Set<String> emittedMessageKeys() {
        return Set.of();
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckConfig;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;

import java.util.List;

/** A pure function from one page's snapshot to findings (spec 5.2). Never drives the browser. */
public interface PageCheck extends CheckDescriptor {

    List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config);
}
```

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckConfig;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;

import java.util.List;

/** Needs cross-page knowledge: hreflang reciprocity, sitemap consistency, TLS (spec 5.2). */
public interface SiteCheck extends CheckDescriptor {

    List<CheckFinding> evaluate(RunSnapshots snapshots, SiteContext site, CheckConfig config);
}
```

`checks/CheckRegistry.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implementations register themselves by their interface (spec 7.3) — adding the twelfth
 * check must not require touching the runner. Spring injects every {@link PageCheck} and
 * {@link SiteCheck} bean on the classpath.
 */
@Component
public class CheckRegistry {

    private final Map<CheckType, PageCheck> pageChecks = new EnumMap<>(CheckType.class);
    private final Map<CheckType, SiteCheck> siteChecks = new EnumMap<>(CheckType.class);

    public CheckRegistry(List<PageCheck> pageChecks, List<SiteCheck> siteChecks) {
        pageChecks.forEach(check -> register(this.pageChecks, check.type(), check));
        siteChecks.forEach(check -> register(this.siteChecks, check.type(), check));
    }

    private static <T> void register(Map<CheckType, T> target, CheckType type, T check) {
        T previous = target.putIfAbsent(type, check);
        if (previous != null) {
            throw new IllegalStateException("Zwei Prüfungen beanspruchen " + type + ": "
                    + previous.getClass().getName() + " und " + check.getClass().getName());
        }
    }

    public Collection<PageCheck> pageChecks() {
        return List.copyOf(pageChecks.values());
    }

    public Collection<SiteCheck> siteChecks() {
        return List.copyOf(siteChecks.values());
    }

    public List<CheckDescriptor> descriptors() {
        List<CheckDescriptor> all = new ArrayList<>(pageChecks.values());
        all.addAll(siteChecks.values());
        return List.copyOf(all);
    }

    public Optional<CheckDescriptor> descriptor(CheckType type) {
        CheckDescriptor descriptor = pageChecks.get(type);
        return Optional.ofNullable(descriptor != null ? descriptor : siteChecks.get(type));
    }
}
```

`checks/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Checks",
        allowedDependencies = {"model"})
package dev.hendrikhoemberg.webtesthelper.checks;
```

Then add `"checks"` to `runner/package-info.java`'s `allowedDependencies`.

- [ ] **Step 5: Write the German check copy**

`src/main/resources/messages_de.properties` — UTF-8, written literally; Spring Boot reads
message bundles as UTF-8 given the `spring.messages.encoding` set in Task 1.

```properties
# --- Prüfungen: Titel, Erklärung, Handlungsempfehlung (Spezifikation 13.1) ---
check.PAGE_STATUS.title=Seitenstatus
check.PAGE_STATUS.description=Prüft, ob jede Seite mit einem regulären Erfolgsstatus antwortet und keine getarnte Fehlerseite ausliefert.
check.PAGE_STATUS.remediation=Rufen Sie die Seite im Browser auf. Fehlt der Inhalt, wurde er meist gelöscht oder umbenannt; richten Sie eine Weiterleitung auf die neue Adresse ein.

check.PAGE_UNREACHABLE.title=Seite nicht erreichbar
check.PAGE_UNREACHABLE.description=Prüft, ob die Seite überhaupt geladen werden kann, ohne dass die Verbindung abbricht oder in eine Zeitüberschreitung läuft.
check.PAGE_UNREACHABLE.remediation=Prüfen Sie, ob der Webserver erreichbar ist und die Seite nicht dauerhaft überlastet ist. Tritt der Fehler nur einmalig auf, war es meist eine kurzzeitige Störung.

check.DEAD_LINK.title=Tote Links
check.DEAD_LINK.description=Prüft, ob alle verlinkten Ziele – innerhalb der Website wie auch nach außen – noch erreichbar sind.
check.DEAD_LINK.remediation=Korrigieren Sie das Linkziel oder entfernen Sie den Verweis. Bei fremden Websites prüfen Sie, ob die Seite umgezogen ist.

check.REDIRECT_CHAIN.title=Weiterleitungsketten
check.REDIRECT_CHAIN.description=Prüft, ob Weiterleitungen direkt zum Ziel führen und sich nicht im Kreis drehen.
check.REDIRECT_CHAIN.remediation=Lassen Sie die Weiterleitung direkt auf die endgültige Adresse zeigen. Eine Schleife muss aufgelöst werden, sonst ist die Seite gar nicht erreichbar.

check.IMAGE_BROKEN.title=Fehlende Bilder
check.IMAGE_BROKEN.description=Prüft, ob alle Bilder tatsächlich angezeigt werden – auch solche aus srcset-Angaben und CSS-Hintergründen.
check.IMAGE_BROKEN.remediation=Laden Sie die Bilddatei erneut hoch oder korrigieren Sie den Dateipfad. Achten Sie auf Groß- und Kleinschreibung im Dateinamen.

check.FILE_DOWNLOAD.title=Datei-Downloads
check.FILE_DOWNLOAD.description=Prüft, ob verlinkte Dateien wirklich als Datei ausgeliefert werden und nicht als Fehler- oder Anmeldeseite.
check.FILE_DOWNLOAD.remediation=Prüfen Sie, ob die Datei noch existiert und öffentlich zugänglich ist. Eine Anmeldeseite statt der Datei deutet auf falsche Zugriffsrechte hin.

check.MEDIA_PLAYABLE.title=Video und Audio
check.MEDIA_PLAYABLE.description=Prüft, ob eingebundene Videos und Audiodateien vorhanden sind und sich abspielen lassen.
check.MEDIA_PLAYABLE.remediation=Prüfen Sie die Quelldatei und ihr Format. Ein nicht abspielbares Video liegt oft in einem Format vor, das Browser nicht unterstützen.

check.IFRAME_EMBED.title=Eingebettete Inhalte
check.IFRAME_EMBED.description=Prüft, ob eingebettete Inhalte wie Karten oder Videos wirklich dargestellt werden und nicht vom Anbieter blockiert sind.
check.IFRAME_EMBED.remediation=Bei Google Maps prüfen Sie API-Schlüssel und Abrechnung in der Google Cloud Console. Sonst prüfen Sie, ob der Anbieter das Einbetten erlaubt.

check.MIXED_CONTENT.title=Unsichere Inhalte
check.MIXED_CONTENT.description=Prüft, ob eine verschlüsselte Seite Inhalte unverschlüsselt nachlädt – Browser blockieren diese und zeigen eine Warnung.
check.MIXED_CONTENT.remediation=Stellen Sie die betroffenen Adressen von http auf https um. Liegt die Datei auf der eigenen Website, genügt eine relative Adresse.

check.CONSOLE_ERRORS.title=JavaScript-Fehler
check.CONSOLE_ERRORS.description=Prüft, ob beim Laden der Seite JavaScript-Fehler auftreten. Standardmäßig ausgeschaltet, weil fremde Skripte ständig harmlose Meldungen erzeugen.
check.CONSOLE_ERRORS.remediation=Lassen Sie die Meldung von der Person prüfen, die die Website betreut. Bekannte harmlose Meldungen können in den Einstellungen ausgeblendet werden.

check.TLS_CERT.title=TLS-Zertifikat
check.TLS_CERT.description=Prüft, ob das Sicherheitszertifikat der Website gültig ist und nicht demnächst abläuft.
check.TLS_CERT.remediation=Erneuern Sie das Zertifikat rechtzeitig. Läuft es ab, zeigen Browser eine Sicherheitswarnung statt der Website.

check.HREFLANG.title=Sprachverweise
check.HREFLANG.description=Prüft, ob die Verweise auf andere Sprachfassungen erreichbar sind und wechselseitig aufeinander zeigen.
check.HREFLANG.remediation=Ergänzen Sie den fehlenden Rückverweis oder korrigieren Sie die Adresse. Jede Sprachfassung muss auf alle anderen verweisen.

check.SITEMAP_CONSISTENCY.title=Sitemap
check.SITEMAP_CONSISTENCY.description=Prüft, ob alle Adressen aus der Sitemap erreichbar sind und ob gefundene Seiten in der Sitemap fehlen. Standardmäßig ausgeschaltet.
check.SITEMAP_CONSISTENCY.remediation=Lassen Sie die Sitemap neu erzeugen. Fehlen Seiten dauerhaft, prüfen Sie die Einstellungen des Redaktionssystems.
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=TextFingerprintTest,CheckDocumentationTest,CheckPurityTest,ModularityTest`
Expected: PASS. The registry-walking cases pass over an empty registry for now; the
`@EnumSource` case is what carries the weight until Task 9.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/{model,checks,runner} src/main/resources/messages_de.properties src/test/java/dev/hendrikhoemberg/webtesthelper/{checks,model}
git commit -m "feat(checks): add the check SPI, registry and documentation enforcement

CheckConfig carries the site's settings plus the run's facts so the spec 7.3
signature stands unchanged. CheckDocumentationTest fails the build when a
check has no German copy (spec 13.7)."
```

---

### Task 9: Page checks, group A — `PAGE_STATUS`, `REDIRECT_CHAIN`, `MIXED_CONTENT`

Three pure functions over a snapshot. Every test hand-builds its input with
`PageSnapshot.builderFor(...)` — no browser, no database, which is the whole point of §5.2.

**`PAGE_UNREACHABLE` has no check class.** A page that could not be loaded produces no
snapshot, so there is nothing for a `PageCheck` to evaluate. The run orchestrator (Task 17)
emits it directly from a failed capture, using
`site.severityFor(CheckType.PAGE_UNREACHABLE, Severity.ERROR)` and the message key
`finding.PAGE_UNREACHABLE.capture_failed`. Its three `check.*` bundle entries already exist
from Task 8, so `CheckDocumentationTest` stays satisfied.

**Files:**
- Create: `src/main/java/.../checks/page/PageStatusCheck.java`
- Create: `src/main/java/.../checks/page/RedirectChainCheck.java`
- Create: `src/main/java/.../checks/page/MixedContentCheck.java`
- Modify: `src/main/resources/messages_de.properties`
- Test: `src/test/java/.../checks/page/PageStatusCheckTest.java`
- Test: `src/test/java/.../checks/page/RedirectChainCheckTest.java`
- Test: `src/test/java/.../checks/page/MixedContentCheckTest.java`

> `checks.page` is a sub-package of the `checks` module and therefore internal to it. That is
> correct: nothing outside `checks` ever names a check class — the runner reaches them through
> `CheckRegistry`, which lives in the module's base package.

**Interfaces:**
- Consumes: `checks.PageCheck`, `model.PageSnapshot`, `model.CheckConfig`, `model.RunFacts`,
  `model.Soft404Probe`, `model.UrlNormalizer`.
- Produces: three `@Component` `PageCheck` beans. No new public types.

- [ ] **Step 1: Write the failing tests**

`PageStatusCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PageStatusCheckTest {

    private final PageStatusCheck check = new PageStatusCheck();

    private static final String NOT_FOUND_TEXT =
            "Seite nicht gefunden Die gewünschte Seite existiert leider nicht Zurück zur Startseite";

    private static PageSnapshot snapshot(int status, String text) {
        return PageSnapshot.builderFor("https://example.com/leistungen")
                .finalUrl("https://example.com/leistungen")
                .httpStatus(status)
                .textContent(text)
                .build();
    }

    private static CheckConfig withProbe(String probeText) {
        Soft404Probe probe = new Soft404Probe("https://example.com/1f2e3d", 200,
                TextFingerprint.simhash(probeText), probeText.length());
        return CheckConfig.empty().withFacts(RunFacts.of(probe, Map.of()));
    }

    @Test
    void aHealthyPageProducesNothing() {
        assertThat(check.evaluate(snapshot(200, "Leistungen Beratung Planung Umsetzung"),
                withProbe(NOT_FOUND_TEXT))).isEmpty();
    }

    @Test
    void anErrorStatusIsReportedWithTheStatusAsAnArgument() {
        List<CheckFinding> findings = check.evaluate(snapshot(500, "Interner Fehler"), CheckConfig.empty());

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.checkType()).isEqualTo(CheckType.PAGE_STATUS);
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.messageKey()).isEqualTo("finding.PAGE_STATUS.http_error");
            assertThat(finding.messageArgs()).containsExactly("500");
            assertThat(finding.subjectKey()).isEqualTo("https://example.com/leistungen");
            assertThat(finding.observedPageUrl()).isEqualTo("https://example.com/leistungen");
            assertThat(finding.evidence().get(Evidence.HTTP_STATUS)).hasValue("500");
        });
    }

    @Test
    void aTwoHundredThatLooksLikeTheProbeIsASoftFourOhFour() {
        List<CheckFinding> findings = check.evaluate(
                snapshot(200, "Seite nicht gefunden Die gewünschte Seite existiert leider nicht Zurück zur Startseite"),
                withProbe(NOT_FOUND_TEXT));

        assertThat(findings).singleElement()
                .satisfies(finding -> assertThat(finding.messageKey())
                        .isEqualTo("finding.PAGE_STATUS.soft_404"));
    }

    @Test
    void withoutAUsableProbeNoSoftFourOhFourIsEverReported() {
        // A site that answers a real 404 to the probe needs no heuristic — and must not get one.
        Soft404Probe realFourOhFour = new Soft404Probe("https://example.com/1f2e3d", 404, 0L, 0);
        CheckConfig config = CheckConfig.empty().withFacts(RunFacts.of(realFourOhFour, Map.of()));

        assertThat(check.evaluate(snapshot(200, NOT_FOUND_TEXT), config)).isEmpty();
    }

    @Test
    void aStatusOnTheSitesIgnoreListIsNotReported() {
        CheckConfig config = CheckConfig.of(Map.of("ignoreStatuses", List.of("401", "403")));

        assertThat(check.evaluate(snapshot(403, "Zugriff verweigert"), config)).isEmpty();
    }

    @Test
    void theSoftFourOhFourThresholdIsConfigurable() {
        CheckConfig strict = new CheckConfig(Map.of("soft404DistanceThreshold", 0),
                withProbe(NOT_FOUND_TEXT).facts());

        assertThat(check.evaluate(snapshot(200,
                "Seite nicht gefunden Die gewünschte Seite /andere existiert leider nicht Zurück zur Startseite"),
                strict)).isEmpty();
    }
}
```

`RedirectChainCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectChainCheckTest {

    private final RedirectChainCheck check = new RedirectChainCheck();

    private static PageSnapshot withChain(String finalUrl, List<RedirectHop> hops) {
        return PageSnapshot.builderFor(hops.isEmpty() ? finalUrl : hops.getFirst().url())
                .finalUrl(finalUrl)
                .httpStatus(200)
                .redirectChain(hops)
                .build();
    }

    @Test
    void noRedirectIsNoFinding() {
        assertThat(check.evaluate(withChain("https://example.com/a", List.of()), CheckConfig.empty()))
                .isEmpty();
    }

    @Test
    void aSingleHopIsFine() {
        assertThat(check.evaluate(withChain("https://example.com/neu",
                List.of(new RedirectHop("https://example.com/alt", 301, "/neu"))), CheckConfig.empty()))
                .isEmpty();
    }

    @Test
    void aChainLongerThanTheLimitIsAWarning() {
        List<RedirectHop> hops = List.of(
                new RedirectHop("https://example.com/1", 302, "/2"),
                new RedirectHop("https://example.com/2", 302, "/3"),
                new RedirectHop("https://example.com/3", 302, "/4"),
                new RedirectHop("https://example.com/4", 302, "/ziel"));

        List<CheckFinding> findings = check.evaluate(
                withChain("https://example.com/ziel", hops), CheckConfig.empty());

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.messageKey()).isEqualTo("finding.REDIRECT_CHAIN.too_long");
            assertThat(finding.severity()).isEqualTo(Severity.WARN);
            assertThat(finding.messageArgs()).containsExactly("4", "3");
            assertThat(finding.subjectKey()).isEqualTo("https://example.com/1");
        });
    }

    @Test
    void aUrlAppearingTwiceIsALoopAndAnError() {
        List<RedirectHop> hops = List.of(
                new RedirectHop("https://example.com/a", 302, "/b"),
                new RedirectHop("https://example.com/b", 302, "/a"),
                new RedirectHop("https://example.com/a", 302, "/b"));

        List<CheckFinding> findings = check.evaluate(
                withChain("https://example.com/b", hops), CheckConfig.empty());

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.messageKey()).isEqualTo("finding.REDIRECT_CHAIN.loop");
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
        });
    }

    @Test
    void aLoopIsReportedInsteadOfNotAsWellAsALengthWarning() {
        List<RedirectHop> hops = List.of(
                new RedirectHop("https://example.com/a", 302, "/b"),
                new RedirectHop("https://example.com/b", 302, "/c"),
                new RedirectHop("https://example.com/c", 302, "/a"),
                new RedirectHop("https://example.com/a", 302, "/b"),
                new RedirectHop("https://example.com/b", 302, "/c"));

        assertThat(check.evaluate(withChain("https://example.com/c", hops), CheckConfig.empty()))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.messageKey()).isEqualTo("finding.REDIRECT_CHAIN.loop"));
    }

    @Test
    void theHopLimitIsConfigurable() {
        List<RedirectHop> hops = List.of(
                new RedirectHop("https://example.com/1", 302, "/2"),
                new RedirectHop("https://example.com/2", 302, "/ziel"));

        assertThat(check.evaluate(withChain("https://example.com/ziel", hops),
                CheckConfig.of(Map.of("maxHops", 1)))).hasSize(1);
    }
}
```

`MixedContentCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MixedContentCheckTest {

    private final MixedContentCheck check = new MixedContentCheck();

    @Test
    void anHttpPageIsNeverMixedContent() {
        PageSnapshot snapshot = PageSnapshot.builderFor("http://example.com/a")
                .finalUrl("http://example.com/a")
                .images(List.of(new ImageRef("x.png", "http://example.com/x.png",
                        ImageOrigin.IMG, 10, 10, "")))
                .build();

        assertThat(check.evaluate(snapshot, CheckConfig.empty())).isEmpty();
    }

    @Test
    void anInsecureImageOnAnHttpsPageIsAPassiveWarning() {
        PageSnapshot snapshot = PageSnapshot.builderFor("https://example.com/a")
                .finalUrl("https://example.com/a")
                .images(List.of(new ImageRef("bild.png", "http://cdn.example.net/bild.png",
                        ImageOrigin.IMG, 10, 10, "")))
                .build();

        assertThat(check.evaluate(snapshot, CheckConfig.empty())).singleElement().satisfies(finding -> {
            assertThat(finding.messageKey()).isEqualTo("finding.MIXED_CONTENT.passive");
            assertThat(finding.severity()).isEqualTo(Severity.WARN);
            assertThat(finding.subjectKey()).isEqualTo("http://cdn.example.net/bild.png");
        });
    }

    @Test
    void anInsecureScriptOrIframeIsAnActiveError() {
        PageSnapshot snapshot = PageSnapshot.builderFor("https://example.com/a")
                .finalUrl("https://example.com/a")
                .subresources(List.of(new SubresourceRef("http://cdn.example.net/tracker.js",
                        "script", 200, Map.of())))
                .iframes(List.of(new IframeRef("http://alt.example.net/x", "http://alt.example.net/x",
                        "", true, true, 20, 0, 600, 300, null, null)))
                .build();

        assertThat(check.evaluate(snapshot, CheckConfig.empty()))
                .hasSize(2)
                .allSatisfy(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.MIXED_CONTENT.active");
                    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
                });
    }

    @Test
    void plainLinksToHttpAreNotMixedContent() {
        // A link is a navigation, not a subresource — the browser does not block it.
        PageSnapshot snapshot = PageSnapshot.builderFor("https://example.com/a")
                .finalUrl("https://example.com/a")
                .links(List.of(new LinkRef("http://alt.example.net/", "http://alt.example.net/",
                        "Partner", "", "")))
                .build();

        assertThat(check.evaluate(snapshot, CheckConfig.empty())).isEmpty();
    }

    @Test
    void theSameInsecureUrlUsedTwiceIsReportedOnce() {
        PageSnapshot snapshot = PageSnapshot.builderFor("https://example.com/a")
                .finalUrl("https://example.com/a")
                .images(List.of(
                        new ImageRef("a", "http://cdn.example.net/bild.png", ImageOrigin.IMG, 1, 1, ""),
                        new ImageRef("b", "http://cdn.example.net/bild.png", ImageOrigin.CSS_BACKGROUND,
                                null, null, "")))
                .build();

        assertThat(check.evaluate(snapshot, CheckConfig.empty())).hasSize(1);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest='*CheckTest'`
Expected: compilation failure — the three check classes do not exist.

- [ ] **Step 3: Write `PageStatusCheck`**

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.checks.PageCheck;
import dev.hendrikhoemberg.webtesthelper.model.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 2xx, plus soft-404 detection (spec 7.1).
 *
 * <p>The soft 404 is the interesting half: a site that answers 200 with "Seite nicht
 * gefunden" passes every status check ever written. The runner learns what that page looks
 * like by fetching {@code {baseUrl}/{random-uuid}} at crawl start, and this check compares
 * every 200 against it.
 */
@Component
public class PageStatusCheck implements PageCheck {

    @Override
    public CheckType type() {
        return CheckType.PAGE_STATUS;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> emittedMessageKeys() {
        return Set.of("finding.PAGE_STATUS.http_error", "finding.PAGE_STATUS.soft_404");
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        int status = snapshot.httpStatus();
        if (config.strings("ignoreStatuses").contains(String.valueOf(status))) {
            return List.of();
        }
        String subject = UrlNormalizer.key(snapshot.finalUrl()).orElse(snapshot.finalUrl());

        if (status >= 400) {
            return List.of(new CheckFinding(type(), defaultSeverity(), subject, snapshot.finalUrl(),
                    "finding.PAGE_STATUS.http_error", List.of(String.valueOf(status)),
                    Evidence.builder()
                            .put(Evidence.HTTP_STATUS, status)
                            .put(Evidence.FINAL_URL, snapshot.finalUrl())
                            .put(Evidence.SCREENSHOT, snapshot.screenshotPath())
                            .build()));
        }

        if (status < 300) {
            int threshold = config.integer("soft404DistanceThreshold",
                    Soft404Probe.DEFAULT_DISTANCE_THRESHOLD);
            boolean softNotFound = config.facts().soft404()
                    .map(probe -> probe.resembles(snapshot.textContent(), threshold))
                    .orElse(false);
            if (softNotFound) {
                return List.of(new CheckFinding(type(), defaultSeverity(), subject, snapshot.finalUrl(),
                        "finding.PAGE_STATUS.soft_404", List.of(),
                        Evidence.builder()
                                .put(Evidence.HTTP_STATUS, status)
                                .put(Evidence.FINAL_URL, snapshot.finalUrl())
                                .put(Evidence.DETAIL, excerpt(snapshot.textContent()))
                                .put(Evidence.SCREENSHOT, snapshot.screenshotPath())
                                .build()));
            }
        }
        return List.of();
    }

    private static String excerpt(String text) {
        return text == null ? "" : text.substring(0, Math.min(300, text.length()));
    }
}
```

- [ ] **Step 4: Write `RedirectChainCheck`**

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.checks.PageCheck;
import dev.hendrikhoemberg.webtesthelper.model.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * No loops, no long hop chains (spec 7.1). Judges the page's own navigation only: a redirect
 * chain behind an internal link is found when that link's own page is crawled, and one
 * behind an external link is the other site's problem.
 *
 * <p>A loop suppresses the length warning — reporting both for one broken redirect is noise.
 */
@Component
public class RedirectChainCheck implements PageCheck {

    private static final int DEFAULT_MAX_HOPS = 3;

    @Override
    public CheckType type() {
        return CheckType.REDIRECT_CHAIN;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARN;
    }

    @Override
    public Set<String> emittedMessageKeys() {
        return Set.of("finding.REDIRECT_CHAIN.loop", "finding.REDIRECT_CHAIN.too_long");
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        List<RedirectHop> chain = snapshot.redirectChain();
        if (chain.isEmpty()) {
            return List.of();
        }
        String subject = UrlNormalizer.key(chain.getFirst().url()).orElse(chain.getFirst().url());

        List<String> visited = new ArrayList<>();
        chain.forEach(hop -> visited.add(UrlNormalizer.key(hop.url()).orElse(hop.url())));
        visited.add(UrlNormalizer.key(snapshot.finalUrl()).orElse(snapshot.finalUrl()));

        Set<String> distinct = new LinkedHashSet<>(visited);
        if (distinct.size() < visited.size()) {
            return List.of(new CheckFinding(type(), Severity.ERROR, subject, snapshot.finalUrl(),
                    "finding.REDIRECT_CHAIN.loop", List.of(String.join(" → ", visited)),
                    Evidence.builder().put(Evidence.DETAIL, String.join(" → ", visited)).build()));
        }

        int maxHops = config.integer("maxHops", DEFAULT_MAX_HOPS);
        if (chain.size() > maxHops) {
            return List.of(new CheckFinding(type(), defaultSeverity(), subject, snapshot.finalUrl(),
                    "finding.REDIRECT_CHAIN.too_long",
                    List.of(String.valueOf(chain.size()), String.valueOf(maxHops)),
                    Evidence.builder()
                            .put(Evidence.DETAIL, String.join(" → ", visited))
                            .put(Evidence.FINAL_URL, snapshot.finalUrl())
                            .build()));
        }
        return List.of();
    }
}
```

- [ ] **Step 5: Write `MixedContentCheck`**

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.checks.PageCheck;
import dev.hendrikhoemberg.webtesthelper.model.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * No http subresources on an https page (spec 7.1).
 *
 * <p>Split into active and passive because the consequences differ: browsers block scripts,
 * iframes and stylesheets outright, while images and media are usually auto-upgraded and
 * only sometimes fail. Reporting both at ERROR would put avoidable noise in the report.
 *
 * <p>Plain links are excluded on purpose — a link to an http page is a navigation, not a
 * subresource, and nothing blocks it.
 */
@Component
public class MixedContentCheck implements PageCheck {

    private static final Set<String> ACTIVE_RESOURCE_TYPES =
            Set.of("script", "stylesheet", "xhr", "fetch", "websocket", "eventsource");

    @Override
    public CheckType type() {
        return CheckType.MIXED_CONTENT;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> emittedMessageKeys() {
        return Set.of("finding.MIXED_CONTENT.active", "finding.MIXED_CONTENT.passive");
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.isSecure()) {
            return List.of();
        }
        // Insecure URL -> whether any of its uses is active. Active wins over passive.
        Map<String, Boolean> insecure = new LinkedHashMap<>();

        snapshot.images().forEach(image -> record(insecure, image.resolvedUrl(), false));
        snapshot.media().forEach(media -> media.sources()
                .forEach(source -> record(insecure, source, false)));
        snapshot.iframes().forEach(iframe -> record(insecure, iframe.resolvedUrl(), true));
        snapshot.subresources().forEach(subresource ->
                record(insecure, subresource.url(), ACTIVE_RESOURCE_TYPES.contains(subresource.resourceType())));

        List<CheckFinding> findings = new ArrayList<>();
        insecure.forEach((url, active) -> findings.add(new CheckFinding(
                type(),
                active ? Severity.ERROR : Severity.WARN,
                url,
                snapshot.finalUrl(),
                active ? "finding.MIXED_CONTENT.active" : "finding.MIXED_CONTENT.passive",
                List.of(url),
                Evidence.builder()
                        .put(Evidence.DETAIL, url)
                        .put(Evidence.FINAL_URL, snapshot.finalUrl())
                        .put(Evidence.SCREENSHOT, snapshot.screenshotPath())
                        .build())));
        return findings;
    }

    private static void record(Map<String, Boolean> insecure, String rawUrl, boolean active) {
        if (rawUrl == null || !rawUrl.startsWith("http://")) {
            return;
        }
        UrlNormalizer.key(rawUrl).ifPresent(key -> insecure.merge(key, active, (a, b) -> a || b));
    }
}
```

- [ ] **Step 6: Add the German finding copy**

Append to `src/main/resources/messages_de.properties`:

```properties
# --- Befunde ---
finding.PAGE_UNREACHABLE.capture_failed=Die Seite konnte nicht geladen werden: {0}
finding.PAGE_STATUS.http_error=Der Server antwortet mit Status {0} statt mit der Seite.
finding.PAGE_STATUS.soft_404=Die Seite meldet Erfolg, zeigt aber die Fehlerseite der Website an.
finding.REDIRECT_CHAIN.loop=Die Weiterleitung dreht sich im Kreis: {0}
finding.REDIRECT_CHAIN.too_long=Die Adresse wird {0}-mal weitergeleitet, erlaubt sind {1}.
finding.MIXED_CONTENT.active=Die verschlüsselte Seite lädt {0} unverschlüsselt nach. Browser blockieren diesen Inhalt.
finding.MIXED_CONTENT.passive=Die verschlüsselte Seite bindet {0} unverschlüsselt ein.
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=PageStatusCheckTest,RedirectChainCheckTest,MixedContentCheckTest,CheckDocumentationTest,CheckPurityTest`
Expected: PASS. `CheckDocumentationTest` is now non-vacuous — it walks three real
descriptors and their seven emitted message keys.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/checks src/main/resources/messages_de.properties src/test/java/dev/hendrikhoemberg/webtesthelper/checks
git commit -m "feat(checks): add PAGE_STATUS, REDIRECT_CHAIN and MIXED_CONTENT

Soft-404 detection compares each 200 against the run's random-uuid probe
via SimHash. Mixed content is split into active (blocked, ERROR) and
passive (upgraded, WARN)."
```

---

### Task 10: Page checks, group B — `IMAGE_BROKEN`, `MEDIA_PLAYABLE`, `CONSOLE_ERRORS`

`IMAGE_BROKEN` and `MEDIA_PLAYABLE` both reject the obvious implementation. A 200 on an
image URL does not mean the browser rendered it, and a 200 on a video source does not mean
the stream decodes — so the primary evidence is `naturalWidth > 0` and
`readyState >= 1 && duration > 0` (§7.1). URL verdicts are the *fallback*, used only where
the DOM has nothing to measure: `srcset` candidates and CSS backgrounds.

`CONSOLE_ERRORS` ships disabled (§7.1). Its subject key is deliberately fuzzy — a stack
trace containing a build hash or a line number would fingerprint differently every deploy
and the diff would be worthless.

**Files:**
- Create: `src/main/java/.../checks/page/ImageBrokenCheck.java`
- Create: `src/main/java/.../checks/page/MediaPlayableCheck.java`
- Create: `src/main/java/.../checks/page/ConsoleErrorsCheck.java`
- Modify: `src/main/resources/messages_de.properties`
- Test: `src/test/java/.../checks/page/ImageBrokenCheckTest.java`
- Test: `src/test/java/.../checks/page/MediaPlayableCheckTest.java`
- Test: `src/test/java/.../checks/page/ConsoleErrorsCheckTest.java`

**Interfaces:**
- Consumes: `checks.PageCheck`, `model.ImageRef`, `model.MediaRef`, `model.ConsoleMessageRef`,
  `model.RunFacts.verdict`.
- Produces: three `@Component` `PageCheck` beans.

- [ ] **Step 1: Write the failing tests**

`ImageBrokenCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImageBrokenCheckTest {

    private final ImageBrokenCheck check = new ImageBrokenCheck();

    private static PageSnapshot withImages(List<ImageRef> images) {
        return PageSnapshot.builderFor("https://example.com/bilder")
                .finalUrl("https://example.com/bilder").images(images).build();
    }

    private static CheckConfig withVerdicts(Map<String, UrlVerdict> verdicts) {
        return CheckConfig.empty().withFacts(RunFacts.of(null, verdicts));
    }

    @Test
    void aRenderedImgIsFine() {
        assertThat(check.evaluate(withImages(List.of(
                new ImageRef("logo.png", "https://example.com/logo.png", ImageOrigin.IMG, 120, 40, "Logo"))),
                CheckConfig.empty())).isEmpty();
    }

    @Test
    void anImgWithZeroNaturalWidthIsBrokenEvenWithoutAVerdict() {
        // The point of spec 7.1: measuring beats trusting the status code.
        List<CheckFinding> findings = check.evaluate(withImages(List.of(
                new ImageRef("fehlt.png", "https://example.com/fehlt.png", ImageOrigin.IMG, 0, 0, "Bild"))),
                CheckConfig.empty());

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.messageKey()).isEqualTo("finding.IMAGE_BROKEN.not_rendered");
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.subjectKey()).isEqualTo("https://example.com/fehlt.png");
        });
    }

    @Test
    void aSrcsetCandidateIsJudgedByItsVerdictBecauseItWasNeverMeasured() {
        Map<String, UrlVerdict> verdicts = Map.of(
                "https://example.com/logo2x.png", UrlVerdict.dead("https://example.com/logo2x.png", 404, null));

        List<CheckFinding> findings = check.evaluate(withImages(List.of(
                new ImageRef("logo2x.png", "https://example.com/logo2x.png", ImageOrigin.SRCSET,
                        null, null, ""))), withVerdicts(verdicts));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.messageKey()).isEqualTo("finding.IMAGE_BROKEN.unreachable");
            assertThat(finding.messageArgs()).containsExactly("404");
        });
    }

    @Test
    void aCssBackgroundWithoutAVerdictIsNotGuessedAbout() {
        assertThat(check.evaluate(withImages(List.of(
                new ImageRef("bg.png", "https://example.com/bg.png", ImageOrigin.CSS_BACKGROUND,
                        null, null, ""))), CheckConfig.empty())).isEmpty();
    }

    @Test
    void anUnverifiableImageIsReportedSeparatelyAtInfo() {
        Map<String, UrlVerdict> verdicts = Map.of("https://cdn.fremd.de/bild.png",
                UrlVerdict.unverifiable("https://cdn.fremd.de/bild.png", 403, "Forbidden"));

        assertThat(check.evaluate(withImages(List.of(
                new ImageRef("x", "https://cdn.fremd.de/bild.png", ImageOrigin.CSS_BACKGROUND,
                        null, null, ""))), withVerdicts(verdicts)))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.IMAGE_BROKEN.unverifiable");
                    assertThat(finding.severity()).isEqualTo(Severity.INFO);
                });
    }

    @Test
    void dataUrisAndEmptySourcesAreIgnored() {
        assertThat(check.evaluate(withImages(List.of(
                new ImageRef("d", "data:image/png;base64,AAAA", ImageOrigin.IMG, 0, 0, ""),
                new ImageRef("", null, ImageOrigin.IMG, 0, 0, ""))), CheckConfig.empty())).isEmpty();
    }

    @Test
    void theSameBrokenImageUsedTwiceOnOnePageIsReportedOnce() {
        assertThat(check.evaluate(withImages(List.of(
                new ImageRef("fehlt.png", "https://example.com/fehlt.png", ImageOrigin.IMG, 0, 0, ""),
                new ImageRef("fehlt.png", "https://example.com/fehlt.png", ImageOrigin.IMG, 0, 0, ""))),
                CheckConfig.empty())).hasSize(1);
    }
}
```

`MediaPlayableCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MediaPlayableCheckTest {

    private final MediaPlayableCheck check = new MediaPlayableCheck();

    private static PageSnapshot withMedia(List<MediaRef> media) {
        return PageSnapshot.builderFor("https://example.com/medien")
                .finalUrl("https://example.com/medien").media(media).build();
    }

    @Test
    void loadedMetadataMeansPlayable() {
        assertThat(check.evaluate(withMedia(List.of(new MediaRef(MediaKind.AUDIO,
                List.of("https://example.com/ton.wav"), 4, 1.2, null))), CheckConfig.empty())).isEmpty();
    }

    @Test
    void aZeroDurationIsNotPlayableEvenAtReadyStateFour() {
        assertThat(check.evaluate(withMedia(List.of(new MediaRef(MediaKind.VIDEO,
                List.of("https://example.com/film.mp4"), 4, 0.0, null))), CheckConfig.empty()))
                .singleElement().satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.MEDIA_PLAYABLE.not_playable"));
    }

    @Test
    void aDeadSourceIsReportedAgainstThatSourceRatherThanThePage() {
        Map<String, UrlVerdict> verdicts = Map.of("https://example.com/fehlt.mp3",
                UrlVerdict.dead("https://example.com/fehlt.mp3", 404, null));

        List<CheckFinding> findings = check.evaluate(withMedia(List.of(new MediaRef(MediaKind.VIDEO,
                List.of("https://example.com/fehlt.mp3"), 0, 0.0, "MEDIA_ERR_4"))),
                CheckConfig.empty().withFacts(RunFacts.of(null, verdicts)));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.messageKey()).isEqualTo("finding.MEDIA_PLAYABLE.source_unreachable");
            assertThat(finding.subjectKey()).isEqualTo("https://example.com/fehlt.mp3");
            assertThat(finding.messageArgs()).containsExactly("https://example.com/fehlt.mp3", "404");
        });
    }

    @Test
    void aMediaElementWithoutAnySourceIsKeyedOnThePageAndTheElementIndex() {
        assertThat(check.evaluate(withMedia(List.of(
                new MediaRef(MediaKind.VIDEO, List.of(), 0, 0.0, null))), CheckConfig.empty()))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.MEDIA_PLAYABLE.no_source");
                    assertThat(finding.subjectKey()).isEqualTo("https://example.com/medien#media-0");
                });
    }

    @Test
    void theMediaErrorCodeTravelsAsEvidence() {
        assertThat(check.evaluate(withMedia(List.of(new MediaRef(MediaKind.VIDEO,
                List.of("https://example.com/film.mp4"), 0, 0.0, "MEDIA_ERR_4"))), CheckConfig.empty()))
                .singleElement().satisfies(finding ->
                        assertThat(finding.evidence().get(Evidence.DETAIL)).hasValue("MEDIA_ERR_4"));
    }
}
```

`ConsoleErrorsCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleErrorsCheckTest {

    private final ConsoleErrorsCheck check = new ConsoleErrorsCheck();

    private static PageSnapshot withConsole(List<ConsoleMessageRef> messages) {
        return PageSnapshot.builderFor("https://example.com/a")
                .finalUrl("https://example.com/a").consoleMessages(messages).build();
    }

    @Test
    void warningsAndLogsAreIgnored() {
        assertThat(check.evaluate(withConsole(List.of(
                new ConsoleMessageRef("warning", "Veraltete API", ""),
                new ConsoleMessageRef("log", "Zustimmung erteilt", ""))), CheckConfig.empty())).isEmpty();
    }

    @Test
    void anUncaughtErrorIsReportedAtWarn() {
        assertThat(check.evaluate(withConsole(List.of(
                new ConsoleMessageRef("pageerror", "TypEr" + "ror: x ist nicht definiert", "app.js:12"))),
                CheckConfig.empty()))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.severity()).isEqualTo(Severity.WARN);
                    assertThat(finding.messageKey()).isEqualTo("finding.CONSOLE_ERRORS.error");
                });
    }

    @Test
    void ignorePatternsFromTheSiteConfigSuppressKnownBenignNoise() {
        CheckConfig config = CheckConfig.of(Map.of("ignorePatterns",
                List.of("consent", "gtm.js", "Third-party cookie")));

        assertThat(check.evaluate(withConsole(List.of(
                new ConsoleMessageRef("error", "Failed to load gtm.js", ""),
                new ConsoleMessageRef("error", "Third-party cookie will be blocked", ""))), config))
                .isEmpty();
    }

    @Test
    void theSubjectKeyIgnoresLineNumbersAndHashesSoItSurvivesADeploy() {
        List<CheckFinding> before = check.evaluate(withConsole(List.of(
                new ConsoleMessageRef("error", "Cannot read property 'x' of null at main.a1b2c3.js:412:9", ""))),
                CheckConfig.empty());
        List<CheckFinding> after = check.evaluate(withConsole(List.of(
                new ConsoleMessageRef("error", "Cannot read property 'x' of null at main.9f8e7d.js:517:3", ""))),
                CheckConfig.empty());

        assertThat(before.getFirst().subjectKey()).isEqualTo(after.getFirst().subjectKey());
    }

    @Test
    void repeatsOfTheSameErrorOnOnePageCollapseToOneFinding() {
        assertThat(check.evaluate(withConsole(List.of(
                new ConsoleMessageRef("error", "Boom", ""),
                new ConsoleMessageRef("error", "Boom", ""),
                new ConsoleMessageRef("error", "Boom", ""))), CheckConfig.empty())).hasSize(1);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=ImageBrokenCheckTest,MediaPlayableCheckTest,ConsoleErrorsCheckTest`
Expected: compilation failure — the three classes do not exist.

- [ ] **Step 3: Write `ImageBrokenCheck`**

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.checks.PageCheck;
import dev.hendrikhoemberg.webtesthelper.model.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code <img>}, {@code srcset} and CSS {@code background-image} render — measured by
 * {@code naturalWidth > 0}, not by a status code (spec 7.1). A 200 that serves an HTML error
 * page under a .png name has a naturalWidth of zero, and that is the case this catches.
 *
 * <p>Only {@link ImageOrigin#IMG} is measurable. srcset candidates the browser did not pick
 * and CSS backgrounds were never decoded, so they fall back to the run's URL verdicts, and
 * report nothing at all when there is no verdict — guessing would be a false positive.
 */
@Component
public class ImageBrokenCheck implements PageCheck {

    @Override
    public CheckType type() {
        return CheckType.IMAGE_BROKEN;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> emittedMessageKeys() {
        return Set.of("finding.IMAGE_BROKEN.not_rendered", "finding.IMAGE_BROKEN.unreachable",
                "finding.IMAGE_BROKEN.unverifiable");
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        List<CheckFinding> findings = new ArrayList<>();
        Set<String> alreadyReported = new LinkedHashSet<>();

        for (ImageRef image : snapshot.images()) {
            String raw = image.resolvedUrl();
            if (raw == null || raw.isBlank() || raw.startsWith("data:")) {
                continue;
            }
            Optional<String> key = UrlNormalizer.key(raw);
            if (key.isEmpty() || !alreadyReported.add(key.get())) {
                continue;
            }
            String subject = key.get();

            if (image.measurable()) {
                if (!image.rendered()) {
                    findings.add(finding(snapshot, subject, "finding.IMAGE_BROKEN.not_rendered",
                            List.of(subject), Severity.ERROR, null));
                }
                continue;   // a measured image needs no verdict
            }

            Optional<UrlVerdict> verdict = config.facts().verdict(subject);
            if (verdict.isEmpty()) {
                alreadyReported.remove(subject);   // not verified this run: say nothing, keep the slot free
                continue;
            }
            UrlVerdict result = verdict.get();
            switch (result.outcome()) {
                case DEAD -> findings.add(finding(snapshot, subject, "finding.IMAGE_BROKEN.unreachable",
                        List.of(String.valueOf(result.httpStatus())), Severity.ERROR, result));
                case UNVERIFIABLE -> findings.add(finding(snapshot, subject,
                        "finding.IMAGE_BROKEN.unverifiable",
                        List.of(String.valueOf(result.httpStatus())), Severity.INFO, result));
                case OK -> { }
            }
        }
        return findings;
    }

    private CheckFinding finding(PageSnapshot snapshot, String subject, String messageKey,
                                 List<String> args, Severity severity, UrlVerdict verdict) {
        return new CheckFinding(type(), severity, subject, snapshot.finalUrl(), messageKey, args,
                Evidence.builder()
                        .put(Evidence.DETAIL, subject)
                        .put(Evidence.HTTP_STATUS, verdict == null ? null : verdict.httpStatus())
                        .put(Evidence.CONTENT_TYPE, verdict == null ? null : verdict.contentType())
                        .put(Evidence.FINAL_URL, snapshot.finalUrl())
                        .put(Evidence.SCREENSHOT, snapshot.screenshotPath())
                        .build());
    }
}
```

- [ ] **Step 4: Write `MediaPlayableCheck`**

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.checks.PageCheck;
import dev.hendrikhoemberg.webtesthelper.model.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Sources resolve and metadata loads (spec 7.1). {@code readyState >= 1 && duration > 0} is
 * the assertion; a 200 on an .mp4 that Chromium cannot decode satisfies neither.
 *
 * <p>When a source is verifiably dead, the finding is keyed on the <em>source</em> rather
 * than the element, so the same missing video embedded on ten pages is one subject.
 */
@Component
public class MediaPlayableCheck implements PageCheck {

    @Override
    public CheckType type() {
        return CheckType.MEDIA_PLAYABLE;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> emittedMessageKeys() {
        return Set.of("finding.MEDIA_PLAYABLE.not_playable", "finding.MEDIA_PLAYABLE.source_unreachable",
                "finding.MEDIA_PLAYABLE.no_source");
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        List<CheckFinding> findings = new ArrayList<>();
        List<MediaRef> media = snapshot.media();

        for (int index = 0; index < media.size(); index++) {
            MediaRef element = media.get(index);
            if (element.metadataLoaded()) {
                continue;
            }
            if (element.sources().isEmpty()) {
                findings.add(finding(snapshot, snapshot.finalUrl() + "#media-" + index,
                        "finding.MEDIA_PLAYABLE.no_source",
                        List.of(element.kind().name()), element));
                continue;
            }

            Optional<CheckFinding> deadSource = element.sources().stream()
                    .map(source -> UrlNormalizer.key(source).orElse(source))
                    .flatMap(key -> config.facts().verdict(key).stream()
                            .filter(verdict -> verdict.outcome() != UrlOutcome.OK)
                            .map(verdict -> finding(snapshot, key,
                                    "finding.MEDIA_PLAYABLE.source_unreachable",
                                    List.of(key, String.valueOf(verdict.httpStatus())), element)))
                    .findFirst();

            findings.add(deadSource.orElseGet(() -> finding(snapshot,
                    UrlNormalizer.key(element.sources().getFirst()).orElse(element.sources().getFirst()),
                    "finding.MEDIA_PLAYABLE.not_playable",
                    List.of(element.kind().name(), element.sources().getFirst()), element)));
        }
        return findings;
    }

    private CheckFinding finding(PageSnapshot snapshot, String subject, String messageKey,
                                 List<String> args, MediaRef element) {
        return new CheckFinding(type(), defaultSeverity(), subject, snapshot.finalUrl(), messageKey, args,
                Evidence.builder()
                        .put(Evidence.DETAIL, element.error())
                        .put(Evidence.FINAL_URL, snapshot.finalUrl())
                        .put(Evidence.SCREENSHOT, snapshot.screenshotPath())
                        .build());
    }
}
```

- [ ] **Step 5: Write `ConsoleErrorsCheck`**

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.checks.PageCheck;
import dev.hendrikhoemberg.webtesthelper.model.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Uncaught JavaScript errors. <strong>Ships disabled</strong> (spec 7.1): real sites throw
 * constantly from third-party scripts, tracking pixels and consent tools, and enabling this
 * by default would make the very first report mostly noise.
 *
 * <p>The subject key is the message with digits, hex blobs and quoted strings removed. A
 * stack trace carrying a build hash and a line number would otherwise fingerprint
 * differently after every deploy, and the run-to-run diff — the entire product — would be
 * worthless for this check.
 */
@Component
public class ConsoleErrorsCheck implements PageCheck {

    private static final int SUBJECT_MAX_LENGTH = 200;

    @Override
    public CheckType type() {
        return CheckType.CONSOLE_ERRORS;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARN;
    }

    @Override
    public Set<String> emittedMessageKeys() {
        return Set.of("finding.CONSOLE_ERRORS.error");
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        List<String> ignorePatterns = config.strings("ignorePatterns").stream()
                .map(pattern -> pattern.toLowerCase(Locale.ROOT)).toList();

        List<CheckFinding> findings = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ConsoleMessageRef message : snapshot.consoleMessages()) {
            if (!message.isError()) {
                continue;
            }
            String text = message.text() == null ? "" : message.text();
            String lower = text.toLowerCase(Locale.ROOT);
            if (ignorePatterns.stream().anyMatch(lower::contains)) {
                continue;
            }
            String subject = normalizeForFingerprint(text);
            if (subject.isBlank() || !seen.add(subject)) {
                continue;
            }
            findings.add(new CheckFinding(type(), defaultSeverity(), subject, snapshot.finalUrl(),
                    "finding.CONSOLE_ERRORS.error", List.of(truncate(text, 300)),
                    Evidence.builder()
                            .put(Evidence.CONSOLE, truncate(text, 2000))
                            .put(Evidence.DETAIL, message.location())
                            .put(Evidence.FINAL_URL, snapshot.finalUrl())
                            .build()));
        }
        return findings;
    }

    /** Strips the parts that change without the error changing: digits, hex blobs, quoted values. */
    static String normalizeForFingerprint(String text) {
        String stripped = text
                .replaceAll("\\b[0-9a-f]{6,}\\b", "#")   // build hashes and ids
                .replaceAll("\\d+", "#")                 // line and column numbers
                .replaceAll("['\"][^'\"]*['\"]", "'…'")  // quoted values
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        return truncate(stripped, SUBJECT_MAX_LENGTH);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
```

- [ ] **Step 6: Add the German finding copy**

Append to `src/main/resources/messages_de.properties`:

```properties
finding.IMAGE_BROKEN.not_rendered=Das Bild {0} wird nicht angezeigt – die Datei fehlt oder ist beschädigt.
finding.IMAGE_BROKEN.unreachable=Die Bilddatei antwortet mit Status {0} und kann nicht geladen werden.
finding.IMAGE_BROKEN.unverifiable=Die Bilddatei konnte nicht geprüft werden (Status {0}); der fremde Server hat die Anfrage abgewiesen.
finding.MEDIA_PLAYABLE.not_playable={0}: die Datei {1} lässt sich nicht abspielen.
finding.MEDIA_PLAYABLE.source_unreachable=Die Mediendatei {0} antwortet mit Status {1}.
finding.MEDIA_PLAYABLE.no_source={0}-Element ohne Quelldatei – es kann nichts abgespielt werden.
finding.CONSOLE_ERRORS.error=JavaScript-Fehler beim Laden der Seite: {0}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=ImageBrokenCheckTest,MediaPlayableCheckTest,ConsoleErrorsCheckTest,CheckDocumentationTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/checks src/main/resources/messages_de.properties src/test/java/dev/hendrikhoemberg/webtesthelper/checks
git commit -m "feat(checks): add IMAGE_BROKEN, MEDIA_PLAYABLE and CONSOLE_ERRORS

Images are judged by naturalWidth and media by readyState/duration rather
than by status code. CONSOLE_ERRORS ships disabled and fingerprints on a
digit- and hash-stripped message so the diff survives a deploy."
```

---

### Task 11: Page checks, group C — `DEAD_LINK`, `FILE_DOWNLOAD`, `IFRAME_EMBED`

The three checks that carry most of the manual checklist, and the three where the obvious
implementation is the useless one (§7.1).

`DEAD_LINK` and `FILE_DOWNLOAD` do not overlap: `DEAD_LINK` owns every link whose verdict is
not `OK`, and `FILE_DOWNLOAD` only speaks when the verdict *is* `OK` but the bytes are wrong.
A dead PDF therefore produces exactly one finding.

`IFRAME_EMBED` special-cases Google Maps. The real-world failure is billing or an API key: a
grey map with a *"For development purposes only"* watermark and an `ApiNotActivatedMapError`
in the console. "The iframe loaded" passes that, so the check asserts the map canvas painted
and scans the console for the provider's error codes.

**Files:**
- Create: `src/main/java/.../checks/page/DeadLinkCheck.java`
- Create: `src/main/java/.../checks/page/FileDownloadCheck.java`
- Create: `src/main/java/.../checks/page/IframeEmbedCheck.java`
- Modify: `src/main/resources/messages_de.properties`
- Test: `src/test/java/.../checks/page/DeadLinkCheckTest.java`
- Test: `src/test/java/.../checks/page/FileDownloadCheckTest.java`
- Test: `src/test/java/.../checks/page/IframeEmbedCheckTest.java`

**Interfaces:**
- Consumes: `checks.PageCheck`, `model.LinkRef`, `model.IframeRef`, `model.UrlVerdict`.
- Produces: three `@Component` `PageCheck` beans.

- [ ] **Step 1: Write the failing tests**

`DeadLinkCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLinkCheckTest {

    private final DeadLinkCheck check = new DeadLinkCheck();

    private static PageSnapshot withLinks(List<LinkRef> links) {
        return PageSnapshot.builderFor("https://example.com/seite")
                .finalUrl("https://example.com/seite").links(links).build();
    }

    private static LinkRef link(String url, String text) {
        return new LinkRef(url, url, text, "", "");
    }

    private static CheckConfig verdicts(Map<String, UrlVerdict> verdicts) {
        return CheckConfig.empty().withFacts(RunFacts.of(null, verdicts));
    }

    @Test
    void aLinkWithAnOkVerdictIsFine() {
        assertThat(check.evaluate(withLinks(List.of(link("https://ziel.de/a", "Partner"))),
                verdicts(Map.of("https://ziel.de/a",
                        UrlVerdict.ok("https://ziel.de/a", 200, "https://ziel.de/a", "text/html", 10L, null, 0)))))
                .isEmpty();
    }

    @Test
    void aDeadLinkIsReportedAgainstItsNormalisedTarget() {
        List<CheckFinding> findings = check.evaluate(
                withLinks(List.of(link("https://ziel.de/weg?utm_source=footer#top", "Angebot"))),
                verdicts(Map.of("https://ziel.de/weg",
                        UrlVerdict.dead("https://ziel.de/weg", 404, "Not Found"))));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.checkType()).isEqualTo(CheckType.DEAD_LINK);
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.messageKey()).isEqualTo("finding.DEAD_LINK.dead");
            // Normalisation is what makes the same dead link fingerprint identically
            // wherever it is found (spec 6.2).
            assertThat(finding.subjectKey()).isEqualTo("https://ziel.de/weg");
            assertThat(finding.messageArgs()).containsExactly("https://ziel.de/weg", "404");
        });
    }

    @Test
    void aBlockedCheckerIsUnverifiableAtInfoRatherThanDeadAtError() {
        // Spec 8: a 403 or 429 from LinkedIn means they blocked us, not that the link is broken.
        List<CheckFinding> findings = check.evaluate(
                withLinks(List.of(link("https://www.linkedin.com/company/x", "LinkedIn"))),
                verdicts(Map.of("https://www.linkedin.com/company/x",
                        UrlVerdict.unverifiable("https://www.linkedin.com/company/x", 999, "Blocked"))));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(Severity.INFO);
            assertThat(finding.messageKey()).isEqualTo("finding.DEAD_LINK.unverifiable");
        });
    }

    @Test
    void linksWithoutAVerdictAreNotJudged() {
        assertThat(check.evaluate(withLinks(List.of(link("https://ziel.de/a", "x"))), CheckConfig.empty()))
                .isEmpty();
    }

    @Test
    void nonWebSchemesAndSelfLinksAreSkipped() {
        assertThat(check.evaluate(withLinks(List.of(
                new LinkRef("mailto:info@example.com", null, "E-Mail", "", ""),
                new LinkRef("#oben", null, "Nach oben", "", ""),
                link("https://example.com/seite", "Diese Seite"))),
                verdicts(Map.of("https://example.com/seite",
                        UrlVerdict.dead("https://example.com/seite", 500, null))))).isEmpty();
    }

    @Test
    void theSameDeadTargetLinkedTwiceOnOnePageIsOneFinding() {
        Map<String, UrlVerdict> verdicts = Map.of("https://ziel.de/weg",
                UrlVerdict.dead("https://ziel.de/weg", 404, null));

        assertThat(check.evaluate(withLinks(List.of(
                link("https://ziel.de/weg", "Oben"),
                link("https://ziel.de/weg?utm_medium=footer", "Unten"))), verdicts(verdicts)))
                .hasSize(1);
    }

    @Test
    void theAnchorTextTravelsAsEvidenceSoTheLinkCanBeFoundOnThePage() {
        List<CheckFinding> findings = check.evaluate(
                withLinks(List.of(link("https://ziel.de/weg", "Zum Angebot"))),
                verdicts(Map.of("https://ziel.de/weg", UrlVerdict.dead("https://ziel.de/weg", 404, null))));

        assertThat(findings.getFirst().evidence().get(Evidence.DETAIL)).hasValue("Zum Angebot");
    }
}
```

`FileDownloadCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileDownloadCheckTest {

    private final FileDownloadCheck check = new FileDownloadCheck();

    private static PageSnapshot withLink(String url) {
        return PageSnapshot.builderFor("https://example.com/downloads")
                .finalUrl("https://example.com/downloads")
                .links(List.of(new LinkRef(url, url, "Handbuch", "", ""))).build();
    }

    private static CheckConfig verdict(String url, UrlVerdict verdict) {
        return CheckConfig.empty().withFacts(RunFacts.of(null, Map.of(url, verdict)));
    }

    @Test
    void aRealPdfPasses() {
        String url = "https://example.com/handbuch.pdf";
        assertThat(check.evaluate(withLink(url),
                verdict(url, UrlVerdict.ok(url, 200, url, "application/pdf", 40_000L, "%PDF-1.4", 0))))
                .isEmpty();
    }

    @Test
    void anHtmlLoginWallWearingAPdfExtensionIsReported() {
        // The failure spec 7.1 names: 200 text/html is a login wall, not a PDF.
        String url = "https://example.com/preisliste.pdf";
        List<CheckFinding> findings = check.evaluate(withLink(url),
                verdict(url, UrlVerdict.ok(url, 200, url, "text/html; charset=utf-8", 2_000L, "<!doc", 0)));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.messageKey()).isEqualTo("finding.FILE_DOWNLOAD.wrong_content_type");
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.messageArgs()).containsExactly("text/html", "application/pdf");
        });
    }

    @Test
    void aPdfContentTypeWithoutPdfMagicBytesIsStillReported() {
        String url = "https://example.com/leer.pdf";
        assertThat(check.evaluate(withLink(url),
                verdict(url, UrlVerdict.ok(url, 200, url, "application/pdf", 40_000L, "<html", 0))))
                .singleElement().satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.FILE_DOWNLOAD.not_a_pdf"));
    }

    @Test
    void aTrivialFileSizeIsReported() {
        String url = "https://example.com/handbuch.pdf";
        assertThat(check.evaluate(withLink(url),
                verdict(url, UrlVerdict.ok(url, 200, url, "application/pdf", 12L, "%PDF-1.4", 0))))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.FILE_DOWNLOAD.too_small");
                    assertThat(finding.messageArgs()).containsExactly("12", "1024");
                });
    }

    @Test
    void aDeadDownloadIsLeftToDeadLinkSoItIsNotReportedTwice() {
        String url = "https://example.com/handbuch.pdf";
        assertThat(check.evaluate(withLink(url), verdict(url, UrlVerdict.dead(url, 404, null)))).isEmpty();
    }

    @Test
    void linksThatAreNotDownloadsAreIgnored() {
        String url = "https://example.com/leistungen";
        assertThat(check.evaluate(withLink(url),
                verdict(url, UrlVerdict.ok(url, 200, url, "text/html", 5_000L, "<!doc", 0)))).isEmpty();
    }

    @Test
    void theExtensionListAndMinimumSizeAreConfigurable() {
        String url = "https://example.com/daten.csv";
        CheckConfig config = new CheckConfig(
                Map.of("extensions", List.of("csv"), "minBytes", 5000),
                RunFacts.of(null, Map.of(url,
                        UrlVerdict.ok(url, 200, url, "text/csv", 1_000L, "Name;", 0))));

        assertThat(check.evaluate(withLink(url), config)).singleElement().satisfies(finding ->
                assertThat(finding.messageKey()).isEqualTo("finding.FILE_DOWNLOAD.too_small"));
    }
}
```

`IframeEmbedCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IframeEmbedCheckTest {

    private final IframeEmbedCheck check = new IframeEmbedCheck();

    private static PageSnapshot with(List<IframeRef> iframes, List<ConsoleMessageRef> console) {
        return PageSnapshot.builderFor("https://example.com/karte")
                .finalUrl("https://example.com/karte").iframes(iframes).consoleMessages(console).build();
    }

    private static IframeRef iframe(String url, boolean attached, boolean bodyNonEmpty,
                                    int textLength, int canvasArea, String xfo, String csp) {
        return new IframeRef(url, url, "Karte", attached, bodyNonEmpty, textLength, canvasArea,
                600, 300, xfo, csp);
    }

    @Test
    void anIframeWithContentIsFine() {
        assertThat(check.evaluate(with(List.of(
                iframe("https://player.example.com/v/1", true, true, 120, 0, null, null)), List.of()),
                CheckConfig.empty())).isEmpty();
    }

    @Test
    void anIframeBlockedByXFrameOptionsIsReported() {
        assertThat(check.evaluate(with(List.of(
                iframe("https://bank.example.com/", true, false, 0, 0, "DENY", null)), List.of()),
                CheckConfig.empty())).singleElement().satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.IFRAME_EMBED.blocked");
                    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
                });
    }

    @Test
    void anIframeBlockedByFrameAncestorsIsReported() {
        assertThat(check.evaluate(with(List.of(
                iframe("https://x.example.com/", true, false, 0, 0, null, "frame-ancestors 'self'")),
                List.of()), CheckConfig.empty())).singleElement().satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.IFRAME_EMBED.blocked"));
    }

    @Test
    void anIframeThatNeverAttachedIsReported() {
        assertThat(check.evaluate(with(List.of(
                iframe("https://weg.example.com/", false, false, 0, 0, null, null)), List.of()),
                CheckConfig.empty())).singleElement().satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.IFRAME_EMBED.not_loaded"));
    }

    @Test
    void anAttachedButEmptyIframeIsReported() {
        assertThat(check.evaluate(with(List.of(
                iframe("https://leer.example.com/", true, false, 0, 0, null, null)), List.of()),
                CheckConfig.empty())).singleElement().satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.IFRAME_EMBED.empty"));
    }

    @Test
    void aMapsEmbedThatLoadedButPaintedNoCanvasIsReported() {
        // "The iframe loaded" is exactly the assertion that passes a billing failure (spec 7.1).
        assertThat(check.evaluate(with(List.of(
                iframe("https://www.google.com/maps/embed?pb=x", true, true, 80, 0, null, null)),
                List.of()), CheckConfig.empty())).singleElement().satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.IFRAME_EMBED.maps_not_painted");
                    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
                });
    }

    @Test
    void aMapsEmbedWithAProviderErrorInTheConsoleNamesTheErrorCode() {
        List<CheckFinding> findings = check.evaluate(with(
                List.of(iframe("https://www.google.com/maps/embed?pb=x", true, true, 80, 0, null, null)),
                List.of(new ConsoleMessageRef("error",
                        "Google Maps JavaScript API error: ApiNotActivatedMapError", ""))),
                CheckConfig.empty());

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.messageKey()).isEqualTo("finding.IFRAME_EMBED.maps_error");
            assertThat(finding.messageArgs()).containsExactly("ApiNotActivatedMapError");
        });
    }

    @Test
    void aMapsEmbedThatPaintedACanvasIsFine() {
        assertThat(check.evaluate(with(List.of(
                iframe("https://maps.google.de/maps?q=x", true, true, 80, 120_000, null, null)),
                List.of()), CheckConfig.empty())).isEmpty();
    }

    @Test
    void additionalMapProvidersCanBeConfiguredPerSite() {
        CheckConfig config = CheckConfig.of(Map.of("mapsUrlPatterns", List.of("*/karte-embed")));

        assertThat(check.evaluate(with(List.of(
                iframe("https://example.com/karte-embed", true, true, 40, 0, null, null)), List.of()),
                config)).singleElement().satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.IFRAME_EMBED.maps_not_painted"));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=DeadLinkCheckTest,FileDownloadCheckTest,IframeEmbedCheckTest`
Expected: compilation failure — the three classes do not exist.

- [ ] **Step 3: Write `DeadLinkCheck`**

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.checks.PageCheck;
import dev.hendrikhoemberg.webtesthelper.model.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Internal and external link targets resolve (spec 7.1).
 *
 * <p>The check does not fetch anything: verification happened in the asset stage on virtual
 * threads (spec 5.3, plan deviation D2) and its verdicts arrive through
 * {@link RunFacts#verdict}. A link with no verdict was not verified in this run and is
 * silently left alone — guessing is how a checker loses trust.
 *
 * <p>Non-OK verdicts belong to this check exclusively; {@code FILE_DOWNLOAD} only speaks
 * about links that resolved, so a dead PDF is one finding, not two.
 */
@Component
public class DeadLinkCheck implements PageCheck {

    @Override
    public CheckType type() {
        return CheckType.DEAD_LINK;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> emittedMessageKeys() {
        return Set.of("finding.DEAD_LINK.dead", "finding.DEAD_LINK.unverifiable");
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        String self = UrlNormalizer.key(snapshot.finalUrl()).orElse(null);
        List<CheckFinding> findings = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();

        for (LinkRef link : snapshot.links()) {
            Optional<String> key = link.resolvedUrl() == null
                    ? Optional.empty()
                    : UrlNormalizer.key(link.resolvedUrl());
            if (key.isEmpty() || key.get().equals(self) || !reported.add(key.get())) {
                continue;
            }
            Optional<UrlVerdict> verdict = config.facts().verdict(key.get());
            if (verdict.isEmpty()) {
                reported.remove(key.get());
                continue;
            }
            UrlVerdict result = verdict.get();
            String status = result.httpStatus() == null ? "-" : String.valueOf(result.httpStatus());
            Evidence evidence = Evidence.builder()
                    .put(Evidence.DETAIL, link.anchorText())
                    .put(Evidence.HTTP_STATUS, result.httpStatus())
                    .put(Evidence.RESPONSE, result.errorMessage())
                    .put(Evidence.FINAL_URL, snapshot.finalUrl())
                    .put(Evidence.SCREENSHOT, snapshot.screenshotPath())
                    .build();

            switch (result.outcome()) {
                case DEAD -> findings.add(new CheckFinding(type(), Severity.ERROR, key.get(),
                        snapshot.finalUrl(), "finding.DEAD_LINK.dead",
                        List.of(key.get(), status), evidence));
                case UNVERIFIABLE -> findings.add(new CheckFinding(type(), Severity.INFO, key.get(),
                        snapshot.finalUrl(), "finding.DEAD_LINK.unverifiable",
                        List.of(key.get(), status), evidence));
                case OK -> { }
            }
        }
        return findings;
    }
}
```

- [ ] **Step 4: Write `FileDownloadCheck`**

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.checks.PageCheck;
import dev.hendrikhoemberg.webtesthelper.model.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Status 200 <em>and</em> matching content-type <em>and</em> non-trivial size <em>and</em>,
 * for PDFs, the {@code %PDF} magic bytes (spec 7.1). Any one of those alone passes a login
 * wall or an error page served under a .pdf name.
 *
 * <p>Only speaks about links whose verdict is OK. Everything else is {@code DEAD_LINK}'s.
 */
@Component
public class FileDownloadCheck implements PageCheck {

    private static final long DEFAULT_MIN_BYTES = 1024;

    private static final Map<String, String> EXPECTED_CONTENT_TYPES = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("zip", "application/zip"),
            Map.entry("csv", "text/csv"));

    private static final List<String> DEFAULT_EXTENSIONS = List.copyOf(EXPECTED_CONTENT_TYPES.keySet());

    @Override
    public CheckType type() {
        return CheckType.FILE_DOWNLOAD;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> emittedMessageKeys() {
        return Set.of("finding.FILE_DOWNLOAD.wrong_content_type", "finding.FILE_DOWNLOAD.not_a_pdf",
                "finding.FILE_DOWNLOAD.too_small");
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        List<String> extensions = config.strings("extensions").isEmpty()
                ? DEFAULT_EXTENSIONS
                : config.strings("extensions");
        long minBytes = (long) config.number("minBytes", DEFAULT_MIN_BYTES);

        List<CheckFinding> findings = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();

        for (LinkRef link : snapshot.links()) {
            Optional<NormalizedUrl> target = link.resolvedUrl() == null
                    ? Optional.empty()
                    : UrlNormalizer.normalize(link.resolvedUrl());
            if (target.isEmpty()) {
                continue;
            }
            String extension = extensionOf(target.get().path());
            if (extension == null || !extensions.contains(extension)) {
                continue;
            }
            String key = target.get().value();
            if (!reported.add(key)) {
                continue;
            }
            Optional<UrlVerdict> verdict = config.facts().verdict(key);
            if (verdict.isEmpty() || !verdict.get().ok()) {
                reported.remove(key);
                continue;   // DEAD_LINK owns anything that did not resolve
            }
            UrlVerdict result = verdict.get();
            Evidence evidence = Evidence.builder()
                    .put(Evidence.DETAIL, link.anchorText())
                    .put(Evidence.HTTP_STATUS, result.httpStatus())
                    .put(Evidence.CONTENT_TYPE, result.contentType())
                    .put(Evidence.CONTENT_LENGTH, result.contentLength())
                    .put(Evidence.RESPONSE, result.magicBytes())
                    .put(Evidence.FINAL_URL, snapshot.finalUrl())
                    .build();

            String expected = EXPECTED_CONTENT_TYPES.get(extension);
            String actual = result.baseContentType();
            if (expected != null && !expected.equals(actual)) {
                findings.add(new CheckFinding(type(), defaultSeverity(), key, snapshot.finalUrl(),
                        "finding.FILE_DOWNLOAD.wrong_content_type",
                        List.of(actual.isEmpty() ? "-" : actual, expected), evidence));
                continue;
            }
            if ("pdf".equals(extension) && !result.looksLikePdf()) {
                findings.add(new CheckFinding(type(), defaultSeverity(), key, snapshot.finalUrl(),
                        "finding.FILE_DOWNLOAD.not_a_pdf", List.of(key), evidence));
                continue;
            }
            if (result.contentLength() != null && result.contentLength() < minBytes) {
                findings.add(new CheckFinding(type(), defaultSeverity(), key, snapshot.finalUrl(),
                        "finding.FILE_DOWNLOAD.too_small",
                        List.of(String.valueOf(result.contentLength()), String.valueOf(minBytes)),
                        evidence));
            }
        }
        return findings;
    }

    private static String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot < slash || dot == path.length() - 1) {
            return null;
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 5: Write `IframeEmbedCheck`**

```java
package dev.hendrikhoemberg.webtesthelper.checks.page;

import dev.hendrikhoemberg.webtesthelper.checks.PageCheck;
import dev.hendrikhoemberg.webtesthelper.model.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Not blocked by X-Frame-Options or CSP, and renders non-empty content (spec 7.1).
 *
 * <p>Google Maps gets a special case because the real-world failure — billing lapsed or the
 * API key rejected — produces a perfectly loaded iframe showing a grey rectangle. The check
 * asserts the map canvas painted and reads the provider's error codes out of the console.
 */
@Component
public class IframeEmbedCheck implements PageCheck {

    private static final List<String> DEFAULT_MAPS_PATTERNS = List.of(
            "*google.com/maps*", "*google.de/maps*", "*maps.google.*",
            "*maps.googleapis.com*", "*google.com/maps/embed*");

    /** The codes Google's Maps JavaScript API prints when the embed is misconfigured. */
    private static final List<String> MAPS_ERROR_CODES = List.of(
            "ApiNotActivatedMapError", "BillingNotEnabledMapError", "InvalidKeyMapError",
            "MissingKeyMapError", "ExpiredKeyMapError", "RefererNotAllowedMapError",
            "OverQuotaMapError", "ApiTargetBlockedMapError", "For development purposes only");

    @Override
    public CheckType type() {
        return CheckType.IFRAME_EMBED;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> emittedMessageKeys() {
        return Set.of("finding.IFRAME_EMBED.blocked", "finding.IFRAME_EMBED.not_loaded",
                "finding.IFRAME_EMBED.empty", "finding.IFRAME_EMBED.maps_not_painted",
                "finding.IFRAME_EMBED.maps_error");
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        List<Pattern> mapsPatterns = compile(config.strings("mapsUrlPatterns").isEmpty()
                ? DEFAULT_MAPS_PATTERNS
                : config.strings("mapsUrlPatterns"));

        List<CheckFinding> findings = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();

        for (IframeRef iframe : snapshot.iframes()) {
            String raw = iframe.resolvedUrl();
            if (raw == null || raw.isBlank() || raw.startsWith("about:")) {
                continue;
            }
            String subject = UrlNormalizer.key(raw).orElse(raw);
            if (!reported.add(subject)) {
                continue;
            }
            Evidence evidence = Evidence.builder()
                    .put(Evidence.DETAIL, iframe.title())
                    .put(Evidence.RESPONSE, iframe.frameOptionsHeader())
                    .put(Evidence.FINAL_URL, snapshot.finalUrl())
                    .put(Evidence.SCREENSHOT, snapshot.screenshotPath())
                    .build();

            if (iframe.blockedByHeaders()) {
                findings.add(finding(snapshot, subject, "finding.IFRAME_EMBED.blocked",
                        List.of(subject), evidence));
                continue;
            }
            if (!iframe.frameAttached()) {
                findings.add(finding(snapshot, subject, "finding.IFRAME_EMBED.not_loaded",
                        List.of(subject), evidence));
                continue;
            }

            if (matches(mapsPatterns, raw)) {
                Optional<String> providerError = mapsErrorIn(snapshot.consoleMessages());
                if (providerError.isPresent()) {
                    findings.add(finding(snapshot, subject, "finding.IFRAME_EMBED.maps_error",
                            List.of(providerError.get()), Evidence.builder()
                                    .put(Evidence.CONSOLE, providerError.get())
                                    .put(Evidence.FINAL_URL, snapshot.finalUrl())
                                    .put(Evidence.SCREENSHOT, snapshot.screenshotPath())
                                    .build()));
                } else if (iframe.canvasArea() <= 0) {
                    findings.add(finding(snapshot, subject, "finding.IFRAME_EMBED.maps_not_painted",
                            List.of(subject), evidence));
                }
                continue;
            }

            if (!iframe.bodyNonEmpty() && iframe.contentTextLength() == 0 && iframe.canvasArea() == 0) {
                findings.add(finding(snapshot, subject, "finding.IFRAME_EMBED.empty",
                        List.of(subject), evidence));
            }
        }
        return findings;
    }

    private CheckFinding finding(PageSnapshot snapshot, String subject, String messageKey,
                                 List<String> args, Evidence evidence) {
        return new CheckFinding(type(), defaultSeverity(), subject, snapshot.finalUrl(),
                messageKey, args, evidence);
    }

    private static Optional<String> mapsErrorIn(List<ConsoleMessageRef> messages) {
        for (ConsoleMessageRef message : messages) {
            String text = message.text() == null ? "" : message.text();
            for (String code : MAPS_ERROR_CODES) {
                if (text.contains(code)) {
                    return Optional.of(code);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean matches(List<Pattern> patterns, String url) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(url).matches());
    }

    private static List<Pattern> compile(List<String> globs) {
        List<Pattern> patterns = new ArrayList<>();
        for (String glob : globs) {
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                if (c == '*') {
                    regex.append(".*");
                } else {
                    if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                }
            }
            patterns.add(Pattern.compile(regex.append('$').toString(), Pattern.CASE_INSENSITIVE));
        }
        return patterns;
    }
}
```

- [ ] **Step 6: Add the German finding copy**

Append to `src/main/resources/messages_de.properties`:

```properties
finding.DEAD_LINK.dead=Der Verweis auf {0} führt ins Leere (Status {1}).
finding.DEAD_LINK.unverifiable=Der Verweis auf {0} konnte nicht geprüft werden (Status {1}) – der fremde Server weist automatische Anfragen ab.
finding.FILE_DOWNLOAD.wrong_content_type=Der Download liefert {0} statt {1} – vermutlich eine Fehler- oder Anmeldeseite.
finding.FILE_DOWNLOAD.not_a_pdf=Die Datei {0} gibt sich als PDF aus, enthält aber kein PDF.
finding.FILE_DOWNLOAD.too_small=Die Datei ist mit {0} Byte zu klein, erwartet werden mindestens {1} Byte.
finding.IFRAME_EMBED.blocked=Der eingebettete Inhalt {0} wird vom Anbieter blockiert und kann nicht dargestellt werden.
finding.IFRAME_EMBED.not_loaded=Der eingebettete Inhalt {0} wurde nicht geladen.
finding.IFRAME_EMBED.empty=Der eingebettete Inhalt {0} bleibt leer.
finding.IFRAME_EMBED.maps_not_painted=Die Karte {0} lädt, zeigt aber kein Kartenbild – meist ein Problem mit API-Schlüssel oder Abrechnung.
finding.IFRAME_EMBED.maps_error=Google Maps meldet {0}. Prüfen Sie API-Schlüssel und Abrechnung in der Google Cloud Console.
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=DeadLinkCheckTest,FileDownloadCheckTest,IframeEmbedCheckTest,CheckDocumentationTest,CheckPurityTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/checks src/main/resources/messages_de.properties src/test/java/dev/hendrikhoemberg/webtesthelper/checks
git commit -m "feat(checks): add DEAD_LINK, FILE_DOWNLOAD and IFRAME_EMBED

FILE_DOWNLOAD requires content-type, size and %PDF magic bytes together.
IFRAME_EMBED special-cases Maps: it asserts the canvas painted and reads
the provider's error codes from the console."
```

---

### Task 12: Asset verification on virtual threads, and the external URL cache

The other half of the two execution models (§5.3). A browser page load is expensive and
slow; an HTTP HEAD is neither. Verifying 4,000 external links through a browser takes hours;
through virtual threads it takes minutes.

Four things have to be right here:

1. **Virtual threads, bounded by a per-host semaphore** rather than by pool size (§5.4).
   Politeness is enforced where it belongs and thread-count tuning disappears.
2. **`UNVERIFIABLE` is a distinct outcome from `DEAD`** (§8). A 403, 429 or 999 means the
   target blocked our checker.
3. **End-of-run re-verification** (§8). Failures are collected, then re-verified with
   backoff; only survivors become findings. This is what removes transient 5xx noise
   without waiting a month for a second run to confirm.
4. **The shared external URL cache** (§8.1), which collapses twenty sites' links to the same
   partner URL into one request per TTL — the largest single cost driver in a sweep *and*
   the largest source of `UNVERIFIABLE` findings. Failures get a much shorter TTL than
   successes so a recovered link is not reported dead for a day.

**Files:**
- Create: `src/main/resources/db/migration/V6__external_url_check.sql`
- Create: `src/main/java/.../crawler/persistence/ExternalUrlCheckEntity.java`
- Create: `src/main/java/.../crawler/persistence/ExternalUrlCheckRepository.java`
- Create: `src/main/java/.../crawler/ExternalUrlCache.java`
- Create: `src/main/java/.../crawler/HostRateLimiter.java`
- Create: `src/main/java/.../crawler/VerificationOptions.java`
- Create: `src/main/java/.../crawler/AssetVerifier.java`
- Modify: `src/test/java/.../support/FixtureSite.java` (add `/gesperrt`, `/wackelig`, request counting)
- Test: `src/test/java/.../crawler/HostRateLimiterTest.java`
- Test: `src/test/java/.../crawler/AssetVerifierIT.java`

**Interfaces:**
- Consumes: `model.UrlVerdict`, `model.UrlOutcome`, `model.NormalizedUrl`, `model.UrlNormalizer`.
- Produces:
  - `VerificationOptions(Duration requestTimeout, int perHostConcurrency, Duration perHostDelay,
    Duration successTtl, Duration failureTtl, String userAgent, int reverifyAttempts,
    Duration reverifyBackoff)` with `VerificationOptions.defaults(String userAgent)`.
  - `AssetVerifier.verify(long siteId, Collection<String> normalizedUrls, VerificationOptions)
    -> Map<String, UrlVerdict>`
  - `ExternalUrlCache.lookup(Collection<String>) -> Map<String,UrlVerdict>`,
    `ExternalUrlCache.store(long siteId, UrlVerdict, Duration ttl)`
  - `HostRateLimiter.run(String host, Callable<T>) -> T`
  - `FixtureSite.requestCount(String path) -> int`, `FixtureSite.resetCounts()`

- [ ] **Step 1: Extend the fixture site**

In `FixtureSite`, add a request counter and two routes. Add the field and methods:

```java
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>
            requestCounts = new java.util.concurrent.ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicInteger flakyHits =
            new java.util.concurrent.atomic.AtomicInteger();

    public int requestCount(String path) {
        var counter = requestCounts.get(path);
        return counter == null ? 0 : counter.get();
    }

    public void resetCounts() {
        requestCounts.clear();
        flakyHits.set(0);
    }
```

At the top of `dispatch`, before the switch:

```java
        requestCounts.computeIfAbsent(path, key -> new java.util.concurrent.atomic.AtomicInteger())
                .incrementAndGet();
```

And two new cases in the switch:

```java
                // Blocks automated checkers, like a Cloudflare-fronted host would (spec 8).
                case "/gesperrt" -> respond(exchange, 403, "text/html; charset=utf-8",
                        html("Gesperrt", "<h1>403 – Zugriff verweigert</h1>"));

                // Fails once, then succeeds: the transient 5xx that end-of-run
                // re-verification exists to swallow (spec 8).
                case "/wackelig" -> {
                    if (flakyHits.getAndIncrement() == 0) {
                        respond(exchange, 503, "text/html; charset=utf-8",
                                html("Wartung", "<h1>503 – kurz nicht verfügbar</h1>"));
                    } else {
                        respond(exchange, 200, "text/html; charset=utf-8",
                                html("Wieder da", "<h1>Alles in Ordnung</h1>"));
                    }
                }
```

- [ ] **Step 2: Write the failing tests**

`HostRateLimiterTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class HostRateLimiterTest {

    @Test
    void neverRunsMoreThanTheAllowedNumberOfTasksAgainstOneHost() throws Exception {
        HostRateLimiter limiter = new HostRateLimiter(2, Duration.ZERO);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(30);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 30; i++) {
                pool.submit(() -> limiter.run("example.com", () -> {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    Thread.sleep(20);
                    inFlight.decrementAndGet();
                    done.countDown();
                    return null;
                }));
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(peak.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void differentHostsDoNotBlockEachOther() throws Exception {
        HostRateLimiter limiter = new HostRateLimiter(1, Duration.ZERO);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String host : new String[]{"a.example.com", "b.example.com"}) {
                pool.submit(() -> limiter.run(host, () -> {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    Thread.sleep(100);
                    inFlight.decrementAndGet();
                    done.countDown();
                    return null;
                }));
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(peak.get()).isEqualTo(2);
    }

    @Test
    void enforcesAMinimumDelayBetweenRequestsToTheSameHost() throws Exception {
        HostRateLimiter limiter = new HostRateLimiter(1, Duration.ofMillis(80));

        long start = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            limiter.run("example.com", () -> null);
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isGreaterThanOrEqualTo(160);
    }
}
```

`AssetVerifierIT.java`:

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.UrlOutcome;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerdict;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssetVerifierIT extends AbstractPostgresTest {

    @RegisterExtension
    static final FixtureSite SITE = new FixtureSite();

    @Autowired
    AssetVerifier verifier;

    @Autowired
    JdbcTemplate jdbc;

    private static final long SITE_ID = 1L;

    private VerificationOptions options() {
        return new VerificationOptions(Duration.ofSeconds(10), 4, Duration.ZERO,
                Duration.ofHours(24), Duration.ofHours(1), "WebTestHelper-Test", 2,
                Duration.ofMillis(50));
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM external_url_check");
        SITE.resetCounts();
    }

    @Test
    void classifiesOkDeadAndBlockedTargets() {
        Map<String, UrlVerdict> verdicts = verifier.verify(SITE_ID, List.of(
                SITE.url("/leistungen"), SITE.url("/echte-404"), SITE.url("/gesperrt")), options());

        assertThat(verdicts.get(SITE.url("/leistungen")).outcome()).isEqualTo(UrlOutcome.OK);
        assertThat(verdicts.get(SITE.url("/echte-404")).outcome()).isEqualTo(UrlOutcome.DEAD);
        // A 403 is "they blocked us", not "your link is broken" (spec 8).
        assertThat(verdicts.get(SITE.url("/gesperrt")).outcome()).isEqualTo(UrlOutcome.UNVERIFIABLE);
    }

    @Test
    void capturesContentTypeLengthAndMagicBytesForDownloads() {
        UrlVerdict pdf = verifier.verify(SITE_ID, List.of(SITE.url("/dokumente/handbuch.pdf")), options())
                .get(SITE.url("/dokumente/handbuch.pdf"));

        assertThat(pdf.outcome()).isEqualTo(UrlOutcome.OK);
        assertThat(pdf.baseContentType()).isEqualTo("application/pdf");
        assertThat(pdf.contentLength()).isGreaterThan(1024L);
        assertThat(pdf.looksLikePdf()).isTrue();
    }

    @Test
    void seesThroughAnHtmlLoginWallWearingAPdfExtension() {
        UrlVerdict wall = verifier.verify(SITE_ID, List.of(SITE.url("/dokumente/preisliste.pdf")), options())
                .get(SITE.url("/dokumente/preisliste.pdf"));

        assertThat(wall.outcome()).isEqualTo(UrlOutcome.OK);
        assertThat(wall.baseContentType()).isEqualTo("text/html");
        assertThat(wall.looksLikePdf()).isFalse();
    }

    @Test
    void followsRedirectsAndCountsTheHops() {
        UrlVerdict verdict = verifier.verify(SITE_ID, List.of(SITE.url("/weiterleitung/1")), options())
                .get(SITE.url("/weiterleitung/1"));

        assertThat(verdict.outcome()).isEqualTo(UrlOutcome.OK);
        assertThat(verdict.redirectHops()).isEqualTo(3);
        assertThat(verdict.finalUrl()).endsWith("/weiterleitung/ziel");
    }

    @Test
    void aRedirectLoopIsDeadRatherThanAnInfiniteFetch() {
        UrlVerdict verdict = verifier.verify(SITE_ID, List.of(SITE.url("/schleife/a")), options())
                .get(SITE.url("/schleife/a"));

        assertThat(verdict.outcome()).isEqualTo(UrlOutcome.DEAD);
        assertThat(verdict.errorMessage()).containsIgnoringCase("weiterleitung");
    }

    @Test
    void aTransientFailureIsSwallowedByEndOfRunReVerification() {
        // /wackelig answers 503 once, then 200. Without re-verification this would be a
        // false positive every time a server hiccups (spec 8).
        UrlVerdict verdict = verifier.verify(SITE_ID, List.of(SITE.url("/wackelig")), options())
                .get(SITE.url("/wackelig"));

        assertThat(verdict.outcome()).isEqualTo(UrlOutcome.OK);
        assertThat(SITE.requestCount("/wackelig")).isGreaterThanOrEqualTo(2);
    }

    @Test
    void aSecondRunReusesTheCacheInsteadOfRefetching() {
        verifier.verify(SITE_ID, List.of(SITE.url("/leistungen")), options());
        int afterFirst = SITE.requestCount("/leistungen");

        Map<String, UrlVerdict> second = verifier.verify(2L, List.of(SITE.url("/leistungen")), options());

        assertThat(second.get(SITE.url("/leistungen")).outcome()).isEqualTo(UrlOutcome.OK);
        assertThat(SITE.requestCount("/leistungen")).isEqualTo(afterFirst);
        assertThat(jdbc.queryForObject(
                "SELECT dependent_site_ids::text FROM external_url_check WHERE normalized_url = ?",
                String.class, SITE.url("/leistungen"))).contains("1").contains("2");
    }

    @Test
    void aFailureIsCachedForMuchLessTimeThanASuccess() {
        verifier.verify(SITE_ID, List.of(SITE.url("/leistungen"), SITE.url("/echte-404")), options());

        Long okTtl = jdbc.queryForObject("""
                SELECT EXTRACT(EPOCH FROM (expires_at - checked_at))::bigint
                  FROM external_url_check WHERE outcome = 'OK'
                """, Long.class);
        Long deadTtl = jdbc.queryForObject("""
                SELECT EXTRACT(EPOCH FROM (expires_at - checked_at))::bigint
                  FROM external_url_check WHERE outcome = 'DEAD'
                """, Long.class);

        assertThat(okTtl).isEqualTo(86_400L);
        assertThat(deadTtl).isEqualTo(3_600L);
    }

    @Test
    void unparseableUrlsAreSkippedRatherThanFailingTheWholeBatch() {
        Map<String, UrlVerdict> verdicts = verifier.verify(SITE_ID,
                List.of("javascript:void(0)", SITE.url("/leistungen")), options());

        assertThat(verdicts).containsOnlyKeys(SITE.url("/leistungen"));
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=HostRateLimiterTest,AssetVerifierIT`
Expected: compilation failure — `HostRateLimiter` and `AssetVerifier` do not exist.

- [ ] **Step 4: Write `V6__external_url_check.sql`**

```sql
-- Shared across all sites (spec 8.1): twenty sites linking to the same partner URL cost
-- one request per TTL, not twenty per sweep.
CREATE TABLE external_url_check (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    normalized_url TEXT NOT NULL,
    outcome TEXT NOT NULL,
    http_status INTEGER,
    final_url TEXT,
    content_type TEXT,
    content_length BIGINT,
    magic_bytes TEXT,
    redirect_hops INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    checked_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    dependent_site_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_euc_url ON external_url_check (normalized_url);
CREATE INDEX ix_euc_expires ON external_url_check (expires_at);
```

- [ ] **Step 5: Write the cache**

```java
package dev.hendrikhoemberg.webtesthelper.crawler.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "external_url_check")
@Getter
@Setter
@NoArgsConstructor
public class ExternalUrlCheckEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "normalized_url", nullable = false)
    private String normalizedUrl;

    @Column(nullable = false)
    private String outcome;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "final_url")
    private String finalUrl;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "content_length")
    private Long contentLength;

    @Column(name = "magic_bytes")
    private String magicBytes;

    @Column(name = "redirect_hops", nullable = false)
    private int redirectHops;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Which sites link here, so a transition to dead can fan out in Phase 2 (spec 8.1). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dependent_site_ids", nullable = false)
    private List<Long> dependentSiteIds = new ArrayList<>();

    @Version
    private long version;
}
```

```java
package dev.hendrikhoemberg.webtesthelper.crawler.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExternalUrlCheckRepository extends JpaRepository<ExternalUrlCheckEntity, Long> {

    List<ExternalUrlCheckEntity> findByNormalizedUrlIn(Collection<String> urls);

    Optional<ExternalUrlCheckEntity> findByNormalizedUrl(String url);
}
```

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.crawler.persistence.ExternalUrlCheckEntity;
import dev.hendrikhoemberg.webtesthelper.crawler.persistence.ExternalUrlCheckRepository;
import dev.hendrikhoemberg.webtesthelper.model.UrlOutcome;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerdict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** The shared external URL cache of spec 8.1. */
@Service
public class ExternalUrlCache {

    private final ExternalUrlCheckRepository repository;

    public ExternalUrlCache(ExternalUrlCheckRepository repository) {
        this.repository = repository;
    }

    /** Only entries still inside their TTL are returned. */
    @Transactional(readOnly = true)
    public Map<String, UrlVerdict> lookup(Collection<String> normalizedUrls) {
        if (normalizedUrls.isEmpty()) {
            return Map.of();
        }
        Instant now = Instant.now();
        Map<String, UrlVerdict> hits = new LinkedHashMap<>();
        for (ExternalUrlCheckEntity entity : repository.findByNormalizedUrlIn(normalizedUrls)) {
            if (entity.getExpiresAt().isAfter(now)) {
                hits.put(entity.getNormalizedUrl(), toVerdict(entity));
            }
        }
        return hits;
    }

    @Transactional
    public void store(long siteId, UrlVerdict verdict, Duration ttl) {
        ExternalUrlCheckEntity entity = repository.findByNormalizedUrl(verdict.normalizedUrl())
                .orElseGet(ExternalUrlCheckEntity::new);
        entity.setNormalizedUrl(verdict.normalizedUrl());
        entity.setOutcome(verdict.outcome().name());
        entity.setHttpStatus(verdict.httpStatus());
        entity.setFinalUrl(verdict.finalUrl());
        entity.setContentType(verdict.contentType());
        entity.setContentLength(verdict.contentLength());
        entity.setMagicBytes(verdict.magicBytes());
        entity.setRedirectHops(verdict.redirectHops());
        entity.setErrorMessage(verdict.errorMessage());
        entity.setCheckedAt(verdict.checkedAt());
        entity.setExpiresAt(verdict.checkedAt().plus(ttl));
        if (!entity.getDependentSiteIds().contains(siteId)) {
            List<Long> dependents = new ArrayList<>(entity.getDependentSiteIds());
            dependents.add(siteId);
            entity.setDependentSiteIds(dependents);
        }
        repository.save(entity);
    }

    private static UrlVerdict toVerdict(ExternalUrlCheckEntity entity) {
        return new UrlVerdict(entity.getNormalizedUrl(), UrlOutcome.valueOf(entity.getOutcome()),
                entity.getHttpStatus(), entity.getFinalUrl(), entity.getContentType(),
                entity.getContentLength(), entity.getMagicBytes(), entity.getRedirectHops(),
                entity.getErrorMessage(), entity.getCheckedAt());
    }
}
```

- [ ] **Step 6: Write `HostRateLimiter` and `VerificationOptions`**

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Politeness where it belongs (spec 5.4, 8): concurrency is capped per host rather than
 * globally, so one slow partner site cannot starve the rest and no host is hammered.
 *
 * <p>Because the cap is per host, the calling executor can be unbounded — which is what
 * makes {@code Executors.newVirtualThreadPerTaskExecutor()} the right tool and makes
 * thread-count tuning disappear.
 */
public class HostRateLimiter {

    private final Map<String, Semaphore> permits = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> nextAllowedNanos = new ConcurrentHashMap<>();
    private final int concurrencyPerHost;
    private final long delayNanos;

    public HostRateLimiter(int concurrencyPerHost, Duration delayBetweenRequests) {
        this.concurrencyPerHost = Math.max(1, concurrencyPerHost);
        this.delayNanos = Math.max(0, delayBetweenRequests.toNanos());
    }

    public <T> T run(String host, Callable<T> work) {
        Semaphore semaphore = permits.computeIfAbsent(host, key -> new Semaphore(concurrencyPerHost));
        try {
            semaphore.acquire();
            try {
                waitForSlot(host);
                return work.call();
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Prüfung für " + host + " unterbrochen", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Prüfung für " + host + " fehlgeschlagen", e);
        }
    }

    private void waitForSlot(String host) throws InterruptedException {
        if (delayNanos == 0) {
            return;
        }
        AtomicLong gate = nextAllowedNanos.computeIfAbsent(host, key -> new AtomicLong());
        while (true) {
            long now = System.nanoTime();
            long allowed = gate.get();
            if (allowed <= now) {
                if (gate.compareAndSet(allowed, now + delayNanos)) {
                    return;
                }
            } else if (gate.compareAndSet(allowed, allowed + delayNanos)) {
                Thread.sleep(Duration.ofNanos(allowed - now));
                return;
            }
        }
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import java.time.Duration;

public record VerificationOptions(Duration requestTimeout, int perHostConcurrency,
                                  Duration perHostDelay, Duration successTtl, Duration failureTtl,
                                  String userAgent, int reverifyAttempts, Duration reverifyBackoff) {

    public static VerificationOptions defaults(String userAgent) {
        return new VerificationOptions(Duration.ofSeconds(15), 4, Duration.ofMillis(250),
                Duration.ofHours(24), Duration.ofHours(1), userAgent, 2, Duration.ofSeconds(2));
    }
}
```

- [ ] **Step 7: Write `AssetVerifier`**

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.model.UrlOutcome;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Verifies links, images and files off the browser path (spec 5.3).
 *
 * <p>Pure blocking I/O on virtual threads, bounded by {@link HostRateLimiter}. Uses
 * {@code java.net.http.HttpClient} so nothing is added to the dependency list.
 *
 * <p>Redirects are followed by hand rather than by the client so the hop count and the loop
 * are both observable — {@code HttpClient.Redirect.NORMAL} would hide both.
 */
@Component
public class AssetVerifier {

    private static final Logger log = LoggerFactory.getLogger(AssetVerifier.class);

    private static final int MAX_REDIRECTS = 5;
    private static final int MAGIC_BYTES = 8;

    /** Extensions worth a ranged GET so FILE_DOWNLOAD gets magic bytes to look at. */
    private static final Set<String> BODY_SNIFF_EXTENSIONS =
            Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "csv");

    /** "They blocked our checker", not "your link is broken" (spec 8). */
    private static final Set<Integer> BLOCKED_STATUSES = Set.of(401, 403, 405, 406, 429, 451, 999);

    private final ExternalUrlCache cache;

    public AssetVerifier(ExternalUrlCache cache) {
        this.cache = cache;
    }

    public Map<String, UrlVerdict> verify(long siteId, Collection<String> rawUrls,
                                          VerificationOptions options) {
        Map<String, NormalizedUrl> targets = new LinkedHashMap<>();
        for (String raw : rawUrls) {
            UrlNormalizer.normalize(raw).ifPresent(url -> targets.put(url.value(), url));
        }
        if (targets.isEmpty()) {
            return Map.of();
        }

        Map<String, UrlVerdict> results = new LinkedHashMap<>(cache.lookup(targets.keySet()));
        List<NormalizedUrl> pending = targets.entrySet().stream()
                .filter(entry -> !results.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();

        if (pending.isEmpty()) {
            return results;
        }

        HostRateLimiter limiter = new HostRateLimiter(options.perHostConcurrency(), options.perHostDelay());
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(options.requestTimeout())
                .build();

        Map<String, UrlVerdict> fresh = fetchAll(client, limiter, pending, options);

        // End-of-run re-verification (spec 8): failures are not findings until they survive
        // a second look with backoff. This is what removes transient 5xx noise.
        List<NormalizedUrl> suspects = pending.stream()
                .filter(url -> fresh.get(url.value()).outcome() == UrlOutcome.DEAD)
                .toList();
        for (int attempt = 1; attempt <= options.reverifyAttempts() && !suspects.isEmpty(); attempt++) {
            sleep(options.reverifyBackoff().multipliedBy(attempt));
            Map<String, UrlVerdict> retried = fetchAll(client, limiter, suspects, options);
            retried.forEach((key, verdict) -> {
                if (verdict.outcome() != UrlOutcome.DEAD) {
                    fresh.put(key, verdict);
                }
            });
            List<NormalizedUrl> stillFailing = suspects.stream()
                    .filter(url -> fresh.get(url.value()).outcome() == UrlOutcome.DEAD)
                    .toList();
            suspects = stillFailing;
        }

        fresh.forEach((key, verdict) -> {
            Duration ttl = verdict.outcome() == UrlOutcome.OK ? options.successTtl() : options.failureTtl();
            cache.store(siteId, verdict, ttl);
        });
        results.putAll(fresh);
        return results;
    }

    private Map<String, UrlVerdict> fetchAll(HttpClient client, HostRateLimiter limiter,
                                             List<NormalizedUrl> urls, VerificationOptions options) {
        Map<String, UrlVerdict> verdicts = new ConcurrentHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(urls.size());
            for (NormalizedUrl url : urls) {
                futures.add(executor.submit(() -> verdicts.put(url.value(),
                        limiter.run(url.host(), () -> fetch(client, url, options)))));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    log.warn("Prüfung einer Adresse ist unerwartet fehlgeschlagen", e.getCause());
                }
            }
        }
        urls.forEach(url -> verdicts.computeIfAbsent(url.value(),
                key -> UrlVerdict.dead(key, null, "Prüfung wurde abgebrochen")));
        return new LinkedHashMap<>(verdicts);
    }

    private UrlVerdict fetch(HttpClient client, NormalizedUrl target, VerificationOptions options) {
        String key = target.value();
        String current = key;
        Set<String> visited = new LinkedHashSet<>();
        int hops = 0;

        try {
            while (true) {
                if (!visited.add(current)) {
                    return UrlVerdict.dead(key, null, "Weiterleitung dreht sich im Kreis bei " + current);
                }
                if (hops > MAX_REDIRECTS) {
                    return UrlVerdict.dead(key, null, "Zu viele Weiterleitungen (" + hops + ")");
                }

                boolean sniffBody = needsBodySniff(current);
                HttpResponse<byte[]> response = send(client, current, options, sniffBody ? "GET" : "HEAD");

                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    Optional<String> location = response.headers().firstValue("location");
                    if (location.isEmpty()) {
                        return UrlVerdict.dead(key, status, "Weiterleitung ohne Zieladresse");
                    }
                    Optional<NormalizedUrl> next = UrlNormalizer.resolve(current, location.get());
                    if (next.isEmpty()) {
                        return UrlVerdict.dead(key, status, "Unbrauchbares Weiterleitungsziel: " + location.get());
                    }
                    current = next.get().value();
                    hops++;
                    continue;
                }

                if (status == 405 || status == 501) {
                    // The host rejects HEAD. Retry once with a ranged GET before judging it.
                    response = send(client, current, options, "GET");
                    status = response.statusCode();
                }

                if (status >= 200 && status < 300) {
                    return UrlVerdict.ok(key, status, current,
                            response.headers().firstValue("content-type").orElse(null),
                            contentLength(response),
                            magicBytes(response.body()),
                            hops);
                }
                if (BLOCKED_STATUSES.contains(status)) {
                    return UrlVerdict.unverifiable(key, status, "Der Server weist die Prüfung ab");
                }
                return UrlVerdict.dead(key, status, "HTTP " + status);
            }
        } catch (IOException e) {
            return UrlVerdict.dead(key, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UrlVerdict.dead(key, null, "Prüfung unterbrochen");
        }
    }

    private HttpResponse<byte[]> send(HttpClient client, String url, VerificationOptions options,
                                      String method) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(options.requestTimeout())
                .header("User-Agent", options.userAgent())
                .header("Accept", "*/*");
        if ("HEAD".equals(method)) {
            request.method("HEAD", HttpRequest.BodyPublishers.noBody());
        } else {
            request.GET().header("Range", "bytes=0-2047");
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static boolean needsBodySniff(String url) {
        int dot = url.lastIndexOf('.');
        int slash = url.lastIndexOf('/');
        if (dot < 0 || dot < slash) {
            return false;
        }
        String extension = url.substring(dot + 1).toLowerCase(Locale.ROOT);
        int cut = extension.indexOf('?');
        if (cut >= 0) {
            extension = extension.substring(0, cut);
        }
        return BODY_SNIFF_EXTENSIONS.contains(extension);
    }

    private static Long contentLength(HttpResponse<byte[]> response) {
        // A ranged GET answers with Content-Range; its total is the real size.
        Optional<String> range = response.headers().firstValue("content-range");
        if (range.isPresent()) {
            int slash = range.get().lastIndexOf('/');
            if (slash >= 0) {
                try {
                    return Long.parseLong(range.get().substring(slash + 1).trim());
                } catch (NumberFormatException ignored) {
                    // fall through to content-length
                }
            }
        }
        return response.headers().firstValueAsLong("content-length").stream().boxed().findFirst().orElse(null);
    }

    private static String magicBytes(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        return new String(body, 0, Math.min(MAGIC_BYTES, body.length), StandardCharsets.ISO_8859_1);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=HostRateLimiterTest,AssetVerifierIT`
Expected: PASS, all twelve cases.

If `capturesContentTypeLengthAndMagicBytesForDownloads` returns a null `contentLength`,
the fixture's `respond` is not sending a `Content-Length` — `sendResponseHeaders(status,
body.length)` sets it, so check that the ranged GET path is being taken for `.pdf`.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/db/migration/V6__external_url_check.sql src/main/java/dev/hendrikhoemberg/webtesthelper/crawler src/test/java/dev/hendrikhoemberg/webtesthelper/{crawler,support}
git commit -m "feat(crawler): verify assets on virtual threads with a shared URL cache

Per-host semaphore instead of a fixed pool. UNVERIFIABLE is distinct from
DEAD, failures are re-verified with backoff before becoming findings, and
failures are cached for an hour against a day for successes (spec 8, 8.1)."
```

---

### Task 13: Site checks — `TLS_CERT`, `HREFLANG`, `SITEMAP_CONSISTENCY`

Site checks need cross-page knowledge and run once, after the crawl (§5.2). Two notes on
their inputs:

- **`TLS_CERT` needs a handshake**, which is I/O and would make the check impossible to unit
  test. The handshake sits behind a `CertificateInspector` interface; the check itself stays
  a pure function of what the inspector reports, and its tests hand-build that.
- **`SITEMAP_CONSISTENCY` needs the sitemap**, which the check cannot fetch. The run
  orchestrator (Task 17) reads it with `SitemapReader` and merges the resulting URL list into
  the check's `CheckConfig` settings under `sitemapUrls` — the same run-facts channel as
  deviation D3, reused rather than duplicated.

**Files:**
- Create: `src/main/java/.../checks/site/CertificateInfo.java`
- Create: `src/main/java/.../checks/site/CertificateInspector.java`
- Create: `src/main/java/.../checks/site/TlsHandshakeInspector.java`
- Create: `src/main/java/.../checks/site/TlsCertCheck.java`
- Create: `src/main/java/.../checks/site/HreflangCheck.java`
- Create: `src/main/java/.../checks/site/SitemapConsistencyCheck.java`
- Create: `src/main/java/.../crawler/SitemapReader.java`
- Modify: `src/main/resources/messages_de.properties`
- Modify: `src/test/java/.../checks/CheckDocumentationTest.java` (add the completeness case)
- Test: `src/test/java/.../checks/site/TlsCertCheckTest.java`
- Test: `src/test/java/.../checks/site/HreflangCheckTest.java`
- Test: `src/test/java/.../checks/site/SitemapConsistencyCheckTest.java`
- Test: `src/test/java/.../crawler/SitemapReaderTest.java`

**Interfaces:**
- Consumes: `checks.SiteCheck`, `model.RunSnapshots`, `model.SiteContext`, `model.CheckConfig`.
- Produces:
  - `CertificateInfo(boolean reachable, boolean trusted, boolean hostnameMatches,
    Instant notBefore, Instant notAfter, String subject, String issuer, String error)`
  - `CertificateInspector.inspect(String host, int port) -> CertificateInfo`
  - `SitemapReader.read(String sitemapXml, String baseUrl) -> List<String>` (normalised URLs)
  - three `@Component` `SiteCheck` beans

- [ ] **Step 1: Write the failing tests**

`SitemapReaderTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SitemapReaderTest {

    private final SitemapReader reader = new SitemapReader();

    @Test
    void readsAbsoluteAndRelativeLocations() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>https://example.com/leistungen</loc></url>
                  <url><loc>/kontakt</loc></url>
                </urlset>
                """;

        assertThat(reader.read(xml, "https://example.com/"))
                .containsExactly("https://example.com/leistungen", "https://example.com/kontakt");
    }

    @Test
    void normalisesEntriesTheSameWayTheCrawlerDoes() {
        String xml = """
                <urlset><url><loc>https://EXAMPLE.com:443/a/?utm_source=x#top</loc></url></urlset>
                """;

        assertThat(reader.read(xml, "https://example.com/")).containsExactly("https://example.com/a");
    }

    @Test
    void ignoresJunkEntriesInsteadOfFailing() {
        String xml = """
                <urlset>
                  <url><loc>mailto:info@example.com</loc></url>
                  <url><loc></loc></url>
                  <url><loc>/gut</loc></url>
                </urlset>
                """;

        assertThat(reader.read(xml, "https://example.com/")).containsExactly("https://example.com/gut");
    }

    @Test
    void malformedXmlYieldsAnEmptyListRatherThanAnException() {
        assertThat(reader.read("<urlset><url><loc>/a", "https://example.com/")).isEmpty();
        assertThat(reader.read(null, "https://example.com/")).isEmpty();
    }

    @Test
    void readsASitemapIndexAsAListOfChildSitemaps() {
        String xml = """
                <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <sitemap><loc>/sitemap-1.xml</loc></sitemap>
                  <sitemap><loc>/sitemap-2.xml</loc></sitemap>
                </sitemapindex>
                """;

        assertThat(reader.read(xml, "https://example.com/"))
                .containsExactly("https://example.com/sitemap-1.xml", "https://example.com/sitemap-2.xml");
    }
}
```

`TlsCertCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks.site;

import dev.hendrikhoemberg.webtesthelper.model.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TlsCertCheckTest {

    private static SiteContext site(String baseUrl) {
        return new SiteContext(1L, "Test", UrlNormalizer.normalize(baseUrl).orElseThrow(),
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, null, Map.of());
    }

    private static CertificateInfo valid(Duration remaining) {
        return new CertificateInfo(true, true, true, Instant.now().minus(Duration.ofDays(30)),
                Instant.now().plus(remaining), "CN=example.com", "CN=Test-CA", null);
    }

    private static TlsCertCheck checkReturning(CertificateInfo info) {
        return new TlsCertCheck((host, port) -> info);
    }

    @Test
    void aValidCertificateWithPlentyOfTimeIsFine() {
        assertThat(checkReturning(valid(Duration.ofDays(90)))
                .evaluate(new RunSnapshots(List.of()), site("https://example.com/"), CheckConfig.empty()))
                .isEmpty();
    }

    @Test
    void anHttpSiteIsNotChecked() {
        assertThat(checkReturning(valid(Duration.ofDays(1)))
                .evaluate(new RunSnapshots(List.of()), site("http://example.com/"), CheckConfig.empty()))
                .isEmpty();
    }

    @Test
    void aCertificateExpiringSoonIsAWarning() {
        assertThat(checkReturning(valid(Duration.ofDays(5)))
                .evaluate(new RunSnapshots(List.of()), site("https://example.com/"), CheckConfig.empty()))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.TLS_CERT.expiring");
                    assertThat(finding.severity()).isEqualTo(Severity.WARN);
                    assertThat(finding.messageArgs().getFirst()).isEqualTo("5");
                });
    }

    @Test
    void anExpiredCertificateIsAnError() {
        assertThat(checkReturning(valid(Duration.ofDays(-2)))
                .evaluate(new RunSnapshots(List.of()), site("https://example.com/"), CheckConfig.empty()))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.TLS_CERT.expired");
                    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
                });
    }

    @Test
    void anUntrustedOrMismatchedCertificateIsAnError() {
        CertificateInfo mismatched = new CertificateInfo(true, true, false,
                Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(200)),
                "CN=andere.de", "CN=Test-CA", null);

        assertThat(checkReturning(mismatched)
                .evaluate(new RunSnapshots(List.of()), site("https://example.com/"), CheckConfig.empty()))
                .singleElement().satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.TLS_CERT.invalid"));
    }

    @Test
    void anUnreachableHostIsReportedAsAHandshakeFailure() {
        CertificateInfo unreachable = new CertificateInfo(false, false, false, null, null, null, null,
                "Connection refused");

        assertThat(checkReturning(unreachable)
                .evaluate(new RunSnapshots(List.of()), site("https://example.com/"), CheckConfig.empty()))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.TLS_CERT.handshake_failed");
                    assertThat(finding.messageArgs()).containsExactly("Connection refused");
                });
    }

    @Test
    void theWarningWindowIsConfigurable() {
        assertThat(checkReturning(valid(Duration.ofDays(40)))
                .evaluate(new RunSnapshots(List.of()), site("https://example.com/"),
                        CheckConfig.of(Map.of("warnDaysBeforeExpiry", 60))))
                .singleElement().satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.TLS_CERT.expiring"));
    }
}
```

`HreflangCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks.site;

import dev.hendrikhoemberg.webtesthelper.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HreflangCheckTest {

    private final HreflangCheck check = new HreflangCheck();

    private static final SiteContext SITE = new SiteContext(1L, "Test",
            UrlNormalizer.normalize("https://example.com/").orElseThrow(), CrawlBudget.DEFAULT,
            List.of(), List.of(), List.of(), true, null, Map.of());

    private static PageSnapshot page(String url, List<HreflangRef> alternates) {
        return PageSnapshot.builderFor(url).finalUrl(url).httpStatus(200).hreflangs(alternates).build();
    }

    private static HreflangRef alternate(String lang, String url) {
        return new HreflangRef(lang, url, url);
    }

    @Test
    void mutuallyReferencingAlternatesAreFine() {
        RunSnapshots snapshots = new RunSnapshots(List.of(
                page("https://example.com/de/start", List.of(
                        alternate("de", "https://example.com/de/start"),
                        alternate("en", "https://example.com/en/start"))),
                page("https://example.com/en/start", List.of(
                        alternate("de", "https://example.com/de/start"),
                        alternate("en", "https://example.com/en/start")))));

        assertThat(check.evaluate(snapshots, SITE, CheckConfig.empty())).isEmpty();
    }

    @Test
    void aMissingBackReferenceIsReportedAgainstThePageThatFailsToPointBack() {
        // The fixture's failure: /de points at /en, /en does not point back.
        RunSnapshots snapshots = new RunSnapshots(List.of(
                page("https://example.com/de/start", List.of(
                        alternate("de", "https://example.com/de/start"),
                        alternate("en", "https://example.com/en/start"))),
                page("https://example.com/en/start", List.of(
                        alternate("en", "https://example.com/en/start")))));

        assertThat(check.evaluate(snapshots, SITE, CheckConfig.empty()))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.HREFLANG.not_reciprocal");
                    assertThat(finding.observedPageUrl()).isEqualTo("https://example.com/en/start");
                    assertThat(finding.subjectKey()).isEqualTo("https://example.com/de/start");
                });
    }

    @Test
    void anAlternateThatDoesNotResolveIsReported() {
        RunSnapshots snapshots = new RunSnapshots(List.of(
                page("https://example.com/de/start", List.of(
                        alternate("de", "https://example.com/de/start"),
                        alternate("fr", "https://example.com/fr/start")))));
        CheckConfig config = CheckConfig.empty().withFacts(RunFacts.of(null,
                Map.of("https://example.com/fr/start",
                        UrlVerdict.dead("https://example.com/fr/start", 404, null))));

        assertThat(check.evaluate(snapshots, SITE, config)).singleElement().satisfies(finding -> {
            assertThat(finding.messageKey()).isEqualTo("finding.HREFLANG.unreachable");
            assertThat(finding.messageArgs()).containsExactly("fr", "https://example.com/fr/start", "404");
        });
    }

    @Test
    void anAlternateOutsideTheCrawlWithNoVerdictIsNotJudged() {
        RunSnapshots snapshots = new RunSnapshots(List.of(
                page("https://example.com/de/start", List.of(
                        alternate("de", "https://example.com/de/start"),
                        alternate("en", "https://en.partner.de/start")))));

        assertThat(check.evaluate(snapshots, SITE, CheckConfig.empty())).isEmpty();
    }

    @Test
    void pagesWithoutAlternatesAreIgnored() {
        assertThat(check.evaluate(new RunSnapshots(List.of(
                page("https://example.com/impressum", List.of()))), SITE, CheckConfig.empty())).isEmpty();
    }
}
```

`SitemapConsistencyCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks.site;

import dev.hendrikhoemberg.webtesthelper.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SitemapConsistencyCheckTest {

    private final SitemapConsistencyCheck check = new SitemapConsistencyCheck();

    private static final SiteContext SITE = new SiteContext(1L, "Test",
            UrlNormalizer.normalize("https://example.com/").orElseThrow(), CrawlBudget.DEFAULT,
            List.of(), List.of(), List.of(), true, null, Map.of());

    private static PageSnapshot page(String url, int status) {
        return PageSnapshot.builderFor(url).finalUrl(url).httpStatus(status).build();
    }

    private static CheckConfig withSitemap(List<String> urls, Map<String, UrlVerdict> verdicts) {
        return new CheckConfig(Map.of("sitemapUrls", urls), RunFacts.of(null, verdicts));
    }

    @Test
    void anEmptySitemapProducesNothing() {
        assertThat(check.evaluate(new RunSnapshots(List.of(page("https://example.com/a", 200))),
                SITE, CheckConfig.empty())).isEmpty();
    }

    @Test
    void aSitemapEntryThatWasCrawledAndFailedIsReported() {
        RunSnapshots snapshots = new RunSnapshots(List.of(page("https://example.com/weg", 404)));

        assertThat(check.evaluate(snapshots, SITE,
                withSitemap(List.of("https://example.com/weg"), Map.of())))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.SITEMAP_CONSISTENCY.dead_entry");
                    assertThat(finding.subjectKey()).isEqualTo("https://example.com/weg");
                });
    }

    @Test
    void aSitemapEntryWithADeadVerdictIsReportedEvenIfItWasNeverCrawled() {
        assertThat(check.evaluate(new RunSnapshots(List.of()), SITE,
                withSitemap(List.of("https://example.com/weg"),
                        Map.of("https://example.com/weg",
                                UrlVerdict.dead("https://example.com/weg", 404, null)))))
                .singleElement().satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.SITEMAP_CONSISTENCY.dead_entry"));
    }

    @Test
    void aCrawledPageMissingFromTheSitemapIsInformationalOnly() {
        RunSnapshots snapshots = new RunSnapshots(List.of(
                page("https://example.com/a", 200), page("https://example.com/geheim", 200)));

        assertThat(check.evaluate(snapshots, SITE, withSitemap(List.of("https://example.com/a"), Map.of())))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.SITEMAP_CONSISTENCY.missing_entry");
                    assertThat(finding.severity()).isEqualTo(Severity.INFO);
                    assertThat(finding.subjectKey()).isEqualTo("https://example.com/geheim");
                });
    }

    @Test
    void theMissingEntryHalfCanBeSwitchedOffPerSite() {
        RunSnapshots snapshots = new RunSnapshots(List.of(page("https://example.com/geheim", 200)));
        CheckConfig config = new CheckConfig(
                Map.of("sitemapUrls", List.of("https://example.com/a"), "reportMissingEntries", false),
                RunFacts.NONE);

        assertThat(check.evaluate(snapshots, SITE, config)).isEmpty();
    }

    @Test
    void nonTwoHundredPagesAreNotExpectedInTheSitemap() {
        RunSnapshots snapshots = new RunSnapshots(List.of(page("https://example.com/weg", 404)));

        assertThat(check.evaluate(snapshots, SITE, withSitemap(List.of(), Map.of()))).isEmpty();
    }
}
```

Also add this case to `CheckDocumentationTest` — with Task 13 the catalog is complete:

```java
    @Test
    void everyCheckTypeExceptPageUnreachableHasAnImplementation() {
        // PAGE_UNREACHABLE is emitted by the run orchestrator from a failed capture: there is
        // no snapshot for a PageCheck to evaluate. See Task 9.
        for (CheckType type : CheckType.values()) {
            if (type == CheckType.PAGE_UNREACHABLE) {
                continue;
            }
            assertThat(registry.descriptor(type))
                    .describedAs("Keine Implementierung für %s", type).isPresent();
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=SitemapReaderTest,TlsCertCheckTest,HreflangCheckTest,SitemapConsistencyCheckTest`
Expected: compilation failure — none of these classes exist.

- [ ] **Step 3: Write `SitemapReader`**

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads {@code <loc>} entries out of a sitemap or a sitemap index, normalised the same way
 * the crawl frontier normalises everything else so the two sets can be compared.
 *
 * <p>Malformed XML yields an empty list. A broken sitemap is a finding for
 * {@code SITEMAP_CONSISTENCY} to make, not an exception that ends the run.
 */
@Component
public class SitemapReader {

    private static final Logger log = LoggerFactory.getLogger(SitemapReader.class);

    public List<String> read(String xml, String baseUrl) {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setNamespaceAware(false);   // sitemaps in the wild are inconsistent about it

            Document document = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));
            NodeList locations = document.getElementsByTagName("loc");

            Set<String> urls = new LinkedHashSet<>();
            for (int i = 0; i < locations.getLength(); i++) {
                String raw = locations.item(i).getTextContent();
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                UrlNormalizer.resolve(baseUrl, raw.trim())
                        .map(NormalizedUrl::value)
                        .ifPresent(urls::add);
            }
            return new ArrayList<>(urls);
        } catch (Exception e) {
            log.debug("Sitemap konnte nicht gelesen werden: {}", e.getMessage());
            return List.of();
        }
    }
}
```

- [ ] **Step 4: Write the certificate inspector**

```java
package dev.hendrikhoemberg.webtesthelper.checks.site;

import java.time.Instant;

public record CertificateInfo(boolean reachable, boolean trusted, boolean hostnameMatches,
                              Instant notBefore, Instant notAfter, String subject, String issuer,
                              String error) {

    public static CertificateInfo unreachable(String error) {
        return new CertificateInfo(false, false, false, null, null, null, null, error);
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.checks.site;

/** The one piece of I/O a site check needs, behind an interface so the check stays testable. */
@FunctionalInterface
public interface CertificateInspector {

    CertificateInfo inspect(String host, int port);
}
```

```java
package dev.hendrikhoemberg.webtesthelper.checks.site;

import org.springframework.stereotype.Component;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLParameters;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;

@Component
public class TlsHandshakeInspector implements CertificateInspector {

    private static final int HANDSHAKE_TIMEOUT_MILLIS = 10_000;

    @Override
    public CertificateInfo inspect(String host, int port) {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MILLIS);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);

            // Endpoint identification makes the JDK verify the hostname during the handshake.
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.startHandshake();

            X509Certificate leaf = (X509Certificate) socket.getSession().getPeerCertificates()[0];
            boolean valid = true;
            try {
                leaf.checkValidity();
            } catch (CertificateExpiredException | CertificateNotYetValidException e) {
                valid = false;   // reported by the check from notAfter, not swallowed here
            }
            return new CertificateInfo(true, true, true,
                    leaf.getNotBefore().toInstant(), leaf.getNotAfter().toInstant(),
                    leaf.getSubjectX500Principal().getName(),
                    leaf.getIssuerX500Principal().getName(),
                    valid ? null : "Zertifikat außerhalb seines Gültigkeitszeitraums");
        } catch (SSLPeerUnverifiedException e) {
            return new CertificateInfo(true, false, false, null, null, null, null, e.getMessage());
        } catch (javax.net.ssl.SSLHandshakeException e) {
            // Untrusted chain or hostname mismatch: reachable, but not usable.
            return new CertificateInfo(true, false, false, null, null, null, null, e.getMessage());
        } catch (Exception e) {
            return CertificateInfo.unreachable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
```

> `HttpsURLConnection` is imported by some IDEs' auto-complete here and is not needed —
> remove it if it appears.

- [ ] **Step 5: Write `TlsCertCheck`**

```java
package dev.hendrikhoemberg.webtesthelper.checks.site;

import dev.hendrikhoemberg.webtesthelper.checks.SiteCheck;
import dev.hendrikhoemberg.webtesthelper.model.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Valid, and not expiring within N days (spec 7.1). */
@Component
public class TlsCertCheck implements SiteCheck {

    private static final int DEFAULT_WARN_DAYS = 21;

    private final CertificateInspector inspector;

    public TlsCertCheck(CertificateInspector inspector) {
        this.inspector = inspector;
    }

    @Override
    public CheckType type() {
        return CheckType.TLS_CERT;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> emittedMessageKeys() {
        return Set.of("finding.TLS_CERT.expired", "finding.TLS_CERT.expiring",
                "finding.TLS_CERT.invalid", "finding.TLS_CERT.handshake_failed");
    }

    @Override
    public List<CheckFinding> evaluate(RunSnapshots snapshots, SiteContext site, CheckConfig config) {
        NormalizedUrl base = site.baseUrl();
        if (!base.isSecure()) {
            return List.of();
        }
        CertificateInfo info = inspector.inspect(base.host(), base.port());
        String subject = base.origin();
        String page = base.value();

        if (!info.reachable()) {
            return List.of(finding(Severity.ERROR, subject, page, "finding.TLS_CERT.handshake_failed",
                    List.of(String.valueOf(info.error())), info));
        }
        if (!info.trusted() || !info.hostnameMatches()) {
            return List.of(finding(Severity.ERROR, subject, page, "finding.TLS_CERT.invalid",
                    List.of(base.host(), String.valueOf(info.error())), info));
        }

        Instant now = Instant.now();
        if (info.notAfter() != null && info.notAfter().isBefore(now)) {
            return List.of(finding(Severity.ERROR, subject, page, "finding.TLS_CERT.expired",
                    List.of(info.notAfter().toString()), info));
        }
        int warnDays = config.integer("warnDaysBeforeExpiry", DEFAULT_WARN_DAYS);
        if (info.notAfter() != null && info.notAfter().isBefore(now.plus(Duration.ofDays(warnDays)))) {
            long remaining = Duration.between(now, info.notAfter()).toDays();
            return List.of(finding(Severity.WARN, subject, page, "finding.TLS_CERT.expiring",
                    List.of(String.valueOf(remaining), info.notAfter().toString()), info));
        }
        return List.of();
    }

    private CheckFinding finding(Severity severity, String subject, String page, String messageKey,
                                 List<String> args, CertificateInfo info) {
        return new CheckFinding(type(), severity, subject, page, messageKey, args,
                Evidence.builder()
                        .put(Evidence.DETAIL, info.subject())
                        .put(Evidence.RESPONSE, info.issuer())
                        .put(Evidence.FINAL_URL, page)
                        .build());
    }
}
```

- [ ] **Step 6: Write `HreflangCheck`**

```java
package dev.hendrikhoemberg.webtesthelper.checks.site;

import dev.hendrikhoemberg.webtesthelper.checks.SiteCheck;
import dev.hendrikhoemberg.webtesthelper.model.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Language alternates resolve and reciprocate across the crawled set (spec 7.1).
 *
 * <p>Reciprocity is reported against the page that <em>fails to point back</em>, not against
 * the page that noticed — that is where the fix belongs, and it keeps one missing tag from
 * producing a finding on every other language version.
 */
@Component
public class HreflangCheck implements SiteCheck {

    @Override
    public CheckType type() {
        return CheckType.HREFLANG;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARN;
    }

    @Override
    public Set<String> emittedMessageKeys() {
        return Set.of("finding.HREFLANG.unreachable", "finding.HREFLANG.not_reciprocal");
    }

    @Override
    public List<CheckFinding> evaluate(RunSnapshots snapshots, SiteContext site, CheckConfig config) {
        Map<String, PageSnapshot> crawled = new LinkedHashMap<>();
        for (PageSnapshot snapshot : snapshots.snapshots()) {
            UrlNormalizer.key(snapshot.finalUrl()).ifPresent(key -> crawled.putIfAbsent(key, snapshot));
        }

        List<CheckFinding> findings = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();

        for (PageSnapshot snapshot : snapshots.snapshots()) {
            String self = UrlNormalizer.key(snapshot.finalUrl()).orElse(null);
            if (self == null || snapshot.hreflangs().isEmpty()) {
                continue;
            }
            for (HreflangRef alternate : snapshot.hreflangs()) {
                Optional<String> target = alternate.resolvedUrl() == null
                        ? Optional.empty()
                        : UrlNormalizer.key(alternate.resolvedUrl());
                if (target.isEmpty() || target.get().equals(self)) {
                    continue;
                }
                String key = target.get();

                PageSnapshot alternatePage = crawled.get(key);
                if (alternatePage == null) {
                    config.facts().verdict(key)
                            .filter(verdict -> verdict.outcome() != UrlOutcome.OK)
                            .ifPresent(verdict -> add(findings, reported, new CheckFinding(type(),
                                    Severity.WARN, key, snapshot.finalUrl(),
                                    "finding.HREFLANG.unreachable",
                                    List.of(alternate.lang(), key, String.valueOf(verdict.httpStatus())),
                                    Evidence.builder().put(Evidence.HTTP_STATUS, verdict.httpStatus())
                                            .put(Evidence.FINAL_URL, snapshot.finalUrl()).build())));
                    continue;
                }
                if (alternatePage.httpStatus() >= 400) {
                    add(findings, reported, new CheckFinding(type(), Severity.WARN, key,
                            snapshot.finalUrl(), "finding.HREFLANG.unreachable",
                            List.of(alternate.lang(), key, String.valueOf(alternatePage.httpStatus())),
                            Evidence.builder().put(Evidence.HTTP_STATUS, alternatePage.httpStatus())
                                    .put(Evidence.FINAL_URL, snapshot.finalUrl()).build()));
                    continue;
                }

                boolean pointsBack = alternatePage.hreflangs().stream()
                        .map(HreflangRef::resolvedUrl)
                        .filter(Objects::nonNull)
                        .map(url -> UrlNormalizer.key(url).orElse(url))
                        .anyMatch(self::equals);
                if (!pointsBack) {
                    add(findings, reported, new CheckFinding(type(), Severity.WARN, self,
                            alternatePage.finalUrl(), "finding.HREFLANG.not_reciprocal",
                            List.of(key, self), Evidence.builder()
                                    .put(Evidence.DETAIL, alternate.lang())
                                    .put(Evidence.FINAL_URL, alternatePage.finalUrl())
                                    .build()));
                }
            }
        }
        return findings;
    }

    private static void add(List<CheckFinding> findings, Set<String> reported, CheckFinding finding) {
        if (reported.add(finding.messageKey() + "|" + finding.subjectKey() + "|"
                + finding.observedPageUrl())) {
            findings.add(finding);
        }
    }
}
```

- [ ] **Step 7: Write `SitemapConsistencyCheck`**

```java
package dev.hendrikhoemberg.webtesthelper.checks.site;

import dev.hendrikhoemberg.webtesthelper.checks.SiteCheck;
import dev.hendrikhoemberg.webtesthelper.model.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Sitemap entries resolve, and crawled pages are not missing from it (spec 7.1).
 * <strong>Ships disabled</strong>: plenty of pages are legitimately absent from a sitemap,
 * and enabled by default this would fill the first report with noise.
 *
 * <p>The sitemap itself arrives through {@code config.strings("sitemapUrls")}, injected by
 * the run orchestrator — a site check cannot fetch.
 */
@Component
public class SitemapConsistencyCheck implements SiteCheck {

    @Override
    public CheckType type() {
        return CheckType.SITEMAP_CONSISTENCY;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARN;
    }

    @Override
    public Set<String> emittedMessageKeys() {
        return Set.of("finding.SITEMAP_CONSISTENCY.dead_entry",
                "finding.SITEMAP_CONSISTENCY.missing_entry");
    }

    @Override
    public List<CheckFinding> evaluate(RunSnapshots snapshots, SiteContext site, CheckConfig config) {
        List<String> sitemapUrls = config.strings("sitemapUrls");
        if (sitemapUrls.isEmpty()) {
            return List.of();
        }
        Set<String> inSitemap = new LinkedHashSet<>(sitemapUrls);

        Map<String, PageSnapshot> crawled = new LinkedHashMap<>();
        for (PageSnapshot snapshot : snapshots.snapshots()) {
            UrlNormalizer.key(snapshot.finalUrl()).ifPresent(key -> crawled.putIfAbsent(key, snapshot));
        }

        List<CheckFinding> findings = new ArrayList<>();
        String page = site.baseUrl().value();

        for (String entry : inSitemap) {
            PageSnapshot snapshot = crawled.get(entry);
            Integer status = snapshot != null
                    ? snapshot.httpStatus()
                    : config.facts().verdict(entry)
                        .filter(verdict -> verdict.outcome() != UrlOutcome.OK)
                        .map(UrlVerdict::httpStatus).orElse(null);
            boolean dead = (snapshot != null && snapshot.httpStatus() >= 400)
                    || (snapshot == null && status != null);
            if (dead) {
                findings.add(new CheckFinding(type(), Severity.WARN, entry, page,
                        "finding.SITEMAP_CONSISTENCY.dead_entry",
                        List.of(entry, status == null ? "-" : String.valueOf(status)),
                        Evidence.builder().put(Evidence.HTTP_STATUS, status).build()));
            }
        }

        if (config.flag("reportMissingEntries", true)) {
            crawled.forEach((key, snapshot) -> {
                if (snapshot.httpStatus() == 200 && !inSitemap.contains(key)) {
                    findings.add(new CheckFinding(type(), Severity.INFO, key, page,
                            "finding.SITEMAP_CONSISTENCY.missing_entry", List.of(key),
                            Evidence.builder().put(Evidence.FINAL_URL, snapshot.finalUrl()).build()));
                }
            });
        }
        return findings;
    }
}
```

- [ ] **Step 8: Add the German finding copy**

Append to `src/main/resources/messages_de.properties`:

```properties
finding.TLS_CERT.expired=Das Sicherheitszertifikat ist am {0} abgelaufen. Browser zeigen jetzt eine Warnung statt der Website.
finding.TLS_CERT.expiring=Das Sicherheitszertifikat läuft in {0} Tagen ab (am {1}).
finding.TLS_CERT.invalid=Das Sicherheitszertifikat für {0} ist ungültig: {1}
finding.TLS_CERT.handshake_failed=Die verschlüsselte Verbindung kam nicht zustande: {0}
finding.HREFLANG.unreachable=Die Sprachfassung „{0}" verweist auf {1}, diese Adresse antwortet aber mit Status {2}.
finding.HREFLANG.not_reciprocal=Die Seite {0} verweist auf diese Sprachfassung, umgekehrt fehlt der Verweis auf {1}.
finding.SITEMAP_CONSISTENCY.dead_entry=Die Sitemap führt {0} auf, diese Adresse antwortet aber mit Status {1}.
finding.SITEMAP_CONSISTENCY.missing_entry=Die Seite {0} wurde gefunden, fehlt aber in der Sitemap.
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=SitemapReaderTest,TlsCertCheckTest,HreflangCheckTest,SitemapConsistencyCheckTest,CheckDocumentationTest,CheckPurityTest`
Expected: PASS. `everyCheckTypeExceptPageUnreachableHasAnImplementation` now proves the
layer-1 catalog is complete.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/{checks,crawler} src/main/resources/messages_de.properties src/test/java/dev/hendrikhoemberg/webtesthelper
git commit -m "feat(checks): add TLS_CERT, HREFLANG and SITEMAP_CONSISTENCY

The TLS handshake sits behind CertificateInspector so the check stays a
pure function. SITEMAP_CONSISTENCY ships disabled and receives the sitemap
through its CheckConfig."
```

---

### Task 14: Fingerprinting and materialisation — site-wide promotion

The identity model of §6.2, and the one place where getting it wrong quietly destroys the
product.

```
fingerprint = sha256(siteId, checkType, subjectKey, locationKey)
```

`subjectKey` is already normalised by the check (Task 2 did that work). `locationKey` is the
part that is easy to get wrong:

- Exact page URL for everything: a broken footer image becomes 312 separate findings and
  muting is useless.
- Omit it: the finding cannot say where.

So: **if a subject appears on more than `SITE_WIDE_THRESHOLD` pages (default 5), the finding
is site-wide and `locationKey = "*"`.** It reads *"logo-x.png returns 404 — on 312 pages."*
Otherwise `locationKey` is the normalised page path. **Occurrences always record every exact
page**, so detail is never lost.

This is why materialisation is a post-crawl step: you cannot know a subject is site-wide
until the crawl is complete.

**Files:**
- Create: `src/main/java/.../findings/package-info.java`
- Create: `src/main/java/.../findings/core/Fingerprints.java`
- Create: `src/main/java/.../findings/core/MaterialisedFinding.java`
- Create: `src/main/java/.../findings/core/FindingMaterialiser.java`
- Modify: `src/main/java/.../runner/package-info.java` (add `"findings"`)
- Test: `src/test/java/.../findings/core/FingerprintsTest.java`
- Test: `src/test/java/.../findings/core/FindingMaterialiserTest.java`

**Interfaces:**
- Consumes: `model.CheckFinding`, `model.CheckType`, `model.Severity`, `model.Evidence`,
  `model.UrlNormalizer`.
- Produces:
  - `Fingerprints.of(long siteId, CheckType, String subjectKey, String locationKey) -> String`
  - `MaterialisedFinding(String fingerprint, CheckType checkType, Severity severity,
    String subjectKey, String locationKey, boolean siteWide, String messageKey,
    List<String> messageArgs, Evidence evidence, List<Occurrence> occurrences)` with
    `occurrenceCount()`, and nested `Occurrence(String pageUrl, Evidence evidence)`
  - `MaterialisedFinding.SITE_WIDE_LOCATION` = `"*"`
  - `FindingMaterialiser.materialise(long siteId, List<CheckFinding>, int siteWideThreshold)
    -> List<MaterialisedFinding>` and `FindingMaterialiser.DEFAULT_SITE_WIDE_THRESHOLD` = 5

- [ ] **Step 1: Write the failing tests**

`FingerprintsTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.findings.core;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprintsTest {

    @Test
    void isStableForTheSameInputs() {
        String first = Fingerprints.of(7, CheckType.DEAD_LINK, "https://ziel.de/weg", "/kontakt");
        String second = Fingerprints.of(7, CheckType.DEAD_LINK, "https://ziel.de/weg", "/kontakt");

        assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void everyComponentChangesTheFingerprint() {
        String base = Fingerprints.of(7, CheckType.DEAD_LINK, "https://ziel.de/weg", "/kontakt");

        assertThat(Fingerprints.of(8, CheckType.DEAD_LINK, "https://ziel.de/weg", "/kontakt"))
                .isNotEqualTo(base);
        assertThat(Fingerprints.of(7, CheckType.IMAGE_BROKEN, "https://ziel.de/weg", "/kontakt"))
                .isNotEqualTo(base);
        assertThat(Fingerprints.of(7, CheckType.DEAD_LINK, "https://ziel.de/anders", "/kontakt"))
                .isNotEqualTo(base);
        assertThat(Fingerprints.of(7, CheckType.DEAD_LINK, "https://ziel.de/weg", "/impressum"))
                .isNotEqualTo(base);
    }

    @Test
    void componentsCannotBeShiftedAcrossTheirBoundaries() {
        // Without a separator, ("ab","c") and ("a","bc") would hash identically and two
        // unrelated findings would share a fingerprint.
        assertThat(Fingerprints.of(1, CheckType.DEAD_LINK, "ab", "c"))
                .isNotEqualTo(Fingerprints.of(1, CheckType.DEAD_LINK, "a", "bc"));
    }
}
```

`FindingMaterialiserTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.findings.core;

import dev.hendrikhoemberg.webtesthelper.model.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FindingMaterialiserTest {

    private static final long SITE = 42L;

    private final FindingMaterialiser materialiser = new FindingMaterialiser();

    private static CheckFinding deadLink(String target, String page) {
        return new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, target, page,
                "finding.DEAD_LINK.dead", List.of(target, "404"),
                Evidence.builder().put(Evidence.HTTP_STATUS, 404).build());
    }

    @Test
    void nothingInNothingOut() {
        assertThat(materialiser.materialise(SITE, List.of(),
                FindingMaterialiser.DEFAULT_SITE_WIDE_THRESHOLD)).isEmpty();
    }

    @Test
    void aSubjectOnTwoPagesBecomesTwoLocationScopedFindings() {
        List<CheckFinding> input = List.of(
                deadLink("https://ziel.de/weg", "https://example.com/kontakt"),
                deadLink("https://ziel.de/weg", "https://example.com/impressum"));

        List<MaterialisedFinding> result = materialiser.materialise(SITE, input, 5);

        assertThat(result).hasSize(2)
                .extracting(MaterialisedFinding::locationKey)
                .containsExactlyInAnyOrder("/kontakt", "/impressum");
        assertThat(result).allSatisfy(finding -> {
            assertThat(finding.siteWide()).isFalse();
            assertThat(finding.occurrenceCount()).isEqualTo(1);
        });
    }

    @Test
    void aSubjectOnMoreThanTheThresholdBecomesOneSiteWideFinding() {
        // "logo-x.png returns 404 — on 312 pages", not 312 findings (spec 6.2).
        List<CheckFinding> input = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            input.add(deadLink("https://ziel.de/weg", "https://example.com/seite-" + i));
        }

        List<MaterialisedFinding> result = materialiser.materialise(SITE, input, 5);

        assertThat(result).singleElement().satisfies(finding -> {
            assertThat(finding.siteWide()).isTrue();
            assertThat(finding.locationKey()).isEqualTo(MaterialisedFinding.SITE_WIDE_LOCATION);
            assertThat(finding.occurrenceCount()).isEqualTo(6);
            assertThat(finding.fingerprint()).isEqualTo(
                    Fingerprints.of(SITE, CheckType.DEAD_LINK, "https://ziel.de/weg", "*"));
        });
    }

    @Test
    void exactlyTheThresholdIsNotYetSiteWide() {
        // Spec 6.2 says "more than SITE_WIDE_THRESHOLD", so five pages at a threshold of
        // five stays page-scoped.
        List<CheckFinding> input = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            input.add(deadLink("https://ziel.de/weg", "https://example.com/seite-" + i));
        }

        assertThat(materialiser.materialise(SITE, input, 5)).hasSize(5)
                .allSatisfy(finding -> assertThat(finding.siteWide()).isFalse());
    }

    @Test
    void aSiteWideFindingStillRecordsEveryExactPage() {
        List<CheckFinding> input = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            input.add(deadLink("https://ziel.de/weg", "https://example.com/seite-" + i));
        }

        MaterialisedFinding finding = materialiser.materialise(SITE, input, 5).getFirst();

        assertThat(finding.occurrences()).extracting(MaterialisedFinding.Occurrence::pageUrl)
                .containsExactlyInAnyOrder(
                        "https://example.com/seite-0", "https://example.com/seite-1",
                        "https://example.com/seite-2", "https://example.com/seite-3",
                        "https://example.com/seite-4", "https://example.com/seite-5",
                        "https://example.com/seite-6", "https://example.com/seite-7");
    }

    @Test
    void theSameSubjectSeenTwiceOnOnePageCountsAsOnePageForPromotion() {
        List<CheckFinding> input = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            input.add(deadLink("https://ziel.de/weg", "https://example.com/kontakt"));
        }

        List<MaterialisedFinding> result = materialiser.materialise(SITE, input, 5);

        assertThat(result).singleElement().satisfies(finding -> {
            assertThat(finding.siteWide()).isFalse();
            assertThat(finding.locationKey()).isEqualTo("/kontakt");
            assertThat(finding.occurrenceCount()).isEqualTo(20);
        });
    }

    @Test
    void differentCheckTypesForOneSubjectStaySeparateFindings() {
        List<CheckFinding> input = List.of(
                deadLink("https://example.com/bild.png", "https://example.com/a"),
                new CheckFinding(CheckType.IMAGE_BROKEN, Severity.ERROR, "https://example.com/bild.png",
                        "https://example.com/a", "finding.IMAGE_BROKEN.not_rendered", List.of(), Evidence.empty()));

        assertThat(materialiser.materialise(SITE, input, 5)).hasSize(2)
                .extracting(MaterialisedFinding::checkType)
                .containsExactlyInAnyOrder(CheckType.DEAD_LINK, CheckType.IMAGE_BROKEN);
    }

    @Test
    void theHighestSeverityInABucketWins() {
        List<CheckFinding> input = List.of(
                new CheckFinding(CheckType.DEAD_LINK, Severity.INFO, "https://ziel.de/weg",
                        "https://example.com/a", "finding.DEAD_LINK.unverifiable", List.of(), Evidence.empty()),
                new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "https://ziel.de/weg",
                        "https://example.com/a", "finding.DEAD_LINK.dead", List.of(), Evidence.empty()));

        assertThat(materialiser.materialise(SITE, input, 5)).singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(Severity.ERROR));
    }

    @Test
    void theFirstNonEmptyEvidenceInABucketIsKept() {
        List<CheckFinding> input = List.of(
                new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "https://ziel.de/weg",
                        "https://example.com/a", "finding.DEAD_LINK.dead", List.of(), Evidence.empty()),
                deadLink("https://ziel.de/weg", "https://example.com/a"));

        assertThat(materialiser.materialise(SITE, input, 5)).singleElement()
                .satisfies(finding ->
                        assertThat(finding.evidence().get(Evidence.HTTP_STATUS)).hasValue("404"));
    }

    @Test
    void locationKeysAreNormalisedPathsRatherThanFullUrls() {
        List<CheckFinding> input = List.of(
                deadLink("https://ziel.de/weg", "https://www.example.com/Aktuelles/?page=2&utm_source=x"));

        assertThat(materialiser.materialise(SITE, input, 5)).singleElement()
                .satisfies(finding -> assertThat(finding.locationKey()).isEqualTo("/Aktuelles?page=2"));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=FingerprintsTest,FindingMaterialiserTest`
Expected: compilation failure — the `findings` package does not exist.

- [ ] **Step 3: Write `Fingerprints`**

```java
package dev.hendrikhoemberg.webtesthelper.findings.core;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** {@code fingerprint = sha256(siteId, checkType, subjectKey, locationKey)} — spec 6.2. */
public final class Fingerprints {

    /**
     * A byte no URL, path or enum name can contain. Without it, {@code ("ab","c")} and
     * {@code ("a","bc")} hash identically and two unrelated findings share an identity.
     */
    private static final byte SEPARATOR = 0x00;

    private Fingerprints() {
    }

    public static String of(long siteId, CheckType checkType, String subjectKey, String locationKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            feed(digest, Long.toString(siteId));
            feed(digest, checkType.name());
            feed(digest, subjectKey == null ? "" : subjectKey);
            feed(digest, locationKey == null ? "" : locationKey);
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 ist in dieser JVM nicht verfügbar", e);
        }
    }

    private static void feed(MessageDigest digest, String component) {
        digest.update(component.getBytes(StandardCharsets.UTF_8));
        digest.update(SEPARATOR);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
```

- [ ] **Step 4: Write `MaterialisedFinding`**

```java
package dev.hendrikhoemberg.webtesthelper.findings.core;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.List;

/**
 * A {@code CheckFinding} bucket after identity has been decided: it has a fingerprint, a
 * location scope and every page it was seen on. Still not persisted — Task 16 turns this
 * into rows.
 */
public record MaterialisedFinding(String fingerprint, CheckType checkType, Severity severity,
                                  String subjectKey, String locationKey, boolean siteWide,
                                  String messageKey, List<String> messageArgs, Evidence evidence,
                                  List<Occurrence> occurrences) {

    /** The {@code locationKey} of a promoted finding: "everywhere on this site" (spec 6.2). */
    public static final String SITE_WIDE_LOCATION = "*";

    public MaterialisedFinding {
        messageArgs = List.copyOf(messageArgs);
        occurrences = List.copyOf(occurrences);
    }

    public int occurrenceCount() {
        return occurrences.size();
    }

    /** Distinct pages this finding was seen on — what the site-wide sentence counts. */
    public long distinctPageCount() {
        return occurrences.stream().map(Occurrence::pageUrl).distinct().count();
    }

    public record Occurrence(String pageUrl, Evidence evidence) {
    }
}
```

- [ ] **Step 5: Write `FindingMaterialiser`**

```java
package dev.hendrikhoemberg.webtesthelper.findings.core;

import dev.hendrikhoemberg.webtesthelper.model.*;

import java.util.*;

/**
 * Turns a run's {@code CheckFinding}s into fingerprinted findings (spec 6.2).
 *
 * <p>Two passes, and the order matters. First group by <em>subject</em> — check type plus
 * subject key — because promotion is a property of the subject, not of any one page. Only
 * then decide the location scope: a subject seen on more than the threshold's worth of
 * distinct pages collapses to one site-wide finding; anything else splits per page.
 *
 * <p>This cannot run during the crawl. Until the last page is visited, no subject's page
 * count is final, so no fingerprint is final either.
 */
public class FindingMaterialiser {

    public static final int DEFAULT_SITE_WIDE_THRESHOLD = 5;

    public List<MaterialisedFinding> materialise(long siteId, List<CheckFinding> findings,
                                                 int siteWideThreshold) {
        if (findings.isEmpty()) {
            return List.of();
        }
        // Encounter order is preserved so a run's report reads in crawl order.
        Map<Subject, List<CheckFinding>> bySubject = new LinkedHashMap<>();
        for (CheckFinding finding : findings) {
            bySubject.computeIfAbsent(new Subject(finding.checkType(), finding.subjectKey()),
                    key -> new ArrayList<>()).add(finding);
        }

        List<MaterialisedFinding> result = new ArrayList<>();
        bySubject.forEach((subject, group) -> {
            Set<String> distinctLocations = new LinkedHashSet<>();
            group.forEach(finding -> distinctLocations.add(locationOf(finding)));

            if (distinctLocations.size() > siteWideThreshold) {
                result.add(build(siteId, subject, MaterialisedFinding.SITE_WIDE_LOCATION, true, group));
                return;
            }
            Map<String, List<CheckFinding>> byLocation = new LinkedHashMap<>();
            for (CheckFinding finding : group) {
                byLocation.computeIfAbsent(locationOf(finding), key -> new ArrayList<>()).add(finding);
            }
            byLocation.forEach((location, atLocation) ->
                    result.add(build(siteId, subject, location, false, atLocation)));
        });
        return result;
    }

    private static MaterialisedFinding build(long siteId, Subject subject, String locationKey,
                                             boolean siteWide, List<CheckFinding> group) {
        CheckFinding representative = group.getFirst();

        Severity severity = group.getFirst().severity();
        for (CheckFinding finding : group) {
            severity = severity.max(finding.severity());
        }
        Evidence evidence = group.stream()
                .map(CheckFinding::evidence)
                .filter(candidate -> !candidate.entries().isEmpty())
                .findFirst()
                .orElse(Evidence.empty());

        List<MaterialisedFinding.Occurrence> occurrences = group.stream()
                .map(finding -> new MaterialisedFinding.Occurrence(
                        finding.observedPageUrl(), finding.evidence()))
                .toList();

        return new MaterialisedFinding(
                Fingerprints.of(siteId, subject.checkType(), subject.subjectKey(), locationKey),
                subject.checkType(), severity, subject.subjectKey(), locationKey, siteWide,
                representative.messageKey(), representative.messageArgs(), evidence, occurrences);
    }

    private static String locationOf(CheckFinding finding) {
        return UrlNormalizer.locationKeyOf(finding.observedPageUrl());
    }

    /** Promotion is a property of the subject, so the subject is the grouping key. */
    private record Subject(CheckType checkType, String subjectKey) {
    }
}
```

`findings/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Findings",
        allowedDependencies = {"model"})
package dev.hendrikhoemberg.webtesthelper.findings;
```

Then add `"findings"` to `runner/package-info.java`'s `allowedDependencies`, which should
now read `{"model", "catalog", "crawler", "checks", "findings"}`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=FingerprintsTest,FindingMaterialiserTest,CheckPurityTest,ModularityTest`
Expected: PASS. `CheckPurityTest.findingAlgorithmsStayPure` is no longer vacuous.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/{findings,runner} src/test/java/dev/hendrikhoemberg/webtesthelper/findings
git commit -m "feat(findings): add fingerprinting and site-wide promotion

Grouping is by subject first, because promotion is a property of the
subject rather than of any one page. More than five distinct pages
collapses to locationKey='*'; occurrences keep every exact page (spec 6.2)."
```

---

### Task 15: Coverage-scoped diff

Coverage is load-bearing (§6.4). A run records the check types it ran and the URLs it
visited, and **resolution applies only within coverage. A finding outside a run's coverage
is left untouched.**

Without this rule, a daily pulse visiting 10 pages marks the broken image on `/leistungen`
as fixed; the weekly full crawl then reports it as regressed. Every week, forever. The same
rule protects budget-capped runs: a run that hit its page or duration budget completes with
partial coverage and resolves nothing it did not reach.

The spec says this gets an explicit test. It gets four.

The two lifecycle axes are orthogonal (§6.3): `observed` is system-owned
(`ACTIVE`/`RESOLVED`), `triage` is human-owned
(`UNTRIAGED`/`ACKNOWLEDGED`/`MUTED`/`WONT_FIX`). Acknowledging a finding must never erase
the fact that it is still broken — and, symmetrically, muting one must never stop the system
from noticing it got fixed.

**Files:**
- Create: `src/main/java/.../model/ObservedState.java`, `TriageState.java`, `RunCoverage.java`
- Create: `src/main/java/.../findings/core/ExistingFinding.java`
- Create: `src/main/java/.../findings/core/FindingDiff.java`
- Create: `src/main/java/.../findings/core/FindingDiffEngine.java`
- Test: `src/test/java/.../model/RunCoverageTest.java`
- Test: `src/test/java/.../findings/core/FindingDiffEngineTest.java`

**Interfaces:**
- Consumes: `findings.core.MaterialisedFinding`, `model.CheckType`.
- Produces:
  - `ObservedState { ACTIVE, RESOLVED }`, `TriageState { UNTRIAGED, ACKNOWLEDGED, MUTED, WONT_FIX }`
  - `RunCoverage(Set<CheckType> checkTypes, Set<String> visitedUrls, boolean partial)` with
    `visitedLocationKeys()`, `covers(CheckType, String locationKey)`, `RunCoverage.none()`
  - `ExistingFinding(long id, String fingerprint, CheckType checkType, String locationKey,
    boolean siteWide, ObservedState observedState, TriageState triageState,
    long firstSeenRunId, int occurrenceCount)`
  - `FindingDiff(List<MaterialisedFinding> created, List<Update> regressed, List<Update> stillOpen,
    List<Update> known, List<ExistingFinding> resolved, List<ExistingFinding> outsideCoverage)`
    with nested `Update(ExistingFinding existing, MaterialisedFinding current)`
  - `FindingDiffEngine.diff(List<MaterialisedFinding>, List<ExistingFinding>, RunCoverage) -> FindingDiff`

- [ ] **Step 1: Write the failing tests**

`RunCoverageTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RunCoverageTest {

    @Test
    void visitedUrlsAreExposedAsNormalisedLocationKeys() {
        RunCoverage coverage = new RunCoverage(EnumSet.of(CheckType.DEAD_LINK),
                Set.of("https://example.com/leistungen", "https://example.com/aktuelles?page=2"), false);

        assertThat(coverage.visitedLocationKeys())
                .containsExactlyInAnyOrder("/leistungen", "/aktuelles?page=2");
    }

    @Test
    void aCheckTypeThatDidNotRunIsNeverCovered() {
        RunCoverage coverage = new RunCoverage(EnumSet.of(CheckType.DEAD_LINK),
                Set.of("https://example.com/leistungen"), false);

        assertThat(coverage.covers(CheckType.DEAD_LINK, "/leistungen")).isTrue();
        assertThat(coverage.covers(CheckType.TLS_CERT, "/leistungen")).isFalse();
    }

    @Test
    void aPageThatWasNotVisitedIsNotCovered() {
        RunCoverage coverage = new RunCoverage(EnumSet.allOf(CheckType.class),
                Set.of("https://example.com/leistungen"), false);

        assertThat(coverage.covers(CheckType.DEAD_LINK, "/impressum")).isFalse();
    }

    @Test
    void aSiteWideLocationIsOnlyCoveredByACompleteRun() {
        RunCoverage complete = new RunCoverage(EnumSet.allOf(CheckType.class),
                Set.of("https://example.com/a"), false);
        RunCoverage partial = new RunCoverage(EnumSet.allOf(CheckType.class),
                Set.of("https://example.com/a"), true);

        assertThat(complete.covers(CheckType.IMAGE_BROKEN, "*")).isTrue();
        assertThat(partial.covers(CheckType.IMAGE_BROKEN, "*")).isFalse();
    }

    @Test
    void anEmptyCoverageCoversNothing() {
        assertThat(RunCoverage.none().covers(CheckType.DEAD_LINK, "/a")).isFalse();
        assertThat(RunCoverage.none().covers(CheckType.DEAD_LINK, "*")).isFalse();
    }
}
```

`FindingDiffEngineTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.findings.core;

import dev.hendrikhoemberg.webtesthelper.model.*;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FindingDiffEngineTest {

    private static final long SITE = 1L;

    private final FindingDiffEngine engine = new FindingDiffEngine();

    private static MaterialisedFinding current(String subject, String location) {
        return new MaterialisedFinding(
                Fingerprints.of(SITE, CheckType.IMAGE_BROKEN, subject, location),
                CheckType.IMAGE_BROKEN, Severity.ERROR, subject, location,
                MaterialisedFinding.SITE_WIDE_LOCATION.equals(location),
                "finding.IMAGE_BROKEN.not_rendered", List.of(subject), Evidence.empty(),
                List.of(new MaterialisedFinding.Occurrence("https://example.com" + location, Evidence.empty())));
    }

    private static ExistingFinding existing(String subject, String location, ObservedState observed,
                                            TriageState triage) {
        return new ExistingFinding(100L, Fingerprints.of(SITE, CheckType.IMAGE_BROKEN, subject, location),
                CheckType.IMAGE_BROKEN, location,
                MaterialisedFinding.SITE_WIDE_LOCATION.equals(location), observed, triage, 1L, 3);
    }

    private static RunCoverage fullCoverage(String... visitedPaths) {
        return new RunCoverage(EnumSet.allOf(CheckType.class),
                Set.of(java.util.Arrays.stream(visitedPaths)
                        .map(path -> "https://example.com" + path).toArray(String[]::new)), false);
    }

    @Test
    void anUnknownFingerprintIsNew() {
        FindingDiff diff = engine.diff(List.of(current("https://example.com/logo.png", "/leistungen")),
                List.of(), fullCoverage("/leistungen"));

        assertThat(diff.created()).hasSize(1);
        assertThat(diff.regressed()).isEmpty();
        assertThat(diff.resolved()).isEmpty();
    }

    @Test
    void anActiveUntriagedFindingSeenAgainIsStillOpen() {
        FindingDiff diff = engine.diff(
                List.of(current("https://example.com/logo.png", "/leistungen")),
                List.of(existing("https://example.com/logo.png", "/leistungen",
                        ObservedState.ACTIVE, TriageState.UNTRIAGED)),
                fullCoverage("/leistungen"));

        assertThat(diff.stillOpen()).hasSize(1);
        assertThat(diff.created()).isEmpty();
        assertThat(diff.known()).isEmpty();
    }

    @Test
    void anAcknowledgedFindingSeenAgainIsKnownRatherThanStillOpen() {
        // Spec 6.3: triage is a separate axis. It changes which report section the finding
        // lands in, never whether the system still considers it broken.
        FindingDiff diff = engine.diff(
                List.of(current("https://example.com/logo.png", "/leistungen")),
                List.of(existing("https://example.com/logo.png", "/leistungen",
                        ObservedState.ACTIVE, TriageState.ACKNOWLEDGED)),
                fullCoverage("/leistungen"));

        assertThat(diff.known()).hasSize(1);
        assertThat(diff.stillOpen()).isEmpty();
    }

    @Test
    void aPreviouslyResolvedFindingSeenAgainIsARegression() {
        FindingDiff diff = engine.diff(
                List.of(current("https://example.com/logo.png", "/leistungen")),
                List.of(existing("https://example.com/logo.png", "/leistungen",
                        ObservedState.RESOLVED, TriageState.UNTRIAGED)),
                fullCoverage("/leistungen"));

        assertThat(diff.regressed()).hasSize(1);
        assertThat(diff.stillOpen()).isEmpty();
    }

    @Test
    void anActiveFindingAbsentFromACoveredPageIsResolved() {
        FindingDiff diff = engine.diff(List.of(),
                List.of(existing("https://example.com/logo.png", "/leistungen",
                        ObservedState.ACTIVE, TriageState.UNTRIAGED)),
                fullCoverage("/leistungen"));

        assertThat(diff.resolved()).hasSize(1);
        assertThat(diff.outsideCoverage()).isEmpty();
    }

    // ---------------------------------------------------------------- spec 6.4

    @Test
    void aRunThatDidNotVisitAUrlMustNotResolveFindingsAtThatUrl() {
        // The explicit test spec 6.4 asks for.
        FindingDiff diff = engine.diff(List.of(),
                List.of(existing("https://example.com/logo.png", "/leistungen",
                        ObservedState.ACTIVE, TriageState.UNTRIAGED)),
                fullCoverage("/", "/kontakt"));

        assertThat(diff.resolved()).isEmpty();
        assertThat(diff.outsideCoverage()).hasSize(1);
    }

    @Test
    void aPulseRunDoesNotResolveFullCrawlFindings() {
        // The failure mode spec 6.4 names: a daily pulse visiting ten pages marks the broken
        // image on /leistungen fixed, and the weekly full crawl reports it regressed. Forever.
        RunCoverage pulse = new RunCoverage(RunScope.PULSE.checkTypes(),
                Set.of("https://example.com/", "https://example.com/kontakt"), false);

        FindingDiff diff = engine.diff(List.of(),
                List.of(existing("https://example.com/logo.png", "/leistungen",
                        ObservedState.ACTIVE, TriageState.UNTRIAGED)), pulse);

        assertThat(diff.resolved()).isEmpty();
        assertThat(diff.outsideCoverage()).hasSize(1);
    }

    @Test
    void aCheckTypeThatDidNotRunResolvesNothingEvenOnAVisitedPage() {
        RunCoverage withoutImages = new RunCoverage(EnumSet.of(CheckType.DEAD_LINK),
                Set.of("https://example.com/leistungen"), false);

        FindingDiff diff = engine.diff(List.of(),
                List.of(existing("https://example.com/logo.png", "/leistungen",
                        ObservedState.ACTIVE, TriageState.UNTRIAGED)), withoutImages);

        assertThat(diff.resolved()).isEmpty();
        assertThat(diff.outsideCoverage()).hasSize(1);
    }

    @Test
    void aBudgetCappedRunResolvesNothingSiteWide() {
        RunCoverage partial = new RunCoverage(EnumSet.allOf(CheckType.class),
                Set.of("https://example.com/", "https://example.com/kontakt"), true);

        FindingDiff diff = engine.diff(List.of(),
                List.of(existing("https://example.com/logo.png",
                        MaterialisedFinding.SITE_WIDE_LOCATION, ObservedState.ACTIVE,
                        TriageState.UNTRIAGED)), partial);

        assertThat(diff.resolved()).isEmpty();
        assertThat(diff.outsideCoverage()).hasSize(1);
    }

    // ----------------------------------------------------------------

    @Test
    void aMutedFindingIsStillResolvedWhenItActuallyGetsFixed() {
        // The two axes are orthogonal (spec 6.3). Muting silences the report, not the system.
        FindingDiff diff = engine.diff(List.of(),
                List.of(existing("https://example.com/logo.png", "/leistungen",
                        ObservedState.ACTIVE, TriageState.MUTED)),
                fullCoverage("/leistungen"));

        assertThat(diff.resolved()).hasSize(1);
    }

    @Test
    void anAlreadyResolvedFindingThatStaysAbsentIsNotResolvedTwice() {
        FindingDiff diff = engine.diff(List.of(),
                List.of(existing("https://example.com/logo.png", "/leistungen",
                        ObservedState.RESOLVED, TriageState.UNTRIAGED)),
                fullCoverage("/leistungen"));

        assertThat(diff.resolved()).isEmpty();
        assertThat(diff.outsideCoverage()).isEmpty();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=RunCoverageTest,FindingDiffEngineTest`
Expected: compilation failure — `RunCoverage` and `FindingDiffEngine` do not exist.

- [ ] **Step 3: Write the lifecycle enums and `RunCoverage`**

```java
package dev.hendrikhoemberg.webtesthelper.model;

/** System-owned axis (spec 6.3). Never changed by a human. */
public enum ObservedState { ACTIVE, RESOLVED }
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

/** Human-owned axis (spec 6.3). Never changed by the system. */
public enum TriageState {
    UNTRIAGED, ACKNOWLEDGED, MUTED, WONT_FIX;

    public boolean triaged() {
        return this != UNTRIAGED;
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a run actually covered (spec 6.4). Resolution applies only within it.
 *
 * @param visitedUrls normalised URLs of pages the run successfully loaded
 * @param partial     true when a budget guard ended the crawl early, in which case the run
 *                    proves nothing about the site as a whole
 */
public record RunCoverage(Set<CheckType> checkTypes, Set<String> visitedUrls, boolean partial) {

    public RunCoverage {
        checkTypes = checkTypes.isEmpty() ? EnumSet.noneOf(CheckType.class) : EnumSet.copyOf(checkTypes);
        visitedUrls = Set.copyOf(visitedUrls);
    }

    public static RunCoverage none() {
        return new RunCoverage(EnumSet.noneOf(CheckType.class), Set.of(), true);
    }

    /** The visited set expressed the way a finding's {@code locationKey} is (spec 6.2). */
    public Set<String> visitedLocationKeys() {
        Set<String> keys = new LinkedHashSet<>();
        visitedUrls.forEach(url -> keys.add(UrlNormalizer.locationKeyOf(url)));
        return keys;
    }

    /**
     * Whether this run is entitled to say anything about a finding of {@code checkType} at
     * {@code locationKey}.
     *
     * <p>A site-wide location ({@code "*"}) needs a complete run: a crawl that stopped at its
     * budget cannot show that a subject has disappeared from every page.
     */
    public boolean covers(CheckType checkType, String locationKey) {
        if (!checkTypes.contains(checkType)) {
            return false;
        }
        if ("*".equals(locationKey)) {
            return !partial && !visitedUrls.isEmpty();
        }
        return visitedLocationKeys().contains(locationKey);
    }
}
```

> `covers` recomputes `visitedLocationKeys()` on each call, which is fine for a per-run diff
> over a few hundred findings. `FindingDiffEngine` hoists it out of the loop anyway.

- [ ] **Step 4: Write `ExistingFinding` and `FindingDiff`**

```java
package dev.hendrikhoemberg.webtesthelper.findings.core;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.ObservedState;
import dev.hendrikhoemberg.webtesthelper.model.TriageState;

/**
 * The persisted finding, reduced to what the diff needs. A record rather than the entity so
 * {@code findings.core} stays free of JPA (spec 5.1).
 */
public record ExistingFinding(long id, String fingerprint, CheckType checkType, String locationKey,
                              boolean siteWide, ObservedState observedState, TriageState triageState,
                              long firstSeenRunId, int occurrenceCount) {

    public boolean active() {
        return observedState == ObservedState.ACTIVE;
    }
}
```

```java
package dev.hendrikhoemberg.webtesthelper.findings.core;

import java.util.List;

/**
 * The run-to-run change set. The report's sections come straight off these lists (spec 6.3):
 * New, Regressed, Still open, Known, Fixed.
 *
 * @param outsideCoverage active findings this run was not entitled to judge (spec 6.4). They
 *                        are neither resolved nor reported as change — they are left alone,
 *                        and naming them explicitly keeps that visible rather than implicit.
 */
public record FindingDiff(List<MaterialisedFinding> created, List<Update> regressed,
                          List<Update> stillOpen, List<Update> known,
                          List<ExistingFinding> resolved, List<ExistingFinding> outsideCoverage) {

    public FindingDiff {
        created = List.copyOf(created);
        regressed = List.copyOf(regressed);
        stillOpen = List.copyOf(stillOpen);
        known = List.copyOf(known);
        resolved = List.copyOf(resolved);
        outsideCoverage = List.copyOf(outsideCoverage);
    }

    public record Update(ExistingFinding existing, MaterialisedFinding current) {
    }

    /** Everything the run saw as broken, whatever its triage state. */
    public int activeCount() {
        return created.size() + regressed.size() + stillOpen.size() + known.size();
    }

    /** What spec 11.1 sends mail about: new or regressed. */
    public boolean hasChange() {
        return !created.isEmpty() || !regressed.isEmpty() || !resolved.isEmpty();
    }
}
```

- [ ] **Step 5: Write `FindingDiffEngine`**

```java
package dev.hendrikhoemberg.webtesthelper.findings.core;

import dev.hendrikhoemberg.webtesthelper.model.ObservedState;
import dev.hendrikhoemberg.webtesthelper.model.RunCoverage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Run-to-run diff, scoped by coverage (spec 6.3, 6.4). Pure: no database, no clock, no
 * Spring — the run orchestrator supplies both sides and persists the outcome.
 */
public class FindingDiffEngine {

    public FindingDiff diff(List<MaterialisedFinding> current, List<ExistingFinding> existing,
                            RunCoverage coverage) {

        Map<String, ExistingFinding> byFingerprint = new LinkedHashMap<>();
        existing.forEach(finding -> byFingerprint.put(finding.fingerprint(), finding));

        List<MaterialisedFinding> created = new ArrayList<>();
        List<FindingDiff.Update> regressed = new ArrayList<>();
        List<FindingDiff.Update> stillOpen = new ArrayList<>();
        List<FindingDiff.Update> known = new ArrayList<>();

        Set<String> seenThisRun = new java.util.LinkedHashSet<>();

        for (MaterialisedFinding finding : current) {
            seenThisRun.add(finding.fingerprint());
            ExistingFinding previous = byFingerprint.get(finding.fingerprint());
            if (previous == null) {
                created.add(finding);
            } else if (previous.observedState() == ObservedState.RESOLVED) {
                regressed.add(new FindingDiff.Update(previous, finding));
            } else if (previous.triageState().triaged()) {
                known.add(new FindingDiff.Update(previous, finding));
            } else {
                stillOpen.add(new FindingDiff.Update(previous, finding));
            }
        }

        // Hoisted: covers() would otherwise rebuild the location-key set per finding.
        Set<String> visitedLocations = coverage.visitedLocationKeys();

        List<ExistingFinding> resolved = new ArrayList<>();
        List<ExistingFinding> outsideCoverage = new ArrayList<>();

        for (ExistingFinding previous : existing) {
            if (seenThisRun.contains(previous.fingerprint()) || !previous.active()) {
                continue;
            }
            if (covers(coverage, visitedLocations, previous)) {
                resolved.add(previous);
            } else {
                // Spec 6.4: a finding outside this run's coverage is left untouched. Without
                // this branch, a pulse run resolves what the weekly full crawl then reports
                // as regressed — every week, forever.
                outsideCoverage.add(previous);
            }
        }

        return new FindingDiff(created, regressed, stillOpen, known, resolved, outsideCoverage);
    }

    private static boolean covers(RunCoverage coverage, Set<String> visitedLocations,
                                  ExistingFinding finding) {
        if (!coverage.checkTypes().contains(finding.checkType())) {
            return false;
        }
        if (finding.siteWide() || MaterialisedFinding.SITE_WIDE_LOCATION.equals(finding.locationKey())) {
            return !coverage.partial() && !coverage.visitedUrls().isEmpty();
        }
        return visitedLocations.contains(finding.locationKey());
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=RunCoverageTest,FindingDiffEngineTest,CheckPurityTest`
Expected: PASS, all sixteen cases — including the four that spec §6.4 asks for by name.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/{model,findings} src/test/java/dev/hendrikhoemberg/webtesthelper/{model,findings}
git commit -m "feat(findings): add coverage-scoped run-to-run diff

Resolution applies only within a run's coverage (spec 6.4): a pulse run
does not resolve full-crawl findings, and a budget-capped run resolves
nothing site-wide. Observed and triage state stay orthogonal."
```

---
