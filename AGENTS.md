# AGENTS.md — Crosshubber Portal

Instructions for AI coding agents working in this repository. Read this before making
changes; it encodes invariants, build commands, and known gotchas that are not obvious
from the code alone.

## Stack (verify versions in `portal/server/pom.xml` — do not trust older docs)

| Layer | Technology |
|---|---|
| Backend | **Spring Boot 4.1.1**, Java 21, Maven — Boot 4 artifact names (`spring-boot-starter-webmvc`, `spring-boot-starter-restclient`, `spring-boot-starter-test-classic`) and **Jackson 3** (`tools.jackson.*` packages, NOT `com.fasterxml.jackson`) |
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
      modules/<feature>/      Feature packages (aihub, i18n, navigation, registry,
                              settings, usersettings) — own entity/repo/service/controller
      auth/ security/ config/ common/ bootstrap/ shell/ workspaces/ proxy/
    src/main/resources/
      application.yml         portal.* config; env-driven, dev defaults insecure-by-design
      db/migration/           Flyway V1..V24 (DDL only here)
      i18n-catalog.json       GENERATED seed catalog (~227 KB, 8 languages) — don't hand-edit
  ui/                         Angular 22 workspace shell
    src/app/core/             Platform services (bridge, i18n, theme, auth, config...)
    src/app/portal-core/      Shell layout, workarea, 18 lazy module UIs, feature stores
    src/design-system/        CSS-only design system (tokens + components + 18 themes)
config-management/tenants-config/   Tenant config overlays (consumed at boot)
scripts/                          Standalone tooling (e.g. sync-design-system.mjs)
docs/archive/                       Archived implementation plans
.opencode/skills/                   Project-local opencode skills (source of truth)
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
   real secrets come from env/compose, never from code.
5. **No TODO/FIXME comments** in either codebase (existing policy; Checkstyle and review
   enforce it). Prefer fixing or documenting in the backlog file.
6. **Formatting before done**: `mvn spotless:apply` then `mvn verify` for Java; Prettier
   (`.prettierrc`) for TS. Java comments/Javadoc follow Google Java Style (see skill).

## Backend conventions

- Feature-based packaging: `modules.<feature>` owns everything for that feature. Cross-
  cutting code lives in `config/`, `security/`, `common/`. Features never import each
  other's internals — go through services.
- Controllers stay thin: routing + status codes. Business logic in services.
- HTTP calls (Keycloak admin, LLM providers, module manifest fetches) must stay **out of
  database transactions** — blocking I/O inside `@Transactional` holds a Hikari
  connection (pool size 5). Pattern: orchestrate outside TX, persist inside TX.
- Jackson 3: use `tools.jackson.databind.*`. `JsonUtils` (common) centralizes
  parse/write helpers — don't hand-roll new ones.
- HTTP client: shared `RestClient` customization lives in `config/HttpClientConfig`
  (10s connect / 30s read). Don't create ad-hoc `HttpClient`s.
- Validation regexes and scalar-coercion helpers (`KEY_RE`, `LANG_CODE_RE`, `string()`,
  `orEmpty`...) were duplicated across services — extract to `common` when touching
  those files (see backlog in `OPTIMIZATIONS.md`).

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

- Server: 1 integration test (`PortalSmokeTest`, Testcontainers — needs Docker) + unit
  tests per service. When editing a service, add/extend its test class. No H2 — DB
  tests are Testcontainers only.
- UI: specs colocated `*.spec.ts`. Feature stores (signals) and pure helpers (diff
  engine, tree builders) are the highest-value test targets — they hold the most logic.

## Gotchas / historical landmines

- **Mojibake**: files in this repo have been CP1252-double-encoded by tooling twice
  (i18n-catalog.json, several Java/TS files, fixed 2026-09-21). Symptom:
  `â€"` instead of `—`, `Â§` instead of `§`. Repair by decoding UTF-8 → re-encoding
  CP1252 → decoding UTF-8 (only if all chars > U+00FF are CP1252 specials). Never save
  files through PS 5.1 `Set-Content`/`Out-File` (they emit BOMs and mojibake on write).
- **The reconciler is fail-fast and runs at every boot** — it re-seeds i18n labels,
  providers, settings idempotently. Keep its upserts idempotent and cheap.
- **Design notes (intentional quirks)**: `entry_points.roles` is stored comma-joined
  (V11) because keys are kebab-case validated; `GET /api/mfe/<key>` without trailing
  path returns 400 JSON by design; proxy upstream failures return 502 (not 500).
- **Tenant config deep-merge**: arrays in the tenant overlay REPLACE the `_default`
  arrays (don't concatenate) — `dev/tenant.json` relies on this to blank demo modules.
- **`i18n-catalog.json`** is the repo-owned seed catalog (originally exported from the
  pre-decommission Node stack; the regen script is gone). Edits are allowed; the
  reconciler seeds insert-if-absent, so changed values need a migration (see `V24__*`)
  or an update pass if existing installs must pick them up.
- Windows dev environment: PowerShell 5.1. Avoid `&&` (unsupported) — use
  `cmd1; if ($?) { cmd2 }`. Native exes with spaces in paths need the call operator.

## Backlog pointers

- `plan/AI_PLAN.md` — roadmap for the portal agent (session context pack, manifest
  `agentContributions` v2, MCP tool execution, A2A-shaped sub-agents, module-owned
  knowledge retrieval).
- `plan/UX_PLAN.md` — portal navigation/UX optimization roadmap (journey-based
  Phases 1–3; Phase 1 quick wins implemented 2026-09-22).
- `portal/server/OPTIMIZATIONS.md` — the running backend optimization plan (items #1
  typed DTOs, #9 @Valid, #10 shared constants, #11 enums, #12 optimistic locking,
  #16 @Cacheable, #26 projections were open as of 2026-09; #27 Reconciler TX was fixed
  in Step 9 — see the Step 9 section).
- UI backlog (tracked here until a UI plan file exists): enable `"strict"` +
  `strictTemplates` in `portal/ui/tsconfig.json`; lazy-load non-default themes (18
  theme files ship in global CSS today); merge `chat`/`quick-chat` duplicated logic;
  replace remaining RxJS `Subject`s with signals; purge unused `.ds-*` CSS (~40% of
  `components.css`); self-host CDN fonts (`design-tokens.css`).
- Done as of Step 9 (2026-09-21): all components `OnPush`; shared `apiFetch`
  (`src/app/core/http/api-fetch.ts`) + `ScopedSettingsClient`; quick-chat is a
  `@defer` chunk; manifest diff engine extracted (`module-registry/manifest-diff.ts`,
  tested); workspaces store has res.ok discipline + tests.
  **Do NOT add a TranslatePipe** — `i18n.t()` reads the `labels` computed signal inside
  template calls, so it is signal-tracked; a pure pipe would break label-reload
  reactivity and an impure pipe saves nothing.
- Deferred pre-decommission behavioral cleanups (2026-09-22 decoupling kept comments-only):
  empty-patch no-op in `I18nService` ("return the language without saving"), ICU/
  `localeCompare` tiebreak emulation in `NavigationValidationService` — revisit with
  tests when touching those files.
- `docs/archive/GAP_CLOSURE_IMPLEMENTATION_PLAN.md` — the original port's working plan
  (historical; the reference implementation it was written against has been
  decommissioned).
