# TEST_PLAN — tenant-driven regression testing

Status: **implemented** (2026-09-29). Layer 1 = `TenantPolicySmokeTest` (policy fixture,
`mvn verify` 130 green). Layer 2 = Playwright pack in `test/e2e/` — 28 specs green against the
ephemeral `crosshubber-e2e` tenant stack, full lifecycle verified (boot → test → `down -v`, zero
containers left). CI remains deferred.

---

## 1. Current state (analysis)

| Layer | State | Gap |
|---|---|---|
| Backend | 19 test classes; 1 integration test (`PortalSmokeTest`, Testcontainers PG) with 4 assertions | No authenticated flows; test fixture has empty `settings{}` — none of the tenant-policy blocks exercised; migration floor stale (`>= 18` vs V28) |
| Frontend | 14 Vitest specs (jsdom); 1 component-integration test | Shell/Login/registry/AI-hub screens untested at DOM level |
| E2E | None (no Playwright/Cypress; `ng e2e` is dead scaffold) | Greenfield |
| CI | None | Deferred by decision |
| Compose | Parameterized via `.env` (`TENANT_SLUG`, `PGSCHEMA`, `REALM_NAME`, ports) but `secrets.env` (3×) and `realm.json` paths pinned to `dev/` | Needs 2-variable parameterization |
| Tenant capacity | `tenants-config/<slug>/{tenant.json, realm.json, secrets.env}` works end-to-end | Reuse verbatim for `e2e` |

**Key facts the plan builds on:**

- Post-normalization API surface: `/api/registry/module-contents`, `/api/navigation/groups`,
  `contentKey` naming (settled 2026-09-29 — normalization shipped as V27/V28 + API renames; selectors
  re-verify before specs freeze).
- Routes are only `login` / `w/:name` / `''` — screens are embedded modules reached via
  `?app=moduleKey:contentKey` deep links.
- `KC_DB: dev-file` means Keycloak data lives inside its container — destroyed on `down` (with or
  without `-v`).
- `OIDC_ISSUER` / `KEYCLOAK_REALM` already follow `REALM_NAME`; portal mounts the whole
  `tenants-config` tree read-only, so a new tenant needs only `TENANT_SLUG` + the two un-pinned
  paths (secrets/realm) + ports.

---

## 2. Layer 1 — API regression with a policy tenant fixture (runs in `mvn verify`)

**Purpose:** fast, always-on proof that the reconciler applies tenant policy — catches fork-feature
regressions without a browser.

**Files:**

- `portal/server/src/test/resources/tenant-config-policy/_default/tenant.json` — full policy blocks:
  `sample-embedded: false`; `defaultTheme: "ocean"` +
  `enabledThemes: ["ocean","light","nord"]`; `i18n.enabledLanguages: ["en-GB","pt-PT"]`,
  `defaultLanguage: "pt-PT"`; `branding{name:"Policy Tenant", title:"Policy Tenant Portal"}`;
  `aiHub.enabledProviders: ["anthropic"]`; `modules.external: []`.
- `portal/server/src/test/java/com/crosshubber/portal/TenantPolicySmokeTest.java` — own
  `@SpringBootTest(RANDOM_PORT)` + own `PostgreSQLContainer` (schema `policy`),
  `portal.tenant-config-dir=target/test-classes/tenant-config-policy`, `portal.tenant-slug=test`.

**Assertions:**

1. `GET /api/branding` → `{"name":"Policy Tenant","title":"Policy Tenant Portal"}` (public endpoint,
   no auth)
2. `GET /api/i18n/config` → exactly `en-GB` + `pt-PT` enabled, `defaultLanguage = "pt-PT"` (public)
3. `NavigationSettingsService.get()` → contains `defaultTheme`, `enabledThemes`, fixture `homeApp`
4. `ShellConfigService.buildConfig(mockUser)` → excludes all contents of `sample-embedded`;
   includes `ai-hub`
5. `ModulesService.setActive("sample-embedded", false)` → `ResponseStatusException` 409
6. `AiHubProviderRepository` → `anthropic.enabled == true`, all others `false`
7. Registry PATCH guard covered by existing `ModulesControllerValidationTest`

