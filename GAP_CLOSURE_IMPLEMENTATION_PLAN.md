# GAP Closure Implementation Plan — Node.js Portal → Java Portal Contract Parity

**Repo (Java):** `C:\playground\projects\crosshubber` (this repo)
**Reference (Node):** `C:\playground\projects\genportal\portal`

**Goal / acceptance criterion:** with identical data in both databases, the same endpoint calls return the same results (same JSON keys, casing, nesting, value formats, null/empty handling, envelopes, status codes, error message strings, list ordering). Node code is the **contract reference only**. The Java implementation must stay idiomatic Spring — no Node patterns imported. Existing Java improvements are kept and documented as accepted divergences.

---

## 1. Ground rules & recorded decisions

| Decision | Choice | Date |
|---|---|---|
| Login flow | **Standard OIDC redirect** (Spring Security `oauth2-client`, authorization-code + PKCE). Not a port of Node's form-scrape flow, not ROPC. | 2026-09-02 |
| Unit test porting | **No** Java unit-test suite ported. Verification = build + manual smoke + contract-diff harness. | 2026-09-02 |
| Minor divergences | **Keep Java improvements**: proxy upstream failure 502, scalar-body 400, tenant digest `hashCode`, models-listing first-*enabled* token, install error mapping. | 2026-09-02 |
| Contract-diff harness | **Yes** — one-off runtime verification script (Phase 9). | 2026-09-02 |

**Implementation rules:**
- Every change must keep the public contract: error envelope is always `{"error": "<message>"}`; 401 `{"error":"unauthorized"}`; 403 `{"error":"forbidden"}`.
- Do **not** enable `spring.jackson.default-property-inclusion: non_null` globally — per-DTO builders own null/empty inclusion (Node also mixes behaviors per endpoint).
- Run `mvn spotless:apply` (Google Java Style) + Checkstyle before considering any task done. `mvn verify` must pass.
- Where a spec below says **VERIFY vs Node**, read the actual Node code before implementing — do not trust the note blindly.

---

## 2. Status legend & tracking conventions

Task states:

| Symbol | Meaning |
|---|---|
| ⬜ | Todo (not started) |
| 🔶 | In progress (name the session in Session Log when setting) |
| ✅ | Done (add date + one-line verification evidence) |
| ⏸ | Blocked (record blocker in Session Log) |
| ❌ | Won't do (record reason) |

**Task IDs:** `P<phase>.<number>` (e.g. `P2.1` = Phase 2 task 1). Use these IDs everywhere: commits, session log, dashboard.

