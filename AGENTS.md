# Project Instructions

## Commands
- **Verify (everything)**: `./mvnw test` (full suite incl. `@Tag("browser")` acceptance tests; ~95 s)
- **Single test**: `./mvnw test -Dtest=FindingListControllerTest`
- **Fast loop**: `./mvnw test -Pfast` (skips browser group only)
- **Run app**: `WTH_ADMIN_PASSWORD=evalpass123 WTH_BASE_URL=http://localhost:9090 ./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=9090`
  (needs Postgres: `docker run -d --name wth-pg -e POSTGRES_DB=webtesthelper -e POSTGRES_USER=webtesthelper -e POSTGRES_PASSWORD=webtesthelper -p 5432:5432 postgres:17-alpine`)

## Architecture
Spring Boot modular monolith (packages: web, catalog, scheduling, runner, crawler, checks, findings, reporting, recorder).
UI is Thymeleaf + HTMX + Alpine, no SPA. Templates in `src/main/resources/templates/`, CSS in `src/main/resources/static/css/app.css`,
all German UI copy in `src/main/resources/messages.properties`. Postgres + Flyway (`src/main/resources/db/migration/`).

## Conventions
- German-only UI; message keys `ui.*`; no internal identifiers (enum names, `{0}` placeholders, raw ISO instants) in rendered HTML.
- View tests: `@WebMvcTest` + MockMvc; assertions on text/markup, not on CSS.
- Journey recorder steps carry multiple ranked locator candidates; keep 0/2/4 worker pool sizes untouched.

## Boundaries
- Do not edit: `data/`, `target/`, `.env`, `compose.yaml` runtime volumes.
- Do not commit screenshots or test data.
