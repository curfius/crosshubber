# Tenant fork plan — per-tenant deployable portal

Status: **implemented** (2026-09-29; `mvn verify` 77 green incl. smoke test, `npm test` 128 green,
`npm run build` green). Working reference for the "easily forkable tenants" work stream — §6 is the
new-tenant runbook; §8 holds the deferred follow-ups.

---

## 1. Core semantics — the ownership rule

One rule delivers both wanted behaviors (GitOps + admin runtime):

> **Present = config-owned.** Any key/block present in the *merged* tenant config
> (`_default/tenant.json` ⊕ `<slug>/tenant.json`; deep-merge = objects merge per-key, arrays/scalars
> replace) is desired state, re-asserted by the Reconciler at every boot. Runtime admin edits to it
> revert on restart.
> **Absent = admin/runtime-owned.** Seeded once if missing; admin edits survive restarts.
> **Overlay `null` = release to admin.** The loader drops null values so `{ "homeApp": null }` in an
> overlay hands `homeApp` back to admin control (the baseline `_default` value still seeds it if the
> DB has no value yet).

Consequences per dimension:

| Dimension | Present in config → | Absent → |
|---|---|---|
| `modules.builtin` (per-key map) | per-key asserts `active` (omitted key = `true`) | all builtins active (today's behavior) |
| `modules.external[]` | list + per-entry `active` asserted (already works) | runtime-installed modules keep admin toggle |
| `settings.defaultTheme` / `enabledThemes` | asserted at boot | all themes allowed; code default `dark-slate` |
| `i18n{}` block | asserts enabled set + default/fallback language | today's behavior (admin toggles survive boot) |
| `branding{}` block | asserted; served via `GET /api/branding` | hardcoded defaults ("Crosshubber" / "Crosshubber Portal") |
| `aiHub.enabledProviders[]` | listed provider ids asserted `enabled=true` | providers stay admin-owned (all seeded `enabled=false`) |
| other `settings{}` keys | asserted (already today) | admin-owned |

Stays hardcoded (code-owned, deliberately NOT tenant-configurable):

- `EmbeddedCatalog` structure: module/entry keys, load paths, categories, colors, sort defaults,
  entry-point roles (contract with Angular routes + load-path guard).
- `SEED_PROVIDERS` catalog (ids / display names / base URLs) — ecosystem facts, seeded `enabled=false`.
- `i18n-catalog.json` label keys + seed values (insert-if-absent; admin edits never clobbered).
- Retired-module/entry-point cleanup lists (transitional).
- `@PreAuthorize` role names (compile-time security contract; realm role *creation* moves to the
  reconciler).
- Frontend `PORTAL_THEMES` (catalog) + `DEFAULT_THEME` (first-paint fallback before config arrives).

---

## 2. Target `tenant.json` schema (v2)

```jsonc
// config-management/tenants-config/_default/tenant.json — baseline every tenant inherits
{
  "configVersion": 2,
  "slug": "_default",
  "name": "Portal Baseline",
  "baseline": true,
  "lifecycle": "persistent",
  "services": { /* untouched — no portal readers today */ },
  "modules": {
    // DEAD ARRAY → per-key override map. Structure/roles stay code-owned in EmbeddedCatalog.
    "builtin": {
      "portal-dashboard": true, "module-registry": true, "settings": true,
      "user-settings": true, "navigation": true, "ai-hub": true,
      "i18n-settings": true, "sample-embedded": true
    },
    "external": [ /* unchanged */ ]
    // "overrides": removed (dead key, zero readers)
  },
  "settings": {
    "homeApp": "portal-navigation:portal",
    "pinnedAppsEnabled": true,
    "workspacesEnabled": true,
    "defaultTheme": "dark-slate",
    "enabledThemes": null            // null = all 17 selectable themes
  },
  "i18n": {
    "enabledLanguages": ["en-GB", "pt-PT", "fr-FR", "es-ES"],
    "defaultLanguage": "en-GB",
    "fallbackLanguage": "en-GB"
  },
  "branding": {
    "name": "Crosshubber",           // sidebar wordmark, login fallback
    "title": "Crosshubber Portal",   // document.title
    "logoUrl": null                  // null = keep current inline SVG mark
  },
  "aiHub": { "enabledProviders": [] }
}

// config-management/tenants-config/dev/tenant.json — overlay demonstrates the delta pattern:
//   "modules": { "builtin": { "sample-embedded": false } },
//   "branding": { "name": "Crosshubber Dev", "title": "Crosshubber Dev Portal" }
```

Deep-merge note: `builtin` is an **object** → tenant overlays merge per-key (disable one builtin by
listing only it). `enabledLanguages` / `enabledThemes` are **arrays** → replace (tenant lists the full
set). `null` in an overlay releases the key to admin ownership.

---

## 3. Backend changes

### 3.1 `bootstrap/TenantConfigLoader`

New parsing next to `parseExternalModules` (`TenantConfigLoader.java:116-147`):

- `modules.builtin` object → `Map<String, Boolean> builtinActive`; non-boolean values → fail-fast.
- `i18n{}` → record `I18nPolicy(List<String> enabledLanguages, String defaultLanguage, String
  fallbackLanguage)` — whole block nullable; each field independent.
- `aiHub{}` → `List<String> enabledProviders` (nullable).
- `branding{}` → record `Branding(String name, String title, String logoUrl)` (nullable).
- `settings` → **drop null values** (release rule) after convertValue.
- Replace the dead-array reader of `modules.builtin` — there is none today; the array shape is simply
  no longer accepted (it was never consumed).

Extend `EffectiveTenantConfig` (`TenantConfigLoader.java:44-51`) with the four new records. Absent
blocks → `null` (never defaults) so the Reconciler can distinguish "not owned" from "owned value".

Schema fail-fast (here): non-string builtin keys, non-boolean builtin values, `enabledLanguages` must
be an array of KEY_RE-valid codes and non-empty when present, `branding.name`/`title` must be strings
when present.

### 3.2 `bootstrap/Reconciler` (TX phase, `Reconciler.java:117-125`)

| Step | Change |
|---|---|
| Cross-ref validation (new, inside TX, before writes) | `builtin` map keys ∉ `EmbeddedCatalog.CATALOG` → abort boot (typo guard). `enabledProviders` id ∉ `SEED_PROVIDERS` → abort. `i18n.defaultLanguage` not in `enabledLanguages` (when both present) or unknown code → abort. `defaultTheme ∉ enabledThemes` (when both present) → abort |
| `reconcileBuiltins` (`:191`) | `entity.setActive(builtinActive.getOrDefault(mod.key(), true))` replaces unconditional `setActive(true)` (`:207`). Entry points stay `active=true` — hiding works at module level via `ShellConfigService.visibleModuleKeys` (`ShellConfigService.java:99`) |
| `reconcileProviders` (`:311`) | after add-only seed: for each id in `enabledProviders` → `setEnabled(true)`; unlisted rows untouched (admin can freely enable others) |
| `reconcileI18n` (`:362`) | after insert-if-absent seed: only if `I18nPolicy` present — assert `enabled` for **seeded** rows only (`listed → true`, unlisted seeded → `false`; admin-added `seeded=false` languages untouched); assert `defaultLanguage`/`fallbackLanguage`. No `content_version` bump (no label change). Extract list-computation into a pure static helper for unit tests |
| `reconcileInstanceSettings` (`:328`) | unchanged besides loader null-filtering upstream |
| `recordTenantMeta` (`:438`) | add writes of `branding.name/title/logoUrl` (first real `tenant_meta` consumer) |
| post-commit (`:126`) | `syncBuiltinRealmRoles`: best-effort `KcAdminClient.ensureRealmRoles` (`KcAdminClient.java:91`) for hardcoded `BUILTIN_REALM_ROLES` = 5 `portal-*-edit` + `portal-admin` (the `@PreAuthorize` set) ∪ `EmbeddedCatalog.securityRoles`; guarded by `isConfigured()`; log-only failures, never abort |

### 3.3 API guard + branding endpoint

- `ModulesService.setActive` (`ModulesService.java:106`): reject builtin keys → 400
  `{"error":"..."}` envelope ("builtin availability is tenant-config-owned"). Registry toggle for
  externals stays; their config-listed `active` still re-asserts at boot (documented).
- New `GET /api/branding` controller in `shell/`: `permitAll` (login renders pre-auth); reads
  `tenant_meta` keys `branding.name/title/logoUrl` → `{name, title, logoUrl}`; falls back to current
  hardcoded strings when the block is absent. Add `/api/branding` to `SecurityConfig.java:73-74`
  permitAll group.
- `GET /api/settings` needs no controller change — `InstanceSettingsService.get()` already returns
  the whole merged jsonb map, so `defaultTheme`/`enabledThemes` ride for free.

### 3.4 No migrations needed

`modules.active`, `entry_points.active`, `i18n_languages.enabled`, `ai_hub_providers.enabled`,
`instance_settings.settings` (jsonb), `tenant_meta` all exist.

---

## 4. Frontend changes

| Component | Change |
|---|---|
| `settings.service.ts` | type + read `defaultTheme?` / `enabledThemes?: string[] \| null` from `/api/settings` (today only `homeApp` is read, line 30) |
| `theme.service.ts` | add signals `available = signal(PORTAL_THEMES)` + `defaultTheme = signal(DEFAULT_THEME)`; `setPolicy({defaultTheme, enabledThemes})` called from `shell.component.ts:100-108` after `settings.load()`, before `initFromPreferences`; switch membership checks (lines 54/66/80) to `available()`; invalid stored/DB theme → tenant default → first available → `DEFAULT_THEME`; re-validate current theme when policy arrives |
| theme picker | `user-settings-general.component.ts:21` binds `theme.available` instead of the constant |
| `BrandingService` (new, `core/branding/`) | fetch `GET /api/branding` once (public); set `document.title` from `title`, sidebar wordmark from `name` (`sidebar.component.html:27`), optional `logoUrl` `<img>` else keep inline SVG; login heading falls back to branding name. `index.html:5` title stays as pre-JS default |
| registry UI | `module-registry.component.html:57` — hide active switch for `mod.builtin`; show "managed by tenant config" hint (i18n key `registry.builtinManaged`) |
| languages / module hiding | zero UI work — `enabled` flag already drives everything (`i18n.service.ts:58-63`); server-filtered `/api/config` hides inactive modules. Add specs for the 3 residual happy paths: pinned ref → missing EP, hidden `homeApp` → fallback chain (`workspaces.store.ts:139-147`), workspace snapshot → missing EP skipped |

---

## 5. Tests

- Backend (Docker required for `PortalSmokeTest`):
  - new `TenantConfigLoaderTest`: block parsing, null-release, absent block → null, fail-fast shapes
  - pure i18n-policy helper unit tests
  - `ModulesControllerValidationTest` extension: builtin PATCH active → 400 envelope
  - `PortalSmokeTest` **must stay green with the fixture that omits all new blocks**
    (`src/test/resources/tenant-config/_default/tenant.json` — proves absent-block = current behavior);
    optionally a second fixture exercising the blocks
- Frontend (Vitest):
  - `theme.service.spec.ts`: policy subset applied; invalid DB theme → tenant default; invalid
    `defaultTheme` → first available; picker binding
  - branding service spec; registry builtin-switch spec update
- i18n catalog: add `registry.builtinManaged` (+ label) for all 4 languages to
  `i18n-catalog.json`; reconciler seeds new keys and bumps `content_version` automatically (no migration).

---

## 6. New-tenant runbook (result after implementation)

1. `config-management/tenants-config/<slug>/tenant.json` — overlay with only deltas.
2. `<slug>/realm.json` + `<slug>/secrets.env` (from `secrets.env.example`) — realm roles auto-created
   by boot sync; realm.json still owns clients, `portal-admins` group, users.
3. Deploy with `TENANT_SLUG=<slug>`, `PGSCHEMA=<slug>`, `OIDC_ISSUER=.../realms/<slug>`.
4. Boot: Flyway creates the schema → Reconciler applies exactly the declared content.

Fork = copy the `_default` baseline, delete what you don't want, restart — no admin-UI clicking.

---

## 7. Deliberate behavior changes (sign-off)

1. Builtin registry toggle stops working (hidden + API 400) — no more silent boot-stomp.
2. When `i18n{}` is present (it is, via `_default`), admin disabling a seeded language or changing
   default language reverts on reboot. Admin-added custom languages unaffected.
3. Invalid tenant config (unknown builtin key, unknown provider id, default language not in the
   enabled set) **aborts boot** — fail-fast, consistent with manifest validation.

## 8. Deferred / backlog

External-module UI toggle silent-revert hint · carris-light/dark selectable (17 vs 19 theme drift) ·
i18n admin hint "config-managed languages" · `EntryPointsService.java:155` active-defaults-true
footgun · per-tenant entry-point-level overrides · removal of the retired-builtin cleanup lists
(tracked in AGENTS.md backlog; keep one more release cycle).

**Resolved in the 2026-09-29 bootstrap cleanup:** Reconciler's hardcoded first-boot instance-settings
block removed (`_default/tenant.json settings{}` is the single config source;
`NavigationSettingsService.DEFAULT_SETTINGS` remains the read-time fallback) · dead `services{}`
block removed from both tenant.json files · `configVersion` now enforced fail-fast (declared version
≠ 2 aborts boot; absent tolerated with a warning).

---

## Execution order

1. Loader (3.1) + config files (§2) + loader tests
2. Reconciler (3.2) + API guard + branding endpoint (3.3)
3. Frontend: settings/theme policy → branding → registry UI
4. i18n catalog key + tests (§5) → `mvn spotless:apply` → `mvn verify` → `npm test` → `npm run build`
5. Docs: AGENTS.md (fix "8 languages" → 4; add ownership rule), README design notes
   (`/api/branding`, builtin-active 400, i18n/theme ownership)