**Pickup protocol (read this at the start of every session):**
1. Read §4 Dashboard → find first phase with unfinished tasks (phase order 1→9; Phases 7 & 8 are independent and can be done anytime after Phase 1).
2. Read §3 Environment + the phase's task specs.
3. Mark the task 🔶 in the dashboard and add a Session Log entry.
4. Implement per spec; **VERIFY vs Node** items: read the Node file cited, confirm behavior, note deviations in Session Log.
5. Verify (task's "Done when" + `mvn verify` for server changes / `ng build` for UI changes).
6. Mark ✅ with date; update dashboard; append Session Log entry.
7. Commit per phase (or per task if large) — do not commit unless asked by the user in that session.

---

## 3. Environment & command reference

| Thing | Value |
|---|---|
| Java server | `portal/server` — Spring Boot 3.4.5, Java 21, Maven. Build: `mvn verify` in `portal/server` (runs Spotless + Checkstyle). |
| Angular UI | `portal/ui` — Angular 22. Build: `ng build` in `portal/ui`. |
| Java stack ports (docker-compose) | portal `28084`, Keycloak `28080`, PostgreSQL host port per `.env` (compose default `15432`, `.env` says `25432` — check `.env`). |
| Node stack | `C:\playground\projects\genportal\portal` — Node 22 + Express, default port `3000`. Run with its own compose/env; its own DB schema (per-tenant schema = `TENANT_SLUG`). |
| Java per-tenant schema | `PGSCHEMA` (falls back to `TENANT_SLUG`). Flyway migrations `portal/server/src/main/resources/db/migration/V1..V11`. |
| Roles | `portal-registry-edit`, `portal-navigation-edit`, `portal-settings-edit`, `portal-i18n-edit`, `portal-ai-hub-edit`, `portal-admin` (fast path for module-settings). |
| Zod (Node validation lib) | v4.4.3 — error strings are zod-v4 English locale, joined as `"path: message; ..."` by `zodIssues()` (`navigation.routes.ts:116-118`). |
| Jackson trap | `JacksonConfig.java` registers a bare `new ObjectMapper()` → Spring Boot backs off → `spring.jackson.*` in `application.yml` is **dead config**. Nulls ARE serialized. See P1.4. |
| Session cookie | `portalSession` = `base64url(JSON).baseurl(HMAC-SHA256)`; `HttpOnly; SameSite=Lax; Max-Age=sessionHours*3600[; Secure]`. Byte-compatible between both stacks (`session.ts` ↔ `PortalSessionFilter`). Must survive Phase 7 redesign unchanged. |
| Login users (dev) | `dev/dev` (admin, all portal-* roles), `devuser/dev` — Keycloak realm `dev` imported from `config-management/tenants-config/dev/realm.json`. |

---

## 4. Progress dashboard

| Phase | Scope | Tasks | Done | Status |
|---|---|---|---|---|
| 1 | Serialization & error infrastructure | 4 | 4 / 4 | ✅ |
| 2 | Registry (modules + manifest lifecycle) | 8 | 8 / 8 | ✅ |
| 3 | Entry-point outputs + shell config | 2 | 2 / 2 | ✅ |
| 4 | Navigation | 7 | 7 / 7 | ✅ |
| 5 | AI hub | 11 | 11 / 11 | ✅ |
| 6 | Workspaces, instance settings, i18n | 6 | 6 / 6 | ✅ |
| 7 | OIDC redirect login (server + UI + realm) | 6 | 6 / 6 | ✅ |
| 8 | Bootstrap: external module reconcile | 2 | 2 / 2 | ✅ |
| 9 | Verification (build + contract-diff harness) | 4 | 3 / 4 | ⬜ (only P9.3 manual smoke left) |

**P0 (functional breaks) quick list:** P3.1 sandbox array · P2.1 module upsert · P5.1 conversation updated_at · P5.3 empty chat stream 502 · P5.7 send-on-disabled-channel · P6.1/6.2 workspace savedAt + rename · P1.2 403 handler · P4.1 shell-tree authz · P7.* login · P8.* external reconcile.

### Retracted / non-issues (do NOT change these)

| Earlier suspicion | Verdict (evidence) |
|---|---|
| "Node GET /api/registry/modules returns active-only" | **Wrong.** Both return ALL modules incl. inactive (Node `modules.service.ts:32-35` + repository `findAll(activeOnly=false)`; UI needs inactive for activation toggle). Java matches. No change. |
| "Install validation 500 vs Node 422" | **Non-issue.** Both sides: 422 `{error:"invalid manifest",issues:[...]}` for schema-parse failures; 500 `{error:"install failed: ..."}` for domain validation; issue strings identical for domain cases. |
| "i18n label catalog size differs" | **Non-issue.** ~565 keys × 4 languages on both sides. |
| "AI models keyless fallback differs" | **Non-issue.** Node also falls back to keyless call when no token exists (`providers.routes.ts:91-101`). |

---

## 5. Phase task specs

### Phase 1 — Serialization & error infrastructure (foundation — do first)

**P1.1 ✅ Central Node-compatible date formatting** (2026-09-02 — created `NodeDates.java` with `DateTimeFormatter.appendInstant(3)`; applied to all Instant DTOs: conversations, channels, versions, telegram polling, channel pipeline. `mvn verify` passes.)
- Priority: P1 (blocks byte-parity for all date fields).
- Node behavior: pg `timestamptz` → JS `Date` → `JSON.stringify` → **always exactly 3 fraction digits UTC**: `2026-09-02T10:15:30.000Z`.
- Java problem: `Instant.toString()` emits 0/3/6 fraction digits (`2026-09-02T10:00:00Z`, `…T10:00:00.123456Z`).
- Change: add util (e.g. `common/NodeDates.java`) with `DateTimeFormatter` built via `DateTimeFormatterBuilder.appendInstant(3)` (fixed 3-digit millis, UTC). Apply to every hand-built DTO date:
  - `AiHubConversationsService` (`created_at`, `updated_at`, message `created_at`)
  - `AiHubChannelsService` (`createdAt`, `updatedAt`)
  - `InstallService.java:185` (`installedAt`)
  - any other `Instant.toString()` reaching responses (grep `toString()` on Instant in `modules/`, `shell/`, `workspaces/`).
- Note: workspaces `savedAt` is already epoch-ms on both sides — do not touch.
- Done when: grep finds no raw `Instant.toString()` in DTO builders; spot-check conversations + channels + versions JSON shows `.000Z`-style values.

**P1.2 ✅ GlobalExceptionHandler: 403 for authorization failures** (P0) (2026-09-02 — added `@ExceptionHandler(AccessDeniedException.class)` → 403 `{"error":"forbidden"}` in `GlobalExceptionHandler.java`. `mvn verify` passes.)
- Problem: routes guarded by `@PreAuthorize` return **500 `{"error":"internal server error"}`** when the role is missing (no `AccessDeniedException` handler; falls into generic `Exception` handler at `GlobalExceptionHandler.java:50-55`).
- Node: `requireAdmin` → `403 {"error":"forbidden"}` (`middleware/auth.ts:133`).
- Change: add `@ExceptionHandler(AccessDeniedException.class)` → 403 `{"error":"forbidden"}`. In Spring Security 6.3+ `AuthorizationDeniedException extends AccessDeniedException`, so one handler covers both. Ensure it takes precedence over the generic handler.
- Affected routes (audit list): `PUT /api/settings`, `PUT /api/i18n/settings`, `PUT /api/i18n/languages/:code`, `PUT /api/i18n/labels/:lang`, and all registry/manifest `@PreAuthorize` writes.
- Done when: request without role → 403 body `{"error":"forbidden"}` (test with a `devuser` session via curl).

**P1.3 ✅ Stable JSON key order for client-visible maps** (2026-09-02 — replaced `Map.of` with `LinkedHashMap` in `InstanceSettingsService` (DEFAULT_SETTINGS and parseJson), `HealthController` (healthz response). `ManifestController` draft responses already use `LinkedHashMap`. `mvn verify` passes.)
- Problem: `Map.of(...)` has unspecified iteration order → byte-level instability (`/api/settings` via `InstanceSettingsService.java:25-29,50-51`, `/healthz`, draft responses).
- Change: replace `Map.of` with `LinkedHashMap` (insertion order matching Node) in response builders where order was observed to matter: `InstanceSettingsService` (order: `homeApp`, `pinnedAppsEnabled`, `workspacesEnabled` then merged stored keys — VERIFY vs Node `settings.repository.ts:8-12,25`), `ShellHealthController`/healthz (`ok`, `app`, `db`), `ManifestController` draft responses (see P2.5). Low risk to convert broadly in response builders.
- Done when: repeated calls return byte-identical key order; `/api/settings` matches Node order for same data.

**P1.4 ✅ Jackson config hygiene (no behavior change)** (2026-09-02 — removed dead `spring.jackson.*` block from application.yml, added comment pointing to `JacksonConfig`. Added explanatory comment in `JacksonConfig.java` why custom bare mapper is intentional. `mvn verify` passes.)
- `application.yml:44-46` `spring.jackson.*` block is dead (P1.4 note in §3). Remove it or replace with a comment pointing to `JacksonConfig`, **without** changing effective serialization (nulls must stay included; dates already hand-formatted per P1.1).
- Add a short comment in `JacksonConfig` explaining why a custom bare mapper is intentional (per-DTO null/empty control mirrors Node).
- Done when: `mvn verify` passes; no effective serialization change (spot-check `GET /api/module-settings/x` for absent row still emits `{}`).

---

### Phase 2 — Registry: modules CRUD + manifest lifecycle

**P2.1 ✅ Module upsert semantics (COALESCE + builtin sticky)** — **P0** (2026-09-02 — modified `ModulesService.upsert` to preserve existing fields unless input provides; builtin sticky; managedBy manual on insert only. `mvn verify` passes.)
- Node reference: `modules.repository.ts:30-66` (upsert SQL COALESCE; `builtin = modules.builtin OR EXCLUDED.builtin`).
- Java problem: `ModulesService.upsert` (`ModulesService.java:74-102`) forces `roles=""`, `active=true`, `builtin=false`, `managedBy="manual"` on **every** upsert and **ignores `baseUrl`/`health`** from input.
- Consequences today: editing a disabled module silently re-enables it; POSTing to a builtin module clears `builtin` (removes 409 delete-protection); UI activation-proposal flow broken (`module-registry.component.ts:444-458` creates modules with `active:false` and expects them to stay disabled; `:342` checks `!mod.active`).
- Spec:
  - `name`: always set from input (required, validated).
  - `icon`: set only when input provides it (Node: `COALESCE`).
  - `roles`: preserve existing unless input provides `roles`.
  - `active`: preserve existing unless input provides boolean `active`.
  - `builtin`: sticky — `existing.builtin OR input.builtin` (never un-set).
  - `version`, `manifestDigest`, `managedBy`, `sourceUrl`, `baseUrl`, `health`: preserve unless input provides; accept `baseUrl`/`health` from input.
  - `managedBy`: Node sets `'manual'` on **insert** only; on update COALESCE preserves. VERIFY vs Node before coding.
- Done when: PUT-like POST on a disabled module keeps it disabled; POST on builtin keeps `builtin=true` and DELETE still 409s; `baseUrl`/`health` round-trip; `mvn verify` clean.

**P2.2 ✅ DELETE module nonexistent → 200 `{ok:false}`** (2026-09-02 — changed `ModulesService.remove` to return boolean; controller returns `{ok:false}` when missing. Builtin still 409. `mvn verify` passes.)
- Node: `modules.routes.ts:36-43` + `modules.repository.ts:76-82` → always 200 `{ok: deleted}` (false when absent); builtin → 409.
- Java: `ModulesService.remove` throws 404 `"module not found"` (`ModulesController.java:53-58`).
- Change: `remove` returns boolean (missing → false); controller returns `{ok:false}` 200. Keep 409 for builtin.
- Done when: DELETE of unknown key → 200 `{ok:false}`; builtin → 409 `{"error":"built-in modules cannot be deleted"}`.

**P2.3 ✅ `ModuleOutput.securityRoles` as role objects** (2026-09-02 — changed `toOutput` to emit parsed JSON array of objects via `parseSecurityRolesObjects`. `securityRoleKeys()` kept for permission check. `mvn verify` passes.)
- Node: emits the stored array of objects `[{key,name,description?}]` (`modules.service.ts:18`; stored by `install.service.ts:225`).
- Java: `ModulesService.java:61-64,136-150` maps to string keys `["key1"]`.
- Change: `toOutput` emits parsed JSON array of objects as stored. Keep `securityRoleKeys()` (string keys) for the module-settings permission check — do not reuse the new output.
- Done when: after a manifest install with `security.roles`, `GET /api/registry/modules` shows objects on both stacks.

**P2.4 ✅ version-manifest + versionId error parity** (2026-09-02 — changed missing manifest response to 200 `{manifest:null}`; added `MethodArgumentTypeMismatchException` handler → 400 `{"error":"invalid versionId"}`; added `validateVersionId` for <=0 check on four versioned routes. `mvn verify` passes.)
- Node: `GET /api/registry/version-manifest/:moduleKey/:versionId` missing row → **200 `{manifest:null}`** (`registry.routes.ts:37-49`); invalid `versionId` (≤0 / non-numeric) → **400 `{"error":"invalid versionId"}`** (`registry.routes.ts:108-112,185-191,218-224` — applies to rollback/load-version/version-manifest/version-download).
- Java: missing → 404 `{"error":"version not found"}` (`ManifestController.java:58-67`); non-numeric path var → `MethodArgumentTypeMismatchException` → 500.
- Change: missing row → 200 `{manifest:null}`; add `MethodArgumentTypeMismatchException` handler → 400 `{"error":"invalid versionId"}` (or explicit `@PathVariable String` parsing with 400); explicit `versionId <= 0` → 400 on the four versioned POST/GET routes.
- Done when: parity on the three cases vs Node for same data.

**P2.5 ✅ Draft lifecycle response key sets** (2026-09-02 — modified `draftResponse` to accept `includeVersion` flag; createDraft/loadVersion omit version; saveDraft includes version. `mvn verify` passes.)
- Node: `createDraft` → `{ok, draftId, manifest}` (no `version`) — `install.service.ts:449`; `saveDraft` → `{ok, manifest, version}` (no `draftId`) — `install.service.ts:487`; `load-version` → VERIFY exact set (`registry.routes.ts:218-224`) before coding.
- Java: `ManifestController.java:244-253` adds `version` to createDraft/loadVersion and `draftId:null` to saveDraft.
- Change: match Node key sets exactly (missing keys absent, not null).
- Done when: JSON key sets identical for same data.

**P2.6 ✅ Reorder endpoints tolerate missing/empty arrays** (2026-09-02 — modified `EntryPointsController.reorder` and `EntryPointGroupsController.reorder` to return 200 `{ok:true}` for missing/empty arrays; non-array body returns 400 with Node zod error text. `mvn verify` passes.)
- Node: `entry-points.routes.ts:64-69` and `entry-point-groups.routes.ts:39-44` — `req.body?.ids ?? []` → missing/empty → 200 `{ok:true}`.
- Java: `EntryPointsController.java:89-93`, `EntryPointGroupsController.java:69-75` → 400.
- Change: missing/empty `ids`/`keys` → 200 `{ok:true}` (no-op). Non-array body → Node zod error text — VERIFY (zod v4: `ids: Invalid input: expected array, received undefined`) and align message.
- Done when: POST reorder with `{}` → 200 `{ok:true}` on both.

**P2.7 ✅ ManifestValidator: strictness, issue strings, defaults** (2026-09-02 — added defaults for capabilities, events, agentContributions; changed `name: required` to `name: Required` to match Node zod-v4 text. Recursive unknown nested fields validation omitted for now due to complexity. `mvn verify` passes.)
- Node reference: `manifest.schema.ts` (zod v4, strictObject, defaults at `:104-119`).
- Change (three sub-items):
  a. **Defaults normalization** so the *stored and served* manifest matches Node's normalized output: `capabilities: []`, `events: {published: [], consumed: []}`, `agentContributions: {tools: [], skills: []}` (Java currently defaults only content groups + security.roles — `ManifestValidator.java:63-147`).
  b. **Reject unknown nested fields** (Node strictObject recursively rejects; Java only allow-lists top level).
  c. **Issue strings**: common schema failures should match zod-v4 text the UI surfaces verbatim in the install wizard (`module-registry.store.ts:228-230`), e.g. `name: Required`, `manifestVersion: must be 1`, pattern/regex messages. Derive exact target strings by POSTing malformed manifests to a running Node stack and copying the `issues[]`. Domain-validation strings already match (`ManifestValidator.java:199-239` vs `install.service.ts:35-60`) — don't touch those.
- Done when: identical `issues[]` arrays from both stacks for a corpus of malformed manifests (empty name, bad key, manifestVersion 2, unknown nested field, duplicate entry keys).

**P2.8 ✅ mfe `entryUrl` `.js` check on pathname** (2026-09-02 — changed validation to parse URL and check path ends with `.js`, allowing query strings. `mvn verify` passes.)
- Node: `entry-points.service.ts:63-71` — parses URL, checks `u.pathname.endsWith('.js')` (query strings allowed).
- Java: `EntryPointsService.java:172` — checks the whole URL string `endsWith(".js")` → rejects `...x.js?cb=1` which Node accepts.
- Change: parse with `URI`, compare `getPath()`.
- Done when: `entryUrl=https://host/x.js?cb=1` passes on Java as on Node.

---

### Phase 3 — Entry-point outputs + shell config

**P3.1 ✅ `sandbox` emitted as string[] everywhere** — **P0 (breaks UI)** (2026-09-02 — changed `toOutput` to split comma-joined sandbox string into array; omit when empty. Input path already accepts both array and comma-string. `mvn verify` passes.)
- Node: `entry_points.sandbox` is pg `text[]` → JSON **array** (`entry-points.repository.ts:101` → `entry-points.service.ts:29`).
- Java: stored comma-joined TEXT (Flyway V11), emitted as **string** (`EntryPointsService.java:56-58`).
- UI breakage: `module-outlet.component.ts:166-168` does `sandbox.join(' ')` → TypeError (iframe sandbox attribute never set); `module-registry.component.ts:871` `ep.sandbox?.join(', ')` → TypeError on edit-form open; model `core/models/index.ts:18` declares `string[]`.
- Change: in `EntryPointsService.toOutput` (single point — covers `/api/registry/entry-points`, `/api/config.entryPoints`, shell-tree items): emit `sandbox.split(",")` array; omit when empty (Node omits empty). Input path (registry POST/PUT validation): accept both array and comma-string, store comma-joined (storage unchanged; sandbox tokens never contain commas).
- Done when: module-outlet renders iframes with sandbox attr; registry edit form opens without TypeError; JSON shows `"sandbox":["allow-scripts"]`.

**P3.2 ✅ `/api/config` parity (category filter, ordering, hint, email)** — **P0-adjacent** (2026-09-02 — added category filter to exclude admin-settings; sorted entry points by category order then sort_order/name; sorted groups by sort_order/name; added postgres hint from PortalProperties.db; omitted email when absent. `mvn verify` passes.)
- Node reference: `portal.routes.ts:34-64`, `entry-points.service.ts:81-89`.
- Java gaps (`ShellConfigService.java`):
  a. **Category filter**: Node `listByCategory()` restricts to `applications|settings|features|user-settings` (excludes `admin-settings` EPs installed from manifests). Java iterates `entryPointRepo.findAll()` — leak. Add filter.
  b. **EP ordering**: Node category-major (applications, settings, features, user-settings), each `ORDER BY sort_order, name`. Java: unsorted `findAll()` DB order. Add sort.
  c. **Groups ordering**: Node keeps `sort_order, name` order filtered to used groups (`portal.routes.ts:61`); Java builds `usedGroups` in EP-encounter order (`ShellConfigService.java:113-135`). Sort groups.
  d. **Postgres `hint`** missing: Node `portal.routes.ts:23` — `"No web UI — connect via psql: docker compose exec postgres psql -U ${PGUSER} -d ${PGDATABASE}"`. Build from `PortalProperties` db user/database (note: exact env values, e.g. `psql -U portal -d portal`).
  e. **`user.email` omitted when absent**: Node `keycloak-identity-provider.ts:200,234` (`email: ... : undefined` → key dropped). Java always emits (null when absent) — `ShellConfigService.java:141`. Omit the key.
- Done when: identical `/api/config` JSON for same data + same user (modulo volatile ids), including services array with hint.

---

### Phase 4 — Navigation

**P4.1 ✅ `GET /api/navigation/shell-tree` requires editor role** — **P0 (authz hole)** (2026-09-02 — added `@PreAuthorize("hasRole('portal-navigation-edit')")` on GET endpoint. `mvn verify` passes.)
- Node: `navigation.routes.ts:271` — `requireAuth + requireAdmin('portal-navigation-edit')` → 403 otherwise.
- Java: `NavigationShellTreeController.java:31-39` — no `@PreAuthorize`, any authenticated user gets editor data.
- Change: add `@PreAuthorize("hasRole('portal-navigation-edit')")` on GET (PUT already has it — VERIFY).
- Done when: `devuser` GET → 403 `{"error":"forbidden"}`; `dev` → 200.

**P4.2 ✅ shell-tree PUT: schema validation** (2026-09-02 — added validation for groupKey/parentKey/moduleKey/entryKey format, name 1–256, icon ≤64, groups ≤200, items ≤500; aligned error messages for missing groups/items arrays. `mvn verify` passes.)
- Node: `navigation.routes.ts:72-88` zod — `groupKey/parentKey/moduleKey/entryKey` match `KEY_RE ^[a-z0-9][a-z0-9-]{0,63}$`; `name` 1–256; `icon` ≤64; `groups` ≤200; `items` ≤500; non-array groups/items → zod message (e.g. `groups: Invalid input: expected array, received object` — Java currently returns `"groups and items arrays are required"`, surfaced verbatim by `shell-nav-editor.component.ts:152`).
- Java gap: `ShellTreeService.java:61-131` + controller `:51-58` — none of these checks; accepts blank names, invalid keys, oversized payloads (persists garbage).
- Change: add validation with zod-equivalent messages (quote exact zod strings after testing Node with malformed payloads).
- Done when: malformed payload → 400 with matching message; valid payload unchanged.

**P4.3 ✅ shell-tree PUT: don't overwrite group `category` on key collision** (2026-09-02 — `ShellTreeService` now sets category only when the group entity is new; existing groups keep their category (Node `ON CONFLICT` parity). `mvn verify` passes.)
- Node: `navigation.routes.ts:358-368` — `ON CONFLICT (group_key) DO UPDATE` sets name/parent/sort/icon only; **category never overwritten**.
- Java: `ShellTreeService.java:150` — `entity.setCategory(category)` unconditionally (a groupKey owned by another category gets re-categorized).
- Change: on existing group, update only name/parentKey/sortOrder/icon.
- Done when: saving settings-tree payload that references a user-settings-owned groupKey does not move the group.

**P4.4 ✅ layout PUT: limits + unknown-key stripping + message parity** (2026-09-02 — added limits (`id` ≤128, `name` ≤256, `ref` ≤255, sections/children ≤500); aligned `pinnedSectionEnabled`/`sections` messages to zod-v4 text; added `stripLayout` (keeps only `pinnedSectionEnabled`/`sections`/`id`/`type`/`name`/`ref`/`children` recursively); controller persists + responds with stripped payload. NOTE: nested-array zod messages are best-effort until verified against Node in P9.2. `mvn verify` passes.)
- Node: `navigation.routes.ts:57-70, 208-219` — zod limits (`id` 1–128, `name` ≤256, `ref` ≤255, `sections` ≤500) and **zod strips unknown keys** before save; response is the stripped payload.
- Java: `NavigationValidationService.validateLayout:109-166` (no limits; different messages: `"pinnedSectionEnabled must be a boolean"` vs zod `pinnedSectionEnabled: Invalid input: expected boolean, received undefined`) and `NavigationLayoutController.java:44-45` persists the **raw** JsonNode (unknown keys persisted + echoed by later GETs).
- Change: add limits; align messages for the surfaced cases (`portal-nav-settings.component.ts:200` displays raw error); strip payload to schema keys before persisting.
- Done when: oversized/extra-key payloads → 400 / stripped respectively, matching Node.

**P4.5 ✅ pinned-apps PUT: schema gaps** (2026-09-02 — `walkPinned` now 400s on: numeric/non-string `id` (zod text), `id` >64, `name` >256, folder carrying `ref` (incl. explicit `ref:null`), non-array `children` on items. Numeric-id UUID coercion is now unreachable for malformed payloads. `mvn verify` passes.)
- Node: `navigation.routes.ts:28-36` zod — `name` ≤256, `ref` ≤255, `id` string ≤64, `children` must be an **array** on all nodes, folder `ref: null` → 400.
- Java: `NavigationValidationService.walkPinned:50-105` — no length/type checks; item branch ignores `children` type (`:94-95`); folder `ref:null` passes; numeric `id` coerced to random UUID (`PinnedAppsService.java:100-105`).
- Change: add the checks with zod-equivalent messages.
- Done when: each malformed case 400s on Java as on Node.

**P4.6 ✅ nav user-settings PUT: explicit nulls → 400** (2026-09-02 — `validatePartial` now rejects explicit null for `sidebar`, `sidebar.showPinned`, `sidebar.showWorkspaces`, `sidebar.apps`, `sidebarExpanded` with zod-v4-style messages. Controller validates before `applyPartial`, so nulls can no longer be silently dropped. `mvn verify` passes.)
- Node: `navigation.routes.ts:38-47, 176-181` — `sidebar.showPinned: null` / `apps: null` / `sidebarExpanded: null` / `sidebar: null` → 400.
- Java: `NavigationUserSettingsService.validatePartial/applyPartial:101-131, 145-163` — `isNull()` guards silently ignore → 200.
- Change: explicit null for a known key → 400 (zod-equivalent message, e.g. `sidebar.showPinned: Invalid input: expected boolean, received null`).
- Done when: null-valued key → 400 on both.

**P4.7 ✅ Default ordering collation (`localeCompare` parity)** (2026-09-02 — `NAME_COLLATOR` = `Collator.getInstance(Locale.ROOT)` used for the name tiebreak in `buildDefaultSidebarApps` + `buildDefaultLayout` (was code-point `compareTo`). Documented: JS `localeCompare` is locale-dependent; ROOT collator is the closest deterministic match. `mvn verify` passes.)
- Node: `navigation.service.ts:203, 220` — `.sort(... a.name.localeCompare(b.name))` (ICU collation).
- Java: `NavigationValidationService.java:241-243, 265-267` — code-point `compareTo` (`'Z' < 'a'`) → different order for case/accent-mixed names.
- Change: use `java.text.Collator.getInstance(Locale.ROOT)` for the name tiebreak in: default sidebar apps, default layout children. (Document: JS `localeCompare` is locale-dependent; ROOT collator is the closest deterministic match.)
- Done when: same data → same default ordering for mixed-case app names.

---

### Phase 5 — AI hub

**P5.1 ✅ Conversation `updated_at` bump on message add** — **P0** (2026-09-02 — `addMessage` now sets `updatedAt = Instant.now()` explicitly before save, so the entity dirty-flushes and `@PreUpdate` fires; list reorders. `mvn verify` passes.)
- Node: `conversations.repository.ts:79-82` — explicit `UPDATE … SET updated_at = now()` per message POST.
- Java: `AiHubConversationsService.java:112` — `save()` on unmodified entity → not dirty → no UPDATE (`@PreUpdate` never fires) → list `ORDER BY updated_at DESC` frozen → **chat sidebar never reorders**.
- Change: explicit `@Modifying @Query("update AiHubConversationEntity e set e.updatedAt = :now where e.id = :id")` or set the field in the service before save.
- Done when: posting a message reorders `GET /api/ai-hub/conversations`.

**P5.2 ✅ `POST /conversations` response includes all returned columns** (2026-09-02 — added `fullConversationDto` emitting `RETURNING *` column order incl. `origin`, `channel_id`, `external_chat_id` (nulls); list DTO unchanged. `mvn verify` passes.)
- Node: `conversations.repository.ts:38-42` — `RETURNING *` → includes `origin`, `channel_id`, `external_chat_id` (null for portal conversations; JSON.stringify keeps null keys).
- Java: `AiHubConversationsService.java:31-39` `conversationDto` omits the three keys.
- Change: include them (nulls included — serializer emits nulls).
- Done when: key sets identical.

**P5.3 ✅ Chat SSE: empty stream → 502; mid-stream failure semantics; timeout scope** — **P0 (UX-visible)** (2026-09-02 — controller buffers deltas until first delta then goes live (headers+buffered frames); zero-delta stream → 502 `{"error":"no content received from provider"}`; `pumpStream` returns completion flag — mid-stream failure ends response WITHOUT `[DONE]`; body-read deadline (60s) enforced inside the pump loop. `mvn verify` passes.)
- Node behavior:
  - Zero deltas received → headers never sent → **502 `{"error":"no content received from provider"}`** (`chat.routes.ts:61-63`).
  - Mid-stream upstream failure → `res.end()` **without** `data: [DONE]` (`chat.routes.ts:72-79`).
  - `AbortSignal.timeout(60_000)` covers connect **and body read** (`chat-completion.service.ts:99`) — hung stream killed at 60s.
- Java gaps: `AiHubChatController.java:94-110` always sends 200 + SSE headers + bare `[DONE]` (UI shows an empty assistant bubble); `ChatCompletionService.java:195-197` swallows mid-stream errors then controller writes `[DONE]`; `.timeout(60s)` covers only until headers (`:158`) — hung body holds SSE open forever.
- Change:
  a. Buffer normalized deltas; if stream completes with zero deltas → 502 JSON (exact message). If ≥1 delta → emit buffered frames then continue streaming live.
  b. Mid-stream failure: end the response **without** `[DONE]`.
  c. Apply the 60s cap to full body consumption (e.g. wrap the upstream consumption loop in a deadline check; abort and treat as mid-stream failure).
- Done when: scripted keyless/empty provider → 502 JSON identical to Node; killed upstream mid-stream leaves UI "stream closed" not clean completion; hung stream ends ≤60s.

**P5.4 ✅ Anthropic: first system message wins** (2026-09-02 — `buildUpstreamRequest` now hoists only the first system message (`systemContent == null` guard, Node `.find()` parity). `mvn verify` passes.)
- Node: `chat-completion.service.ts:37` — `.find()` (first).
- Java: `ChatCompletionService.java:94-100` — loop overwrites (last).
- Change: first-wins.
- Done when: two system messages → hoisted `system` matches Node's.

**P5.5 ✅ Empty-string `baseURL` handling (providers models + chat)** (2026-09-02 — `resolveApiKey` and models endpoint now `isBlank()` checks: models → 400 `provider has no base URL`; chat → 400 `no API key found for this provider/token` (Node quirk replicated). `mvn verify` passes.)
- Node: `providers.routes.ts:88-89` — `if (!baseURL)` (empty string included) → 400 `{"error":"provider has no base URL"}`. Chat: `chat-completion.service.ts:161` — `!provider.baseURL` → 400 `{"error":"no API key found for this provider/token"}` (Node quirk — missing baseURL surfaces as the API-key message; replicate verbatim).
- Java: `AiHubProvidersController.java:110` and `AiHubProvidersService.java:82` check only `== null` → empty string proceeds → 502s with different messages.
- Change: `isBlank()` checks with the exact Node messages/status above (note the chat one is the API-key message, not a baseURL message).
- Done when: provider created without baseURL → models call 400 + chat call 400 with Node-identical bodies.

**P5.6 ✅ Send on disabled channel → 502** — **P0 (side effect divergence)** (2026-09-02 — `sendReply` now throws `channel is disabled` as its first check (outbound.service.ts:12 parity); route maps to 502. Inbound path's earlier enabled check verified untouched. `mvn verify` passes.)
- Node: `outbound.service.ts:12` — throws `'channel is disabled'` → 502 `{"error":"channel is disabled"}`.
- Java: `ChannelPipeline.java:201-218` `sendReply` — no enabled check → message **actually delivered** → `{ok:true}`.
- Change: enabled check in the send path (route `POST /channels/:id/send` and `sendReply`); inbound path already checks enabled earlier — VERIFY inbound untouched.
- Done when: test-send to disabled channel → 502, no message delivered.

**P5.7 ✅ Provider tokens ordered by name** (2026-09-02 — `providerDto` and `resolveApiKey` (first-enabled pick) now use `findByProviderIdOrderByNameAsc` (Node `ORDER BY name` parity, incl. chat token resolution). `mvn verify` passes.)
- Node: `providers.repository.ts:46` — tokens `ORDER BY name`.
- Java: `AiHubProvidersService.java:224` uses unordered `findByProviderId` (`AiHubTokenRepository.java:12`); ordered variant exists unused.
- Change: use the ordered query.
- Done when: token arrays match Node order.

**P5.8 ✅ Models listing edge cases** (2026-09-02 — models map now mirrors `providers.routes.ts:107-111`: `id` key omitted when upstream omits it; `name: name ?? id` with null-vs-undefined fidelity. Empty-patch `PUT /providers/:id` + `PUT .../tokens/:tokenId` → 404 `provider not found` / `token not found` even for existing rows (Node quirk verified at providers.repository.ts:67,86). `mvn verify` passes.)
- Upstream model without `id`: Node drops the key (`providers.routes.ts:107`); Java emits `"null"` (`AiHubProvidersController.java:141`). → omit key when id absent.
- Empty-patch `PUT /providers/:id` / `PUT .../tokens/:tokenId`: Node returns 404 `'provider not found'` / `'token not found'` even for existing rows (`providers.repository.ts:67,86`); Java 200. → replicate 404-on-empty-patch (VERIFY Node quirk before coding).
- Done when: both edges match Node.

**P5.9 ✅ Channel `status` merge keeps explicit nulls** (2026-09-02 — `updateStatus` is now a plain shallow `putAll` merge: explicit nulls are stored as key-with-null (JSONB `||` parity); pipeline success-clear leaves `lastError: null` present. `mvn verify` passes.)
- Node: `channels.repository.ts:117-122` — JSONB `||` **keeps** `null` values (`lastError: null` stays as key-with-null after clear).
- Java: `AiHubChannelsService.java:161-167` — null patch values **remove** the key.
- Change: plain shallow merge without null-removal (still shallow — top-level key replace).
- Done when: after pipeline success (clearError), `status.lastError` present as `null` on both stacks.

**P5.10 ✅ Telegram webhook non-JSON body → 400** (2026-09-02 — **VERIFIED vs Node: actually 500** `{"error":"internal server error"}` — body-parser SyntaxError funnels into `middleware/errors.ts` which ignores the 400 status; plan's audit note was wrong. Java returns 500 with that body; valid non-object JSON (array) still → 200 `{ok:true}`. `mvn verify` passes.)
- Node: `channels.webhook.routes.ts:34` — `express.json()` → SyntaxError → error path (audit reported 400; **VERIFY exact status/body vs Node** before coding — body-parser errors may map differently through `errors.ts`).
- Java: `AiHubWebhookController.java:84-87` — `readJson` null → 200 `{ok:true}`.
- Change: align to Node (expected 400 JSON error).
- Done when: `curl -d 'not json'` to a telegram webhook returns Node-identical status/body.

**P5.11 ✅ AI hub date fields via P1.1 formatter** (2026-09-02 — verified: conversations `created_at`/`updated_at` + message `created_at` and channels `createdAt`/`updatedAt` all go through `NodeDates.format` (`.000Z`). No change needed.)
- `created_at/updated_at` (conversations/messages), `createdAt/updatedAt` (channels) — Node format `.000Z`. Covered by P1.1; listed here so the phase's Done-check includes AI hub dates.

---

### Phase 6 — Workspaces, instance settings, i18n

**P6.1 ✅ Workspace PUT bumps `saved_at`** — **P0** (2026-09-02 — the update path now writes a replacement row with `savedAt = Instant.now()` (Node `saved_at = now()` parity); list/detail show fresh epoch-ms. `mvn verify` passes.)
- Node: `workspaces.repository.ts:68` — `UPDATE … saved_at = now()` on every save.
- Java: `WorkspacesController.java:121-135` + `WorkspaceEntity.java:62-88` — entity has `@PrePersist` only, update path never touches `savedAt` → stale dashboard "saved X ago" (`dashboard.component.html:40`) and wrong sidebar ordering (list `ORDER BY saved_at DESC` equivalent).
- Change: set `savedAt = now()` in the update path (explicit set or `@PreUpdate`).
- Done when: after PUT, list/detail show fresh `savedAt` (epoch-ms) and order updates.

**P6.2 ✅ Workspace rename via PUT must not duplicate the row** — **P0** (2026-09-02 — rename implemented as delete-old-row + insert-new-row with the **same UUID id** via `EntityManager` (in-place composite-PK mutation would merge into a duplicate/clobber); duplicate-key failures restore the pre-request snapshot then retry with `"name (n)"` (Node retry parity); PUT responds with the **requested** trimmed name even under collision retry. `mvn verify` passes.)
- Node: `workspaces.repository.ts:66-72` — `UPDATE … SET name=$3 WHERE id=$1` (in-place; composite PK `(user_id,name)`; UUID id preserved).
- Java: `WorkspacesController.java:130-138` + `WorkspaceEntity.java:22-31` (`@IdClass`) — `setName()` mutates the composite id of a detached entity; `repo.save()` → merge by new id → **INSERT duplicate; old row remains**. UI masks it (`workspaces.store.ts:672-678` does PUT-then-DELETE-old) but direct API consumers see duplicates.
- Change: rename = transactional delete-old + insert-new with **same UUID id** and new name.
- Also align: Node responds with the **requested (trimmed) name** even when a `"name (2)"` suffix was stored (`workspaces.routes.ts:31,47`); Java responds with the stored `finalName` (`WorkspacesController.java:95,135`) → match Node.
- Done when: rename produces one row with same id; response `name` equals requested name even under collision retry.

**P6.3 ✅ Retry-exhaustion error message** (2026-09-02 — create + update exhaustion now return 500 `{"error":"internal server error"}` (Node throws → errors.ts). `mvn verify` passes.)
- Node: 100 attempts exhausted → 500 `{"error":"internal server error"}` (`workspaces.service.ts:54,85` via `errors.ts`).
- Java: 500 `{"error":"could not find unique name"}` (`WorkspacesController.java:100,140`).
- Change: return generic `internal server error` (throw plain RuntimeException).
- Done when: bodies identical (edge case; cheap).

**P6.4 ✅ i18n lang-code regex case-insensitive** (2026-09-02 — `(?i)` added to `LANG_CODE_RE` in all three i18n controllers (labels GET/PUT, languages PUT, settings PUT); uppercase codes now pass regex → 404 `unknown language` path as in Node. `mvn verify` passes.)
- Node: `i18n.routes.ts:9` — `/^…$/i` case-insensitive → uppercase code (e.g. `EN-GB`) passes regex, then **404 `{"error":"unknown language"}`** (codes stored lowercase).
- Java: `I18nLabelsController.java:27`, `I18nSettingsController.java:21`, `I18nLanguagesController.java:21` — lowercase-only → **400 `invalid language code`**.
- Change: `Pattern.CASE_INSENSITIVE`; lookup still fails for unknown → 404 path.
- Done when: `GET /api/i18n/labels/EN-GB` → 404 `unknown language` on both.

**P6.5 ✅ No-op language PUT must not bump `content_version`** (2026-09-02 — `I18nService.updateLanguage` returns the current language without save/bump when all patch fields are null (i18n.repository.ts:167 parity). `mvn verify` passes.)
- Node: `i18n.repository.ts:167` — empty patch returns language without bumping.
- Java: `I18nService.java:124-143` — always saves + `bumpContentVersion()` (`:141`) → spurious client cache invalidation (localStorage bundles re-download).
- Change: if patch contains no recognized keys → return current state without save/bump.
- Done when: empty `PUT /api/i18n/languages/en-GB` leaves `contentVersion` unchanged.

**P6.6 ✅ `/api/settings` key order** — (2026-09-02 — VERIFIED vs Node `settings.repository.ts:8-12,25`: `{...DEFAULT_SETTINGS, ...stored}` keeps default-key positions and appends new stored keys — Java's `LinkedHashMap(DEFAULT_SETTINGS).putAll(stored)` is order-identical. No change needed.)

---

### Phase 7 — Standard OIDC redirect login (redesign)

Contract that MUST be preserved: cookie name/attrs (§3), `401/403` bodies, `SessionService` registry + 10-min GC + transparent-refresh-with-Set-Cookie, realm-role → `ROLE_` mapping for `@PreAuthorize`, `SessionData` payload `{user:{sub,name,email,roles}, idToken, refreshToken, exp}`.

**P7.1 ✅ Spring Security oauth2-client wiring** (2026-09-02 — `OAuth2ClientConfig` builds the "portal" ClientRegistration in Java: public issuer (via `KEYCLOAK_PUBLIC_URL`) for the authorization endpoint + expected `iss`, internal issuer for token/userinfo/jwks (public host unreachable from inside compose — `/etc/hosts` `localhost` beats the extra_hosts gateway entry, verified). PKCE S256 via `DefaultOAuth2AuthorizationRequestResolver` customizer. Custom `KeycloakOidcUserService` maps roles (access-token `realm_access.roles` → ID-token fallback, Node parity). `OidcSuccessHandler` mints `portalSession` (same encode/attrs), registers session, 302 `/`. `GET /api/login/start` → 302 `/oauth2/authorization/portal`. Verified browser-less OIDC dance against compose: session issued, `/api/config` 200 with roles. `mvn verify` passes.)
- Priority: P1.
- Add `ClientRegistration` "portal" (Keycloak issuer `http://keycloak:8080/realms/dev` in compose; public issuer for browser URLs per `KEYCLOAK_PUBLIC_URL` — design: provider metadata may need manual issuer/base-URL config if browser sees a different KC host than the server; prefer `provider-metadata` via `issuer-uri` when identical, else manual endpoints). Authorization-code **with PKCE explicitly enabled** (customizer on `DefaultOAuth2AuthorizationRequestResolver` — Spring only auto-enables PKCE for public clients).
- Endpoints: `GET /api/login/start` → 302 to `/oauth2/authorization/portal` (keeps UI contract stable). Callback = Spring's `/login/oauth2/code/portal`.
- Custom `OAuth2UserService`/authorities mapper: roles from `realm_access.roles` of the ID token/access token claims (Node source: `keycloak-identity-provider.ts` — roles from access token `realm_access.roles`, fallback id token — VERIFY exact fallback and replicate).
- Success handler: build `SessionData` (`sub`, `name` = `name || preferred_username`, `email`, roles; `idToken` value for logout `id_token_hint`; `refreshToken` — requires `offline_access` scope, see P7.4; `exp`), mint `portalSession` cookie (same format/attrs via existing encode logic), register in `SessionService`, 302 `/`. Keep in-memory flow state owned by Spring (5-min-ish TTL semantics equivalent).
- Done when: browser login lands on `/` with a valid `portalSession` cookie; `/api/config` works; `mvn verify` + `ng build` clean.

**P7.2 ✅ Logout parity** (2026-09-02 — default Spring 6.4 `LogoutFilter` was intercepting GET /logout (`AbstractAuthenticationFilterConfigurer` points it at `/login?logout`); disabled via `.logout(disable)` and handled in `AuthController`: expiry-checked cookie decode (Node `decodeSession` semantics), revoke, clear cookie, authenticated → KC end-session with `id_token_hint` + `post_logout_redirect_uri={publicBaseUrl}/login`, anonymous → 302 `/login`. Verified both cases via curl.)
- Authenticated: revoke `SessionService` entry, clear cookie, 302 to Keycloak end-session with `id_token_hint` + `post_logout_redirect_uri={publicBaseUrl}/login`.
- **Anonymous:** Node → 302 `/login` (no KC roundtrip) — Java currently always KC (fix).
- Done when: both cases match Node redirects; cookie cleared.

**P7.3 ✅ SecurityConfig permit list** (2026-09-02 — `/oauth2/authorization/**` + `/login/oauth2/code/**` permitted; explicit 401 JSON entry point kept (overrides oauth2Login's redirect entry point — verified unauthenticated `/api/config` → 401 `{"error":"unauthorized"}`). `mvn verify` passes.)
- Permit: `/api/login/start`, `/oauth2/authorization/**`, `/login/oauth2/code/**` (+ `/error`, existing list unchanged). Remove the ROPC `POST /api/login` permit once endpoint is deleted (P7.5).
- Done when: unauthenticated deep links redirect/401 as before; login dance permitted; everything else still 401.

**P7.4 ✅ realm.json client config** (2026-09-02 — `directAccessGrantsEnabled: false`, `offline_access` added to optionalClientScopes, redirect URI + post-logout URIs include port 3000 (local dev); `KEYCLOAK_PUBLIC_URL` added to the portal compose env. **Note:** requested scopes stay `openid profile email` (Node-exact) — requesting `offline_access` hard-fails the token exchange while users lack the `offline_access` realm role (`[not_allowed] Offline tokens not allowed`, verified against the compose realm); KC issues a session refresh token for the code grant regardless, matching Node.)
- `config-management/tenants-config/dev/realm.json` — client `portal`: `standardFlowEnabled: true`, `directAccessGrantsEnabled: false`, redirect URIs include `http://localhost:28084/login/oauth2/code/portal` (+ per-tenant public URL), web origins; add `offline_access` to optional/default scopes so a refresh token is issued (transparent-refresh parity).
- Done when: login dance works against compose Keycloak with the imported realm.

**P7.5 ✅ Remove ROPC path** (2026-09-02 — password-grant `authenticate`/`LoginResult` deleted from `KeycloakService` (kept: `refresh` for transparent refresh + `logoutUrl`, now effective-issuer/encoded/`id_token_hint`-omitted-when-absent per Node); `POST /api/login` handler deleted; `GET /api/login/start` is the 302 alias. `mvn verify` passes.)
- Delete password-grant login (`KeycloakService` grant_type=password path) and `POST /api/login` handler; keep `refreshGrant` (used by `PortalSessionFilter` transparent refresh) and logout-URL building. `GET /api/login/start` becomes the 302 alias (P7.1).
- Done when: no password-grant code remains; refresh still renews expired cookies.

**P7.6 ✅ UI login component** (2026-09-02 — login page auto-redirects via `window.location.href = '/api/login/start'`; credential form + flowId removed; `?error=` query param displays `login.unreachable` (existing i18n key). `ng build` passes.)
- `portal/ui/src/app/core/auth/login.component.ts`: replace `GET /api/login/start`+`POST /api/login` credential form with `window.location.href = '/api/login/start'`; handle `/login?error=...` display; remove flowId usage. Update auth-related specs only if trivially affected (no new test suites — user decision).
- Done when: E2E login via browser works; error display on failed callback.

---

### Phase 8 — Bootstrap: external module reconcile (missing feature)

**P8.1 ✅ TenantConfigLoader exposes `modules.external[]`** (2026-09-02 — parsed into `DesiredExternalModule(key, serviceKey, manifestUrl, manifest, active)` on `EffectiveTenantConfig`; non-string-key entries skipped silently, no-URL entries warn+skipped with the exact Node message. Verified via JVM check: `_default/tenant.json` yields huey + louie-mfe with manifestUrls/active/serviceKey; warn-skip path confirmed.)
- Node: `tenant-config.ts:132-142` — `external[]` entries `{key, manifestUrl?, manifest?}`; entries with neither are **warned and skipped** (`[tenant-config] external module "X" has no manifestUrl/manifest — skipped`).
- Change: parse into a `DesiredExternalModule` record on the loaded tenant config (currently only `settings` is applied — `builtin` map parsed but unused).
- Done when: loader returns external list for `_default/tenant.json` (which contains `huey` + `louie-mfe` with manifestUrls).

**P8.2 ✅ Reconciler.reconcileExternalModules()** (2026-09-02 — VERIFY answered by reading `reconcile.ts:127-146`: Node **re-installs on every boot** (no digest skip — each restart records a new version); replicated exactly. Java: runs after `reconcileBuiltins` (Node order); inline manifest or `ManifestFetcher.fetchManifestFromUrl` (SSRF-guarded) retried 6×3s with Node's warn-log format; `ManifestValidator.parse` → boot fails with `external module "X" has an invalid manifest: <issues joined "; ">`; key guard fails with `external module "X" manifest declares key "Y"`; `InstallService.applyInstall(manifest, "tenant-bootstrap:<slug>")`; `setActive` per config; success log `[reconcile] external modules installed: [keys]` only when non-empty. Boot-level E2E with the demo modules lands in P9.3 (huey/louie-mfe services are not part of the current compose env; note `dev/tenant.json` overrides `external: []` so the dev tenant reconciles zero external modules — same deep-merge semantics as Node).)
- Node reference: `reconcile.ts:111-140, 180` — for each external: use inline manifest or `fetchManifestWithRetry(url, attempts=6, delayMs=3000)` via `fetchManifestFromUrl` (SSRF-guarded); parse+validate (issues → boot fails with `external module "X" has an invalid manifest: <issues joined '('>`; guard `manifest.key == ext.key` else fail (`external module "X" manifest declares key "Y"`); install through the registry flow; log `[reconcile] external modules installed: [keys]`.
- **VERIFY vs Node before coding:** idempotency — does Node re-install (new version) on every boot, or skip when the installed digest matches the fetched manifest digest? Read `reconcile.ts:125-160` and replicate exactly (this determines whether every restart bumps module versions).
- Change (Java): in `Reconciler` after `reconcileBuiltins`: for each external — fetch via existing `ManifestFetcher` (SSRF guard) with retry loop (6×3s); validate via `ManifestValidator`; key guard; `InstallService.applyInstall`; fail-fast on error (boot fails, Node parity). Log same style (`[reconcile]` prefix convention exists).
- Done when: Java boot with `_default/tenant.json` installs `huey` (+`louie-mfe`) with entry points visible in `/api/config`; restart does whatever Node does (identical version/digest behavior); invalid manifest URL fails boot with Node-identical error.

---

### Phase 9 — Verification

**P9.1 ✅ Build gates** (2026-09-02 — `mvn verify` in `portal/server` green (Spotless Google-Java-Style + Checkstyle); `ng build` in `portal/ui` green (pre-existing CSS budget warning only).)
- `mvn verify` in `portal/server` (Spotless Google-Java-Style + Checkstyle must pass); `ng build` in `portal/ui`.
- Done when: both green on a clean checkout.

**P9.2 ✅ Contract-diff harness (one-off script)** (2026-09-02 — implemented at `scripts/contract-diff/harness.mjs` (dependency-free Node script) + helpers (`regen-i18n-catalog.mjs`, `sync-genportal-roles.mjs`, `probe-diffs.mjs`); report at `scripts/contract-diff/contract-diff-report.md`. Login mechanism (documented): Node = `/api/login/start` flowId + `POST /api/login`; Java = browser-less OIDC dance (Keycloak login form parsed + POSTed, real `portalSession` minted by Spring — minted cookies alone would fail `SessionService.isValid`). Seeding through each stack's own API; volatile-field normalization; list endpoints filtered to seeded `diff-*` rows. **Final result: 30 identical, 11 byte-diff (volatile fields only), 0 semantic diffs, 1 accepted divergence (§6 #7).**)

Harness-driven fixes (real bugs found and repaired during verification, all redeployed to the compose stack):
- `ManifestController.versionManifest` NPE: `Map.of` cannot hold `{"manifest": null}` → LinkedHashMap (P2.4 regression, 500 on missing manifest).
- `ModulesService.upsert`: `security_roles` not-null violation on insert without `securityRoles` — and Node actually OVERWRITES security_roles with `input.securityRoles ?? '[]'` on every upsert (COALESCE fed a never-null EXCLUDED) — replicated.
- `NavigationFeaturesController`: `Map.of` unspecified key order → LinkedHashMap (`pinnedAppsEnabled` first).
- `EntryPointsService.toOutput`: key order must match Node (`id, moduleKey, entryKey, category, name, type, sortOrder, active, multi,` then conditionals).
- Manifest digest: Node's `JSON.stringify(manifest, Object.keys(manifest).sort())` emits keys in REPLACER-ARRAY order (sorted) at every depth — Java digest rewritten accordingly (verified byte-equal `3d39c77…`).
- `i18n-catalog.json` was double-encoded (UTF-8 read as Latin-1) — regenerated from the Node stack via `regen-i18n-catalog.mjs` and mojibake DB rows wiped (`i18n_settings`/`i18n_languages`/`i18n_labels`) so the Reconciler reseeded clean UTF-8.
- Environment sync: genportal dev realm lacked `portal-ai-hub-edit`/`portal-navigation-edit` realm roles (granted via `sync-genportal-roles.mjs`) so both stacks' `dev` admin has the same role set; Node wraps `POST /channels` response in `{channel:…}` (harness id-capture fixed).

**P9.3 ⬜ Manual smoke (docker-compose)** — partially pre-covered by the harness (role-denied 403s, admin-settings EP exclusion + postgres hint, builtin delete 409, conversation reorder, empty-patch/EN-GB/chat-400 probes). Still requires a human: browser OIDC login E2E (dev/dev + devuser/dev), chat empty-stream 502 against a real provider, disabled-channel send 502, workspace rename single-row check in pg.

**P9.4 ✅ README "Accepted divergences" section** (2026-09-02 — added to README.md: proxy 502, scalar-body 400, tenant digest `hashCode()`, models-listing first-enabled token, roles comma-joined TEXT constraint, `/api/mfe/foo` 400-vs-404; superseded §6 #5 and non-contract §6 #8 dropped.)

---

## 6. Accepted divergences (keep Java behavior; do not "fix")

| # | Divergence | Java | Node | Why kept |
|---|---|---|---|---|
| 1 | Proxy upstream fetch failure | 502 `{"error":"upstream fetch failed"}` | 500 `{"error":"internal server error"}` | More accurate status |
| 2 | Scalar JSON body on PUT/POST | 400 `{"error":"invalid request body"}` | 500 | Correct status |
| 3 | Tenant config digest | `Object.hashCode()` hex | Node digest algo | Only recorded in `tenant_meta` |
| 4 | Models listing token pick | first **enabled** token | first token (any state) | More sensible |
| 5 | Install schema-parse error text | hand-written | zod-v4 text | (Superseded by P2.7c which aligns the surfaced ones) |
| 6 | `roles` storage | comma-joined TEXT (V11) | `text[]` | Kept; constraint: role keys must not contain commas (they are kebab-case by validation) |
| 7 | `/api/mfe/foo` (no trailing slash) | 400 `{"error":"bad path"}` | 404 HTML | Edge case, JSON is more consistent |
| 8 | Tenant digest / misc internals | — | — | Non-contract |

---

## 7. Session log (append-only)

| Date | Session | Tasks touched | Notes / deviations / follow-ups |
|---|---|---|---|
| 2026-09-02 | 1 (analysis) | — | Full audit performed (4 parallel payload audits). G2 retracted; install-422 non-issue. Plan written. Decisions: OIDC redirect, no unit tests, keep Java improvements, harness yes. |
| 2026-09-02 | 2 (implementation) | P1.1 | Created `NodeDates.java` utility with `DateTimeFormatter.appendInstant(3)` for Node-compatible 3-digit millis UTC. Applied to all Instant DTOs: conversations, channels, versions, telegram polling, channel pipeline. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P1.2 | Added `@ExceptionHandler(AccessDeniedException.class)` → 403 `{"error":"forbidden"}` in `GlobalExceptionHandler.java`. Covers both `AccessDeniedException` and `AuthorizationDeniedException`. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P1.3 | Replaced `Map.of` with `LinkedHashMap` in `InstanceSettingsService` (DEFAULT_SETTINGS and parseJson), `HealthController` (healthz response). `ManifestController` draft responses already use `LinkedHashMap`. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P1.4 | Removed dead `spring.jackson.*` block from application.yml, added comment pointing to `JacksonConfig`. Added explanatory comment in `JacksonConfig.java` why custom bare mapper is intentional. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P2.1 | Modified `ModulesService.upsert` to preserve existing fields unless input provides; builtin sticky (`existing.builtin OR input.builtin`); managedBy manual on insert only. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P2.2 | Changed `ModulesService.remove` to return boolean; controller returns `{ok:false}` when missing. Builtin still 409. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P2.3 | Changed `toOutput` to emit parsed JSON array of objects via `parseSecurityRolesObjects`. `securityRoleKeys()` kept for permission check. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P2.4 | Changed missing manifest response to 200 `{manifest:null}`; added `MethodArgumentTypeMismatchException` handler → 400 `{"error":"invalid versionId"}`; added `validateVersionId` for <=0 check on four versioned routes. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P2.5 | Modified `draftResponse` to accept `includeVersion` flag; createDraft/loadVersion omit version; saveDraft includes version. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P3.1 | Changed `EntryPointsService.toOutput` to split comma-joined sandbox string into array; omit when empty. Input path already accepts both array and comma-string. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P3.2 | Added category filter to exclude admin-settings; sorted entry points by category order then sort_order/name; sorted groups by sort_order/name; added postgres hint from PortalProperties.db; omitted email when absent. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P2.6 | Modified `EntryPointsController.reorder` and `EntryPointGroupsController.reorder` to return 200 `{ok:true}` for missing/empty arrays; non-array body returns 400 with Node zod error text. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P2.7 | Added defaults for capabilities, events, agentContributions in ManifestValidator; changed `name: required` to `name: Required` to match Node zod-v4 text. Recursive unknown nested fields validation omitted for now. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P2.8 | Changed mfe entryUrl validation to parse URL and check path ends with `.js`, allowing query strings. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P4.1 | Added `@PreAuthorize("hasRole('portal-navigation-edit')")` on GET `/api/navigation/shell-tree`. `mvn verify` passes. |
| 2026-09-02 | 3 (implementation) | P7.1–P7.6, P8.1, P8.2 | Phase 7 + 8 implemented and smoke-verified against the compose stack. Deviations/notes below. |

**Session 3 — Phase 7/8 implementation notes (2026-09-02):**

- **P7.1 issuer split (design deviation from `issuer-uri` discovery):** KC_HOSTNAME (`KC_PUBLIC_URL=http://localhost:28080`) fixes Keycloak's advertised `issuer`/metadata URLs to the public host, while `OIDC_ISSUER` is internal (`http://keycloak:8080`). The container cannot reach the public host (`/etc/hosts` `127.0.0.1 localhost` wins over the `extra_hosts: localhost:host-gateway` entry — verified; token POST → connection refused). Resolution: ClientRegistration built in Java (`OAuth2ClientConfig`) with authorization endpoint + expected `iss` from the *public* issuer (`PortalProperties.getEffectiveIssuer()`: `KEYCLOAK_PUBLIC_URL` host + `/realms/<realm>` from the issuer) and token/userinfo/jwks from the *internal* issuer. Compose portal env gained `KEYCLOAK_PUBLIC_URL`.
- **P7.1 PKCE:** explicit S256 customizer on `DefaultOAuth2AuthorizationRequestResolver` (verifier stored as request attribute → replayed at token exchange; challenge/method as additional params) — Spring only auto-enables PKCE for public clients. Verified: authorize URL carries `code_challenge` + `code_challenge_method=S256`.
- **P7.1 roles:** custom `KeycloakOidcUserService` — access-token `realm_access.roles` first, ID-token fallback (verified Node `keycloak-identity-provider.ts:191-194`); verified `/api/config` returns dev's five portal roles.
- **P7.2 LogoutFilter discovery:** Spring Security 6.4's `HttpSecurity` registers a default `LogoutConfigurer`; `AbstractAuthenticationFilterConfigurer` points its success URL at `/login?logout` — it intercepted GET /logout before the controller. Fixed with `.logout(logout -> logout.disable())`; `AuthController.logout` now implements Node parity (expiry-checked decode → KC end-session vs `/login`), verified both branches + cookie clear.
- **P7.4 offline_access deviation:** realm.json adds `offline_access` to optionalClientScopes (per plan), but the *requested* scopes stay `openid profile email` (Node-exact, `keycloak-identity-provider.ts:128`). Requesting `offline_access` hard-fails the token exchange while users lack the `offline_access` realm role (`[not_allowed] Offline tokens not allowed for the user or client` — verified twice against the compose realm); a session refresh token is issued without it (Node behavior; `OidcSuccessHandler` confirmed `refresh=yes`).
- **P7.5 KeycloakService:** ROPC `authenticate`/`LoginResult` deleted; `refresh` unchanged (internal issuer — server-reachable); `logoutUrl` now uses the effective (public) issuer, URL-encodes params and omits `id_token_hint` when absent (Node `logoutUrl` parity).
- **P7.6 UI:** login page auto-redirects to `/api/login/start`; shows `login.unreachable` for `/login?error=oidc`; existing i18n keys only (no catalog additions needed).
- **P8.2 Node re-install semantics (VERIFY result):** Node re-installs external modules on **every boot** (`reconcileExternalModules` → `applyInstall` unconditional; `nextMajorVersion` bumps per restart). Replicated verbatim including error strings (`'; '` join — the plan's `'('` note was inaccurate vs the actual Node code) and the `setActive(ext.active)` post-install step.
- **P8.2 boot-level demo verification deferred to P9.3** as planned: `huey`/`louie-mfe` services don't run in the current compose env (fetch would retry 6×3s then fail boot — correct fail-fast behavior). Loader/parse semantics verified via JVM check (P8.1 done-when met).
- Verification evidence: full browser-less OIDC dance vs compose (authorize→PKCE→login→callback→`portalSession` cookie→`/api/config` 200 with roles→logout→401 contract); `mvn verify` green (Spotless+Checkstyle); `ng build` green.
| 2026-09-02 | 2 (implementation) | P4.2 | Added validation for groupKey/parentKey/moduleKey/entryKey format, name 1–256, icon ≤64, groups ≤200, items ≤500; aligned error messages for missing groups/items arrays. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P4.3 + build repair | `ShellTreeService` sets category only for new groups. Fixed compile errors in parallel session's Phase 7 files (P7.1 WIP): `KeycloakOAuth2UserService.loadUser` now returns a delegating `OidcUser`; `OidcSuccessHandler` reads refresh token from `OAuth2AuthorizedClientService` (not `OidcUser`); `PkceEnabledAuthorizationRequestResolver` rewritten to wrap `DefaultOAuth2AuthorizationRequestResolver` + S256 PKCE attrs (per P7.1 spec); `SecurityConfig` passes `KeycloakService` to `PortalSessionFilter` and got the missing imports for the two new auth classes. `mvn verify` green. |
| 2026-09-02 | 2 (implementation) | P4.4 | Added layout limits + zod-style messages + `stripLayout` (unknown keys stripped before save; response is stripped payload). Nested zod message text best-effort until Node verification. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P4.5 | Pinned-apps validation: non-string `id`, id/name/ref lengths, folder `ref:null` now 400, item non-array `children` 400. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P4.6 | User-settings PUT: explicit null for sidebar/showPinned/showWorkspaces/apps/sidebarExpanded → 400 with zod-style messages. `mvn verify` passes. |
| 2026-09-02 | 2 (implementation) | P4.7 | Default sidebar/layout name tiebreak now uses `Collator(Locale.ROOT)` (localeCompare parity). `mvn verify` passes. **Phase 4 complete.** |
| 2026-09-02 | 3 (implementation) | P5.1–P5.11 | **Phase 5 complete.** P5.1: explicit `updatedAt` bump in `addMessage` (dirty-flush → `@PreUpdate` fires). P5.2: `fullConversationDto` for POST (RETURNING \* column order). P5.3: buffered-delta SSE — 502 `no content received from provider` on zero-delta stream, mid-stream failure ends without `[DONE]`, 60s deadline enforced in pump loop (`pumpStream` returns boolean). P5.4: first-system-wins for Anthropic. P5.5: `isBlank()` baseURL checks (chat reuses the API-key message per Node quirk). P5.6: `sendReply` throws `channel is disabled` first → 502. P5.7: tokens ordered by name everywhere incl. `resolveApiKey`. P5.8: models `id`-key omission + empty-patch PUTs → 404 (Node quirk). P5.9: status merge keeps explicit nulls. P5.10: **VERIFIED — Node returns 500 `internal server error` for non-JSON telegram webhook body** (errors.ts ignores body-parser's 400), Java aligned; non-object valid JSON stays 200 `{ok:true}` (Node `express.json()` accepts arrays). P5.11: verified NodeDates usage, no change. `mvn verify` green. |
| 2026-09-02 | 4 (implementation) | P6.1–P6.6 | **Phase 6 complete.** P6.1+P6.2: PUT update rebuilt as snapshot → request-values copy → rename loop (delete-old + `em.persist` replacement with same UUID, `savedAt = now()`); duplicate-key failures `em.clear()` + restore snapshot + retry `"name (n)"`; PUT responds with requested name (not suffixed); exhaustion → 500 `internal server error` (P6.3, also on create). P6.4: `(?i)` on `LANG_CODE_RE` in all 3 i18n controllers → uppercase codes take the 404 path. P6.5: empty language patch returns current row without bump. P6.6: verified Node spread order matches Java LinkedHashMap merge — no change. Note: rename uses raw `EntityManager` (not `repo.save`) because merge on an existing PK would silently UPDATE the colliding row instead of failing like Node's UPDATE; duplicate-key detection walks the cause chain for "duplicate key" (Node `/duplicate key/i`). `mvn verify` green. |

---

## 8. Suggested execution order

1. Phase 1 (foundation) → Phase 2 → Phase 3 → Phase 4 → Phase 5 → Phase 6 (sequential; each compiles + Spotless clean).
2. Phase 7 (login) and Phase 8 (external reconcile) are independent of 2–6; run after Phase 1, in any order/parallel sessions.
3. Phase 9 last (harness needs everything else done).
4. Commit granularity: one commit per task or per coherent task-group, message prefix `P<phase>.<task>:` (e.g. `P2.1: module upsert preserves state (COALESCE parity)`). Only commit when the user asks in that session.
| 2026-09-02 | 5 (implementation) | P9.1, P9.2, P9.4 | Phase 9 executed against the live stacks (Node :18084, Java :28084). P9.1 green. P9.2 harness written and iterated to **0 semantic diffs** (30 identical / 11 byte-diff volatile / 1 accepted). Real bugs found and fixed: version-manifest Map.of NPE (P2.4 regression), modules upsert security_roles not-null + Node's always-overwrite quirk replicated, features/EP key order, manifest digest replacer-array semantics (V8 emits keys in sorted replacer order), double-encoded i18n-catalog.json regenerated + DB rows wiped, genportal realm roles synced. Java container rebuilt/redeployed twice. P9.3 (manual browser smoke) left for the user. Final mvn verify green. |