**Also:** fix `PortalSmokeTest.allFlywayMigrationsApplied` → assert latest applied version equals
**V28** (not `>= 18`). Existing minimal fixture untouched (documents the absent-block path).

**Cost:** +1 PG container, ~30 s per `mvn verify`.

---

## 3. Layer 2 — Playwright E2E against the temporary `e2e` tenant

### 3.1 The test tenant (`config-management/tenants-config/e2e/`)

```jsonc
// tenant.json — deterministic, policy-heavy; every E2E assertion traces to a line here
{
  "configVersion": 2, "slug": "e2e", "name": "E2E Tenant", "lifecycle": "persistent",
  "modules": {
    "builtin": { "sample-embedded": false },          // hidden-module assertions
    "external": []                                    // no huey/louie
  },
  "settings": {
    "homeApp": "portal-navigation:portal",
    "defaultTheme": "ocean",
    "enabledThemes": ["ocean", "light", "nord"]       // picker-subset assertions
  },
  "i18n": {
    "enabledLanguages": ["en-GB", "pt-PT"],
    "defaultLanguage": "pt-PT"                        // first-paint language assertion
  },
  "branding": { "name": "Crosshubber E2E", "title": "Crosshubber E2E Portal" },
  "aiHub": { "enabledProviders": ["anthropic"] }
}
```

- `realm.json` — realm `"e2e"`; users `e2e-admin` (group `portal-admins`, all 5 roles) and
  `e2e-user` (no group); clients `portal` (PKCE S256, redirect `http://localhost:28094/*`) +
  `portal-admin` (service account); password placeholders
  `${KC_USER_E2EADMIN_PASSWORD}` / `${KC_USER_E2EUSER_PASSWORD}`.
