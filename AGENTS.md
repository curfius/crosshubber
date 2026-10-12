# AGENTS.md — Crosshubber Portal

Instructions for AI coding agents working in this repository. Read this before making
changes; it encodes invariants, build commands, and known gotchas that are not obvious
from the code alone.

## Stack (verify versions in `portal/server/pom.xml` — do not trust older docs)

| Layer | Technology |
|---|---|
| Backend | **Spring Boot 4.1.1**, Java 25 LTS, Maven — Boot 4 artifact names (`spring-boot-starter-webmvc`, `spring-boot-starter-restclient`, `spring-boot-starter-test-classic`) and **Jackson 3** (`tools.jackson.*` packages, NOT `com.fasterxml.jackson`) |
| Frontend | Angular 22, **zoneless** (`provideZonelessChangeDetection`), signals-first, standalone components, Vitest (no Karma) |
| Database | PostgreSQL 16, multi-tenant via per-tenant schema (`PGSCHEMA`), Flyway owns all DDL |
| Identity | Keycloak 26, OIDC authorization-code + PKCE |
| Messaging | NATS (event broker) |
| Style | Google Java Style via Spotless + Checkstyle (2-space, 100 cols) |

The API is **Crosshubber-owned**. The portal began as a port of an earlier Node
implementation that has since been decommissioned; good ideas were imported, the rest
dropped. Response shapes change when Crosshubber needs them to — cover changes with
tests (`mvn verify` / `npm test`), not a parity harness.

## Repo map

```
portal/
  server/                     Spring Boot backend
    src/main/java/com/crosshubber/portal/
      modules/<feature>/      Feature packages (aihub, i18n, navigation [incl. groups,
                              settings, shelltree, workspaces], registry [incl.
                              modulecontents], settings.modules, usersettings) — own
                              entity/repo/service/controller
      auth/ security/ config/ common/ bootstrap/ shell/ proxy/
    src/main/resources/
      application.yml         portal.* config; env-driven, dev defaults insecure-by-design
      db/migration/           Flyway V1..V37 (DDL only here)
      i18n-catalog.json       GENERATED seed catalog (~227 KB, 4 languages) — don't hand-edit
  ui/                         Angular 22 workspace shell
    src/app/core/             Platform services (bridge, i18n, theme, auth, config...)
    src/app/portal-core/      Shell layout, workarea, 18 lazy module UIs, feature stores
    src/design-system/        CSS-only design system (tokens + components + 18 themes)
config-management/tenants-config/   Tenant config overlays (consumed at boot)
scripts/                          Standalone tooling (e.g. sync-design-system.mjs)
docs/archive/                       Archived implementation plans
.opencode/skills/                   Project-local opencode skills (source of truth)
```

## Skill Usage

Before starting relevant work, load the appropriate skill using the `skill` tool:

- **Plans work**: `skill({ name: "context-filter" })` — before checking or working with plans
- **UI/UX work**: `skill({ name: "portal-ui-design" })` — before designing or implementing UI
- **Java code**: `skill({ name: "google-java-style" })` — before writing or reviewing Java code
- **Spring Boot backend**: `skill({ name: "spring-boot" })` — before scaffolding or refactoring Spring Boot backend

Skills are loaded on-demand via the `skill` tool. Do not rely on auto-invocation; explicitly load the relevant skill before starting relevant work.
```

## Build & verify commands

```bash
# Backend (Docker must be running — integration test uses Testcontainers PostgreSQL)
cd portal/server
mvn spotless:apply      # format first, always
mvn verify              # build + checkstyle (runs at validate) + spotless check + tests

# Frontend
cd portal/ui
npm ci                  # only when lockfile changed
npm test                # Vitest via @angular/build:unit-test
npm run build           # bundle budgets are enforced — build fails on budget overrun

