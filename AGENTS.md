# Project Instructions

## Commands
- **Single test**: `./mvnw test -Dtest=FindingListControllerTest`
- **Verify (default)**: `bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"` — everything except the `@Tag("browser")` group; ~30 s
- **Verify (full)**: `bash -c "set -o pipefail; ./mvnw test -B --no-transfer-progress | tail -n 60"` — complete suite incl. browser acceptance tests; ~6 min
- **When full is required instead of default**: the change can reach browser acceptance tests —
  templates, `messages.properties`, controllers, layout/security, or the runner, crawler,
  recorder, checks, journeys modules. Pure Java-domain changes (services, utils, repositories)
  may finish with the default gate.
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
- Desktop-only UI: mobile/responsive layout is out of scope. Do not add mobile breakpoints or a
  collapsible sidebar; treat small-viewport findings as non-issues.
- Test-Ausgaben & Kontext-Hygiene: Bei Testläufen immer `-B --no-transfer-progress` nutzen und Output mit `set -o pipefail` und `tail` bündeln (oder temporär in `target/test.log` überschreiben), um den LLM-Kontext nicht mit tausenden Zeilen Testlogs zu überfluten.

## Boundaries
- Do not edit: `data/`, `target/`, `.env`, `compose.yaml` runtime volumes.
- Do not commit screenshots or test data.
