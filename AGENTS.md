# Project Instructions

## Tech Stack
- Language: Java 25
- Framework: Spring Boot 4 (Web MVC), Thymeleaf, HTMX (+ Alpine.js where needed)
- Build: Maven (`./mvnw` wrapper)
- Frontend: vanilla HTML/CSS/JS — no bundler, no framework
- Test stack: JUnit 5, Mockito, MockMvc, AssertJ, Testcontainers (real Postgres), Playwright for Java (`@Tag("browser")`)

## Commands
- **Test (full)**: `./mvnw test` — includes the `browser`-tagged suite (needs Chromium)
- **Test (no browser)**: `./mvnw test -Pfast` — skips `@Tag("browser")`
- **Test (single class)**: `./mvnw test -Dtest=<ClassName>`
- **Build**: `./mvnw -q compile`
- **Run**: `./mvnw spring-boot:run`

## Architecture
- `src/main/java/dev/hendrikhoemberg/webtesthelper/catalog/` — domain services
- `src/main/java/dev/hendrikhoemberg/webtesthelper/web/` — controllers, security, form models
- `src/main/resources/templates/` — Thymeleaf views (fragments for HTMX)
- `src/main/resources/static/` — vanilla CSS/JS, vendored HTMX/Alpine under `vendor/`
- `src/test/java/` — unit tests, `@WebMvcTest` slices, `AbstractPostgresTest` acceptance tests

## Conventions
- German UI only; every visible string goes through `src/main/resources/messages.properties`
- Web-layer tests: `@WebMvcTest` slices with `@Import`; acceptance tests extend `AbstractPostgresTest` (real Postgres via Testcontainers)
- HTMX endpoints return Thymeleaf fragments, not full pages
- Controllers stay thin; validation via `BindingResult.rejectValue` + message keys
- Handle errors explicitly — no silent failures

## Slimpowers workflow (always)
- **Start of every task**: consult the `slimpowers-router` skill and decide whether a skill applies
- After the router: `slimpowers:test-driven-development` for any production code, `slimpowers:systematic-debugging` for hard bugs
- **Before claiming anything is done, fixed, or passing**: `slimpowers:verification-before-completion` — run the real verification command and show its output
- The git pre-commit hook (`./mvnw test` on staged Java/pom changes) is the deterministic backstop — let it run; `--no-verify` only for real emergencies

## Boundaries
- Do not modify generated files (e.g., `target/`)
- Do not change `pom.xml` dependencies or CI/CD configuration without explicit approval