# Full dev stack
docker compose up --build -d     # portal :28084, keycloak :28080, pg :25432, nats :32252
```

Login for the dev tenant: `dev/dev` (admin, all `portal-*` roles) or `devuser/dev` (limited).

## Hard invariants — do not violate

1. **API responses are Crosshubber-owned**: JSON keys, casing, nesting, null/empty
   handling, list ordering, status codes, and the `{"error":"..."}` envelope are design
   decisions of this repo (see README "Design notes"). Changing them is allowed when
   Crosshubber needs it — update tests and the README notes. Conventions that remain
   binding: key casing per endpoint is owned by the DTO/record, not global Jackson
   config — never enable `default-property-inclusion: non_null` globally.
2. **Flyway owns all DDL**. Never touch `ddl-auto` (`none` in prod; test profile uses
   `validate`). New indexes/tables = new `V<n>__*.sql` migration only.
3. **Response-shape changes need tests green**: after any change touching an HTTP
   response, run `mvn verify` and `npm test` (plus update any affected unit/snapshot
   tests). There is no parity harness.
4. **Secrets are never committed**. `config-management/tenants-config/dev/secrets.env`
   and `.env` are gitignored. `application.yml` holds insecure dev defaults by design —
   real secrets come from env/compose, never from code. **Sole exception:**
   `tenants-config/e2e/secrets.env` is a committed file of throwaway dummy values that
   are only valid inside the ephemeral `crosshubber-e2e` compose project (test stack,
   `down -v` teardown) — never reuse those values anywhere real.
5. **No TODO/FIXME comments** in either codebase (existing policy; Checkstyle and review
   enforce it). Prefer fixing or documenting in the backlog file.
6. **Formatting before done**: `mvn spotless:apply` then `mvn verify` for Java; Prettier
   (`.prettierrc`) for TS. Java comments/Javadoc follow Google Java Style (see skill).
7. **Zero warnings**: the workspace must stay warning-free — no IDE/Java-language-server
   diagnostics (deprecated API, unused code, null analysis, raw types, unchecked, m2e) and
   no compiler warnings from `mvn`/`npm`. Fix warnings introduced by your change in the
   same change; never silence one with a suppression (`@SuppressWarnings` only where a
   real unchecked cast is documented; class-level `@SuppressWarnings` for deprecation is
   forbidden). Known replacements: Jackson 3 `isTextual()`/`asText()` → `isString()`/
   `asString()`; Spring 7 `HttpStatus.UNPROCESSABLE_ENTITY` → `UNPROCESSABLE_CONTENT`;
   Testcontainers 2.x: `GenericContainer<?>` (generic), `PostgreSQLContainer` (non-generic —
   do NOT parameterize); shared JSON via `JsonUtils`, not dead injected `ObjectMapper` fields.

## Backend conventions

- Feature-based packaging: `modules.<feature>` owns everything for that feature. Cross-
  cutting code lives in `config/`, `security/`, `common/`. Features never import each
  other's internals — go through services.
- Builtin agent tools declare themselves: feature-owned `BuiltinToolProvider` beans (SPI
  in `modules/aihub/agent`) own declaration + handler; `BuiltinToolHandlers` only
  aggregates. A set `moduleKey` gates the tool on that module's active state and puts it
  in the module's Reconciler-synthesized manifest (manifest name snake-cased —
  `Keys.AGENT_NAME_RE`; catalogue/model-facing name stays camelCase). Null moduleKey =
  platform-global, never gated, in no manifest.
- Controllers stay thin: routing + status codes. Business logic in services.
- HTTP calls (Keycloak admin, LLM providers, module manifest fetches) must stay **out of
  database transactions** — blocking I/O inside `@Transactional` holds a Hikari
  connection (pool size 5). Pattern: orchestrate outside TX, persist inside TX.
- Jackson 3: use `tools.jackson.databind.*`. `JsonUtils` (common) centralizes
  parse/write helpers — don't hand-roll new ones.
- HTTP client: shared `RestClient` customization lives in `config/HttpClientConfig`
  (10s connect / 30s read). Don't create ad-hoc `HttpClient`s.
- Validation regexes and scalar-coercion helpers live in `common/Keys` and
  `common/Texts` (`KEY_RE`, `LANG_CODE_RE`, `string()`, `blankToNull`...) — extend
  them there, never re-copy (history: `plan/archive/OPTIMIZATIONS.md`).

## Frontend conventions

- Standalone components + signals (`signal/computed/effect/input/output/viewChild`).
  Legacy decorators (`@Input/@Output/@ViewChild`) are legacy debt — use signals API in
  new/edited code.
- **Zoneless**: change detection only fires from signals/DOM events — use `OnPush`
  (app-wide direction) and never rely on manual `ChangeDetectorRef.detectChanges()`.
- No `HttpClient` module — services use `fetch`. Error policy must be explicit: throw,
  or document-and-return-default; don't silently swallow `!res.ok` (see F4-style fixes).
  A shared `apiFetch` helper in `src/app/core/http/` is the preferred fetch wrapper.
- Runtime i18n is DB-backed (`I18nService`, localStorage cache keyed by `contentVersion`);
  templates call `i18n.t('key')`. Hardcoded English strings in templates are bugs.
- Design system is CSS-only (`src/design-system/`): tokens in `design-tokens.css`,
  components in `components.css`, themes in `styles/themes/`. Use tokens, never raw
  hex colors in component CSS.
- Route entry points stay lazy where possible; `Shell` must not eagerly import
  lazily-registered embedded modules (bundle duplication).
- Vitest: import `describe/it/expect` from `'vitest'` directly; jsdom environment.

## Testing expectations

- Server: 2 integration tests (`PortalSmokeTest` = absent-block tenant path,
  `TenantPolicySmokeTest` = policy-tenant path; both Testcontainers — needs Docker) +
  unit tests per service. When editing a service, add/extend its test class. No H2 —
  DB tests are Testcontainers only.
- UI: specs colocated `*.spec.ts`. Feature stores (signals) and pure helpers (diff
  engine, tree builders) are the highest-value test targets — they hold the most logic.
- E2E (`test/e2e/`, Playwright): browser regression pack against the ephemeral `e2e`
  tenant stack — `cd test/e2e && npx playwright test` (boots `crosshubber-e2e` compose
  project, tears it down with `down -v`; `E2E_KEEP_STACK=1` keeps it for debugging).
  Tenant policy lives in `config-management/tenants-config/e2e/`. Specs that assert
  English copy call `ensureEnglish` first; mutation specs (language/theme/labels)
  must restore their state — user preferences persist in the DB across runs.
  See `plan/archive/TEST_PLAN.md`.

## Gotchas / historical landmines

- **Mojibake**: files in this repo have been CP1252-double-encoded by tooling twice
  (i18n-catalog.json, several Java/TS files, fixed 2026-09-21). Symptom:
  `â€"` instead of `—`, `Â§` instead of `§`. Repair by decoding UTF-8 → re-encoding
  CP1252 → decoding UTF-8 (only if all chars > U+00FF are CP1252 specials). Never save
  files through PS 5.1 `Set-Content`/`Out-File` (they emit BOMs and mojibake on write).