- `secrets.env` — **committed with dummy values** (`e2e-pg-password`, `e2e-portal-secret`, …).
  Stack-local throwaways only valid inside the ephemeral compose project; AGENTS.md gets a note
  distinguishing this from real-secrets hygiene (invariant #4 stays intact for dev/prod).

### 3.2 Compose parameterization (prereq — dev-neutral)

```yaml
# docker-compose.yml — the two dev-pinned paths become variables (defaults = today's behavior):
env_file:
  - ${TENANT_SECRETS_FILE:-./config-management/tenants-config/dev/secrets.env}   # ×3 services
volumes:
  - ${TENANT_REALM_FILE:-./config-management/tenants-config/dev/realm.json}:/opt/keycloak/data/import/realm.json:ro
```

`test/e2e/.env.e2e` (committed, non-secret structure): `TENANT_SLUG=e2e`, `PGSCHEMA=e2e`,
`REALM_NAME=e2e`, `TENANT_SECRETS_FILE`, `TENANT_REALM_FILE`, `POSTGRES_DB=crosshubber_e2e`, ports
`KC_HOST_PORT=28090`, `PORTAL_HOST_PORT=28094`, `PG_HOST_PORT=25442`, `NATS_HTTP_PORT=32262`.
The dev stack stays bootable and can run in parallel.

### 3.3 Stack orchestration — `test/e2e/scripts/stack.mjs`

- `up`: `docker compose -p crosshubber-e2e --env-file test/e2e/.env.e2e up -d --build` → poll portal
  `/healthz` then `GET /api/branding` (expect e2e payload — proves the *right* tenant booted) →
  timeout 8 min (first build).
- `down`: `docker compose -p crosshubber-e2e down -v` (PG volume + KC dev-file destroyed →
  **temporary by construction**).
- Playwright `webServer` runs `up`; `globalTeardown` runs `down` (skipped with
  `reuseExistingServer` locally for faster authoring).

### 3.4 Playwright project — `test/e2e/`

```
test/e2e/
  package.json            @playwright/test + typescript (isolated from Angular deps/budgets)
  playwright.config.ts    baseURL http://localhost:28094; projects: setup → admin, limited;
                          webServer = stack up; globalTeardown = stack down; trace on-retry
  .gitignore              test-results/, playwright-report/, storageState-*.json
  scripts/stack.mjs
  specs/setup/auth.setup.ts     real KC form login ×2 users → storageState-admin/-limited.json
  specs/...                     (pack below)
  support/helpers.ts            apiRequest helper (request context), common selectors
```

**Auth model:** one setup run performs both OIDC logins through the Keycloak login page
(admin + limited); all other specs reuse `storageState`. No password handling in specs.

**Selector convention:** role/text/label selectors first; `data-testid` added to shell landmarks
only where text is dynamic — each such addition is listed in the spec PR.

### 3.5 Spec inventory — Extended pack (17)

| # | Spec | Key assertions |
|---|---|---|
| 1 | `auth/login.spec.ts` | unauthenticated `/` → KC redirect; `e2e-admin` login lands on shell; logout returns to `/login` |
| 2 | `auth/limited-user.spec.ts` | `e2e-user`: settings gear disabled/absent, no registry-edit affordances, read-only surfaces render |
| 3 | `tenant/branding.spec.ts` | login page shows "Crosshubber E2E"; `document.title` = "Crosshubber E2E Portal"; sidebar wordmark |
| 4 | `tenant/theme-policy.spec.ts` | `data-theme="ocean"` after boot; picker lists exactly ocean/light/nord; `dark-slate` absent |
| 5 | `tenant/i18n-policy.spec.ts` | first paint `pt-PT`; switcher shows exactly 2; switch to en-GB persists after reload |
| 6 | `tenant/module-visibility.spec.ts` | `sample-embedded` absent from app grid, add-app picker, deep-link fallback; no huey/louie; ai-hub present |
| 7 | `tenant/providers.spec.ts` | AI-hub providers list: anthropic enabled, remaining 6 disabled |
| 8 | `shell/home-app.spec.ts` | cold load opens Home tab with `portal-navigation:portal` content |
| 9 | `shell/workspace-persistence.spec.ts` | open app → pin → create workspace → reload: tab restored, pin present |
| 10 | `shell/registry-guard.spec.ts` | builtin cards show "Managed by tenant config" hint, no switch; API `PATCH /api/registry/modules/sample-embedded/active` → 409 via request context |
| 11 | `admin/i18n-labels.spec.ts` | edit a label value in i18n admin → save → reload → UI shows new string |
| 12 | `admin/navigation-editor.spec.ts` | portal-nav editor: toggle `pinnedAppsEnabled`, save, sidebar reflects (session-scoped; boot re-asserts config — documented) |
| 13 | `admin/registry-content.spec.ts` | module registry: module-contents tree renders for a builtin; toggle a content item; tree + shell refresh reflect it |
| 14 | `admin/aihub-settings.spec.ts` | enable `openai` provider (runtime), token form validation error shown on bad submit |

Plus 3 split-out negative specs from #6/#4/#5 as needed to keep each file single-purpose
(target ≈ 17 total).

---

## 4. Execution order

| Step | Work | Est. |
|---|---|---|
| 1 | Layer 1: policy fixture + `TenantPolicySmokeTest` + migration-floor fix | 0.5 d |
| 2 | Compose parameterization + `e2e` tenant (tenant.json/realm.json/secrets.env) + AGENTS secrets note | 0.75 d |
| 3 | Playwright scaffold: package, config, stack.mjs, KC auth setup, storageStates | 0.75 d |
| 4 | Tenant-policy + shell-core specs (#3–10) | 1 d |
| 5 | Admin-flow specs (#11–14) | 1–1.5 d |
| 6 | Docs: this file's status, AGENTS.md testing section, README command | 0.25 d |

---

## 5. Verification / acceptance

- `mvn verify` green including new `TenantPolicySmokeTest` (Docker required)
- `docker compose -p crosshubber-e2e --env-file test/e2e/.env.e2e up -d --build` boots;
  `/api/branding` returns the e2e payload
- `cd test/e2e && npx playwright test` → all specs green against the ephemeral stack; after run,
  `docker compose -p crosshubber-e2e down -v` leaves no state
- Dev workflow unchanged: `docker compose up` + `npm test` + `npm run build` unaffected

---

## 6. Deferred / backlog

CI pipeline (GitHub Actions: verify → test → build → e2e) · visual-regression screenshots ·
trace/report artifact retention · parallel tenant matrix · API-only regression for external-module
manifests (needs a stub manifest server).