- **The reconciler is fail-fast and runs at every boot** — it re-seeds i18n labels,
  providers, settings idempotently. Keep its upserts idempotent and cheap.
- **Design notes (intentional quirks)**: `registry_module_contents.roles` is stored comma-joined
  (V11) because keys are kebab-case validated; `GET /api/mfe/<key>` without trailing
  path returns 400 JSON by design; proxy upstream failures return 502 (not 500).
- **Tenant config deep-merge**: arrays in the tenant overlay REPLACE the `_default`
  arrays (don't concatenate) — `dev/tenant.json` relies on this to blank demo modules.
- **Tenant policy ownership (2026-09)**: keys/blocks present in the merged `tenant.json`
  are desired state — the Reconciler re-asserts them at every boot (builtin availability
  `modules.builtin` per-key map, `settings.defaultTheme`/`enabledThemes`, `i18n{}`
  languages, `branding{}`, `aiHub.enabledProviders[]`); keys absent from the config stay
  runtime/admin-owned; overlay `null` releases a key. Invalid config (unknown builtin
  key, unknown provider id, default language outside the enabled set) fails boot. Builtin
  module `active` is config-owned: registry PATCH → 409, UI toggle hidden. `GET
  /api/branding` serves `tenant_meta` branding rows (public, fallback = repo baseline).
  See `plan/archive/TENANT_FORK_PLAN.md` (§6 new-tenant runbook; §8 deferred follow-ups).
- **`i18n-catalog.json`** is the repo-owned seed catalog (originally exported from the
  pre-decommission Node stack; the regen script is gone). Edits are allowed; the
  reconciler seeds insert-if-absent, so changed values need a migration (see `V24__*`)
  or an update pass if existing installs must pick them up.
- Windows dev environment: PowerShell 5.1. Avoid `&&` (unsupported) — use
  `cmd1; if ($?) { cmd2 }`. Native exes with spaces in paths need the call operator.
- Remove the retired-builtin cleanup lists in `Reconciler.reconcileBuiltins`
  (`llm-providers`, `ai-assistant`, 3 retired entry points) once every long-lived
  install has booted past the 2026-09 rename — they cost ~5 findById lookups per boot
  and exist only to delete stale rows on upgrades.
- **Dev Keycloak is ephemeral**: `KC_DB: dev-file` with no data volume — every
  `keycloak` recreate reimports `tenants-config/<tenant>/realm.json` and wipes manual
  console changes (roles renamed or granted by hand vanish → agent dispatch fails with
  a zero-duration `outcome=denied` row). Durable role grants belong in the realm import
  file (e.g. `dev/realm.json`: `roles.realm` entries + group `realmRoles`); after
  editing it, `docker compose up -d --force-recreate keycloak`.
