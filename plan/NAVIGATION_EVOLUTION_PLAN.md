---
status: active
domain: navigation
summary: "Navigation module evolution: split nav settings into 4 pages, seeded module/Core shell trees, no root-level apps, drop/shift semantics, avatar color picker with apply-to-all, sidebar overflow + chat dock, recently used replaces quick access, workspaces section on the Portal Navigation page, delete dashboard module, right-aligned tree chevrons, module-scoped settings paths."
---

# Navigation module — fixes & evolutions

Detailed implementation plan. Derived from a full code survey of the portal UI (`portal/ui`) and
server (`portal/server`). File references are current as of 2026-10-12.

> **Implementation status (2026-10-12): all ten workstreams landed.**
> `mvn verify` green (343 tests incl. Testcontainers smoke ITs), `npm test` green (284),
> `npm run build` green (pre-existing `anyComponentStyle` budget warnings on
> `markdown-editor`/`dnd-tree` remain; both already exceeded the 4 kB budget on HEAD — the
> dnd-tree stylesheet was compacted to a smaller total). Dev-stack boot verified against the
> real dev DB: V38 applied, Core + per-module sections seeded, i18n keys seeded (4 languages),
> retired rows (`portal-dashboard`, `settings:general`, `navigation:portal-nav`) deleted.
> Deferred: Playwright e2e run (the dev NATS holds port 32253 the e2e stack needs — run with
> the dev stack stopped or after a port split in the e2e compose).

User decisions baked into this plan (confirmed 2026-10-12):

| Decision | Choice |
|---|---|
| Shell-tree depth cap | **Keep 3 levels** (root section > section > sub-section > apps). Fix the moveIn math bug; deeper nesting stays rejected by design. |
| "Recently used" persistence | **Server-side** in `navigation_user_settings` (last N refs, debounced PUT). |
| Section-visibility toggles + Home-app setting | **Merge the existing General settings page** (`settings:general` / `portal-general-settings`, currently only the home-app dropdown, owned by the settings module) **into the navigation module** as the new General page, carrying the Home-app dropdown + the Pinned-apps / Workspaces feature switches + the Pinned-apps-section / Workspaces-section toggles. |
| Auto "section per module" + "Core" seeding | **All three shell trees** (applications, admin-settings, user-settings). |

---

## 0. Current-state findings (root causes for the reported bugs)

| # | Symptom | Root cause (file:line) |
|---|---|---|
| 1 | Tab shows no color when the app color is unset (square shows the hash color) | Tab dot binds raw `tab.content.color` with no fallback — `portal/ui/src/app/portal-core/workarea/tab-area.component.html:45`. Squares use `avatarBg()` = `ep.color \|\| avatarColor(moduleContentId(ep))` (FNV-1a hash over the ref; `portal/ui/src/app/core/navigation/navigation.models.ts:123-131`, `AVATAR_COLORS` palette at :110-121). The fallback exists in `tab-area.component.ts:160-163` but is not used by the dot. |
| 2 | Pinned apps not displayed when the sidebar is EXPANDED | The rail is a fixed-height, `overflow:hidden` flex column (`.ds-sidebar`, `portal/ui/src/design-system/styles/components.css:423-431`; shell wraps it in `h-screen overflow-hidden`, `shell.component.html:3`). Only the pinned `<nav>` has `overflow-y-auto` (`sidebar.component.html:93`) ⇒ per flexbox rules it is the only child with `min-height:0`, so when expanded-mode content overflows, the pinned list alone collapses toward 0px. Secondary: unresolvable pinned refs render nothing (no `@else`, `sidebar.component.html:116-138`) and the sidebar gates on `features().pinnedAppsEnabled` while the portal page uses `pinnedSectionEnabled` (two different flags — see §W8/W7). |
| 3 | Sidebar vertical overflow unmanaged; chat not docked | Only the pinned nav scrolls; quick-access (`sidebar.component.html:152`) and workspaces (`:184`) navs don't; the chat button relies on `flex-1` spacer + `mt-auto` (`:252-271`) which only works while content fits. |
| 4 | Settings nav tree in navigation settings ≠ settings shell tree | Editor GET `/api/navigation/shell-tree` returns ALL groups (hidden, role-restricted) and items filtered **only** by `active=TRUE` — items of inactive modules still appear (`ShellTreeService.java:47-63`, single filter at `:59`). Settings shell renders the per-user role-filtered `/api/config` snapshot (`ShellConfigService.java:77-193`: module active+roles → group roles+hidden-cascade → item active/hidden/category/roles/group-allowed). Deltas: inactive-module items, hidden rows, role-restricted rows, groups no longer referenced. |
| 5 | "Shift right" fails for 2nd-level sections | `DndTree.moveIn` (`dnd-tree.component.ts:309-325`) hand-rolls its own depth check: `targetDepth = depthOfNode(sibling)+1` and rejects when `subtreeDepthOf(node) + targetDepth > maxDepth` — double-counts the landing level, so an item into a 2nd-level section (1 + 3 = 4 > 3) is always rejected although `moveNode`'s canonical check (`navigation.models.ts:200-230`) would accept it. Additionally the `»` button is hidden by `depth < maxDepth()-1` (`dnd-tree.component.html:122`). |
| 6 | Deep link with duplicate entry-point keys restores the wrong page | `applyModulePath` resolves the stored `path` by **bare contentKey** (`settings.component.ts:89-93`, `user-settings.component.ts:90-94`); the builtin catalog already has same-contentKey collisions in admin-settings (`settings:general`, `i18n-settings:general`). Path is built as `'/' + ep.contentKey` (`settings.component.ts:65-72`). |
| 7 | Drop into a section puts the item inside | The drop-into strip under each folder row appends into children (`onDrop` `into:true` branch, `dnd-tree.component.ts:259-286`); dropping on the folder row itself inserts before/after by dropPoint midpoint — neither matches the wanted default ("below") / Shift ("inside") contract. |
| 8 | Color picker lives in the right action row | Palette button at `dnd-tree.component.html:133-137` (items only), dialog in the consumer `shell-nav-editor.component.html:39-69`; avatar (`:76-82`) has no affordance. |
| 9 | Portal Navigation page gaps | Pinned strip is not collapsible (`portal-navigation.component.html:17-49`) while configured sections are (`:80-95`); no workspaces section; search/open-in-new-tab borrow `dashboard.*` i18n keys (`:12, :31, :64`). Nested sections DO render recursively already (`:52-97`). |
| 10 | Close button only visible with >1 area | `canCloseArea` returns false for single-leaf layouts (`tab-area.component.ts:43-56`), gating the X button (`tab-area.component.html:115-126`). |
| 11 | No root-level-apps rule | Every conversion path treats orphan/root items as first-class: `shellTreeFromResponse` pushes them to roots (`shell-nav-editor.component.ts:23-56`), save detaches unlisted items to `group_key = NULL` roots appended after root items (`ShellTreeService.java:283-294`), `buildTree` roots orphans (`tree.model.ts:60-67`). |
| 12 | Quick access is a manual pick list | Stored `sidebar.apps: string[]` in `navigation_user_settings` (V7), edited via palette+drag in `sidebar-nav-editor.component.ts:63-94`, default derived from all module contents (`NavigationUserSettingsService.java:188-197`). No usage tracking anywhere. |

---

## 1. Workstream W1 — Design system: tree chevron to the right

**Goal**: collapsible blocks in the settings / user-settings trees show their chevron on the RIGHT so
all row labels align at the same x offset.

- `portal/ui/src/app/shared/components/ds-tree/ds-tree.component.ts` — move the
  `.ds-tree-chevron` span from the first child position to **after** `label`/`meta` (before or after
  `action`, decide with a glance at consumers; chevron stays `flex-shrink:0`, click already
  `stopPropagation()`). No CSS change needed (`label` is `flex:1`, chevron trailing right-aligns).
- Keep the collapse animation (`transform: rotate(90deg)` via `.open`).
- Indentation stays `padding-left: 12px` per depth level (already inline in the template).
- Sweep consumers for regressions: `settings.component`, `user-settings.component`, registry tree,
  msg-center tree, any other `app-ds-tree` usage (grep `app-ds-tree`).

**Acceptance**: settings + user-settings trees render with right chevrons and aligned labels;
all existing vitest specs green; visual spot check via e2e dev stack optional.

---

## 2. Workstream W2 — DnD editor tree behavior

Component: `portal/ui/src/app/shared/components/dnd-tree/dnd-tree.component.{ts,html,css}`;
model: `portal/ui/src/app/core/navigation/navigation.models.ts`; specs: `dnd-tree.component.spec.ts`,
`navigation.models.spec.ts`.

### 2.1 Drop semantics: "below by default, Shift = inside"
- `onDrop` (`dnd-tree.component.ts:259-286`): for the row-list branch, when the hovered node is a
  **folder**: default → `moveNodeRelative(nodes, dragged.id, node.id, 'after', maxDepth)` (lands
  below the whole section, at the section's own level); when `event.event.shiftKey` →
  `moveNode(nodes, dragged.id, node.id, Number.MAX_SAFE_INTEGER, maxDepth)` (inside, last child).
  Item targets keep today's before/after midpoint behavior.
- Keep the thin drop-into strip as an explicit inside-target (`into:true` branch unchanged) — it is
  the visual affordance for "drop inside" without the keyboard.
- Update `dnd-tree.component.spec.ts`: folder-target default = after; shift branch = inside; item
  targets unchanged.

### 2.2 No root-level apps (UI half; server half in W3)
- Root strip (`html:10-21`, `{parentId: null, into:true, append:true}`) and empty-root
  (`html:24-33`) accept **folders only**; dragging an item over them does not open the list (add a
  `cdkDragStart`-time guard or a rejected-drop toast in `onDrop` — prefer rejecting in `onDrop`
  with a dedicated toast so the mechanic is testable).
- `moveOut` (`:296-307`): an **item** whose parent is a root-level folder must be rejected (it
  would land at root). Folders may still move to root.
- New i18n key `dnd.rootNotAllowed` ("Apps cannot live outside a section").
- Spec additions: item-to-root rejected with toast; folder-to-root allowed.

### 2.3 Fix moveIn (shift-right)
- Replace the hand-rolled check with the canonical one: attempt the move through
  `moveNode(...)` and let IT reject (`isSelfOrAncestor`, depth clamp). Button visibility becomes:
  a previous-sibling folder exists **and** `depthOf(tree, sibling.id) + subtreeDepthOf(node) ≤
  maxDepth` (matches `moveNode`'s math, no double count).
- Keep maxDepth at 3 (user decision). Document in the component doc comment: item into 2nd-level
  section = allowed; a populated 2nd-level section into a sibling section = rejected (would create
  level 4).
- Spec: shift-right on an item inside a 2nd-level section now succeeds; the `»` button is rendered
  in that state (today it is hidden/broken).

### 2.4 Color affordance on the avatar
- Wrap the avatar span (`html:75-82`) with a hover overlay: pencil icon appears on row hover
  (pattern from `.ds-tree-action { opacity:0 }` / `:hover { opacity:1 }`,
  `components.css:1190-1207`); click → `colorPick.emit(node)` + `stopPropagation()`.
- Remove the palette button from the actions row (`html:133-137`) and the `showColorPicker() &&
  node.kind === 'item'` condition — the avatar pencil is available for **items and folders** when
  the input is on.
- New CSS in `dnd-tree.component.css` (layout only) + token reuse; no raw hex.
- Consumer: `shell-nav-editor.component.html:39-69` dialog gains an **"Apply to all"** checkbox
  (default **checked**) rendered only when the target is a folder: applies the picked color to the
  folder AND every descendant item (new `setNodeColorDeep(nodes, id, color)` in
  `navigation.models.ts` — recursive rebuild like `setNodeColor` at `:94-102` + spec). Unchecked →
  colors only the folder. Items keep plain apply.
- The pinned-apps editor keeps colors read-only (no change).

### 2.5 Rename on section-label click
- `dnd-tree.component.html:96`: folder label click → `startRename(node, $event)` (same path as the
  pencil at `:144`). Item labels stay non-editable. Keep rename disabled while dragging
  (`dropListsDisabled` interplay already exists).

**i18n adds**: `dnd.applyToAll` ("Apply to all"), `dnd.rootNotAllowed`.

---

## 3. Workstream W3 — Backend: section colors, seeded module/Core trees, no-orphans

Package: `portal/server/src/main/java/com/crosshubber/portal/modules/navigation/shelltree/`,
`groups/`, `registry/modulecontents/` (DTO only), `bootstrap/Reconciler.java`.

### 3.1 Section color persistence
- Migration **`V38__navigation_group_colors.sql`**: `ALTER TABLE navigation_groups ADD COLUMN
  color TEXT NULL;` (no backfill needed — fallback hash color is a render concern only for items;
  sections without color render neutral).
- `NavigationGroupEntity` + `NavigationGroupsService.toOutput` + `NavigationGroupDto` add `color`.
- `ShellTreeService.saveShellTree`: accept `color` on group nodes (validate `#rgb|#rrggbb`, reuse
  the item-color validation), persist it.
- Runtime groups in `/api/config` inherit the field via `NavigationGroupDto::fromEntity` — no
  change to `ShellConfigService` filtering.
- Backend unit test: save with color → round-trips; invalid color → 400.

### 3.2 No orphan items on save (server half)
- `NavigationValidationService.validateShellTree` (lines ~161-199): every item must reference a
  group present in the payload (`groupKey` non-null) — root-level items → validation error.
- `saveShellTree` unlisted-item handling (`:283-294`): instead of clearing `group_key`, re-parent
  unlisted items into their **module section** (`mod-<moduleKey>`, created if missing) — or Core
  when the item is a shell element (see 3.3). No item ever ends up ungrouped.
- Update `ShellTreeServiceTest` (or its equivalent) accordingly.

### 3.3 Auto sections: Core + one section per module (Reconciler pass)
New `Reconciler` pass (idempotent, insert-if-absent, runs at every boot next to `reconcileBuiltins`):

For each group-capable category (`applications`, `admin-settings`, `user-settings`):
1. **Core group**: ensure a group with reserved key `core` exists (seed names: `Core apps` for
   applications, `Core settings` for the two settings trees). Reserved-key namespace shared with
   `mod-<moduleKey>` — document in `Keys`/`common` if a constant lands there.
2. **Module sections**: for every module having contents in the category, ensure a group
   `mod-<moduleKey>` (seed name = module name). Inserted with `sort_order = max+10` per category.
3. **Assignment of ungrouped contents** (only rows with `group_key NULL` or pointing at a missing
   group): navigation-module contents → `core` (shell elements: `navigation:portal` in
   applications; the four admin pages in admin-settings; `navigation:pinned`/`navigation:sidebar`
   in user-settings); every other content → its own `mod-<moduleKey>`.
   Admin-set groupings are **never** re-parented (preserves D4).
- Effect: fresh install boots with a fully initialized tree; a module added later (registry) gets
  its section + contents on next boot; mid-runtime additions are covered by the render fallback
  below.
- Keep the pass cheap: one `findAll` on groups + one on contents, in-memory grouping; upsert only
  missing rows.

### 3.4 Editor GET parity
- `ShellTreeService.shellTreePayload`: also exclude items whose **module is inactive** (join
  module rows; `active=FALSE` module ⇒ item dropped), matching the runtime module gate. Hidden
  groups/items and role-restricted rows stay visible to the admin (they are the point of the
  editor) — annotated in W10.2.

### 3.5 Render fallback for stragglers (frontend, cheap)
- `portal/ui/src/app/portal-core/layout/sidebar/tree.model.ts` `buildTree` (`:60-67`): orphan
  entry points (null/unknown groupKey) no longer go to roots; they are grouped under a **virtual
  section keyed by `moduleKey`** appended after real groups (label = moduleKey). This guarantees
  "no orphans" at render time even before the next boot pass. Used by all three trees
  automatically.

**i18n adds**: none (seed names are data, admin-renameable).

---

## 4. Workstream W4 — Sidebar layout, home filtering

`portal/ui/src/app/portal-core/layout/sidebar/sidebar.component.{ts,html,css}`.

### 4.1 Flex layout fix (fixes expanded-pinned bug + overflow + chat docking)
Restructure the rail column:
```
header (shrink-0)
Home button (shrink-0)
<div class="flex-1 min-h-0 overflow-y-auto">   ← single scroll region
  pinned nav (remove overflow-y-auto)
  Recently used nav (W6)
  workspaces nav
</div>
chat dock button (shrink-0)                    ← docked bottom by layout, no flex-1 spacer
```
- All sections above the scroll region get `shrink-0`; the fresh-user empty hint stays inside the
  scroll region (expanded-only rendering unchanged).
- Collapsed mode unaffected (tiles + mini-rules; chat button visible at bottom).
- Spec: sidebar.component.spec — assert structure/class contract (scroll region exists, chat dock
  outside it).

### 4.2 Hide the home app from the sidebar
- Resolve the home ref (`settingsService.homeApp()` / `wb.getHomeAppRef()` — same chain as the
  workbench, `workspaces.store.ts:132-158`).
- Filter: pinned-tree rendering skips nodes resolving to the home ref (keep the tree shape — skip
  only leaf items); Recently used skips it too. Rationale comment: the sidebar has a dedicated
  Home button already.
- The Add-App modal already filters home (`tab-area.component.ts:35-41`) — unchanged.

---

## 5. Workstream W5 — Workarea toolbar + tab color

`portal/ui/src/app/portal-core/workarea/tab-area.component.{ts,html}`.

1. **Close button always visible**: drop the `@if (canCloseArea())` gate at `tab-area.component.html:115-126`.
   Keep `canCloseArea` as a *disabled* affordance: when false (single primary area holding only
   Home) the button renders disabled; per-area-role confirm flow unchanged (primary →
   `tabs.closeTabsInAreaConfirm`, non-primary → `tabs.closeAreaConfirm`; empty area closes without
   confirm).
2. **Button order** (edit tools panel, `html:84-148`): **Add app → Hide/Show Home → divider →
   Split vertical → Split horizontal → Close area**. The Hide-Home gate stays
   `isPrimary() && !isSingleArea()`.
3. **Tab color fallback**: dot binding becomes `[style.background]="avatarBg(tab.content)"`
   (`avatarBg` already exists in this component at `:160-163`).
4. **Snapshot refresh**: `WorkbenchService` gains `refreshTabContents()` — on config reload
   (`configService.notifyChanged` → Shell re-fetch → `wb.setModuleContents`), update open tabs'
   content snapshots (name/color/type) in place by `moduleContentId`. Home fixture already
   refreshes (`workspaces.store.ts:168-183`); generalize it.

**Spec**: tab-area spec — toolbar order, close visibility/disabled, dot fallback; workspaces.store
spec — refreshTabContents.

---

## 6. Workstream W6 — "Recently used" replaces Quick access

### 6.1 Backend (`modules/navigation/usersettings/`)
- Effective user-settings document becomes:
  ```
  sidebar: { showPinned, showWorkspaces, showRecent (default true), recentLimit (default 10, int 1..50), recentApps: string[] }
  sidebarExpanded: string[]
  ```
- `NavigationUserSettingsService`:
  - `defaultUserSettings` (`:188-197`): drop `apps` + `buildDefaultSidebarApps` (remove the
    helper in `NavigationValidationService.java:219-234`); add `showRecent`/`recentLimit`/empty
    `recentApps`.
  - `validatePartial` (`:94-151`): accept `sidebar.showRecent` boolean, `sidebar.recentLimit`
    integer 1..50, `sidebar.recentApps` array ≤100 refs matching `Keys.REF_RE` (known-ref check
    NOT applied — refs may point at since-removed apps and must not fail persistence);
    `sidebar.apps` no longer accepted (ignored like any unknown key).
- Unit tests for the service updated.

### 6.2 Client recording
- `NavigationStore`: `recordRecent(ref)` — dedupe (move-to-front), cap at `recentLimit`, debounce
  (~800ms) `saveSidebar({ recentApps })`; guards: only refs that resolve to an applications
  entry point at render time.
- Hook: `WorkbenchService.openApp` (`workspaces.store.ts`) calls `nav.recordRecent(id)` on every
  app activation (both fresh-open and activate-existing paths). Home app is NOT recorded.

### 6.3 Sidebar
- Replace the Quick access section (`sidebar.component.html:147-176`) with **"Recently used"**:
  gated `sidebarSettings().showRecent`; list = `sidebar.recentApps` refs → resolve against the
  applications input, filter home ref (W4.2) and unresolvable refs, slice(0, recentLimit).
- Same row markup as today (icon square via `avatarBg`, unread badge, active state).

### 6.4 User-settings editor (`sidebar-nav-editor.component.*`)
- Remove the quick-access palette + reorder list (`:50-94` UI, `addPick/removePick/onReorder/
  persistApps`).
- Add: "Recently used" show/hide toggle + limit number input (1-50, clamped), autosaving via
  `saveSidebar`.
- Section visibility toggles (showPinned / showWorkspaces) unchanged.

**i18n**: add `sidebar.recentlyUsed` ("Recently used"), `navigation.sidebar.recentlyUsedHint`,
`navigation.sidebar.recentLimit`; remove `sidebar.quickAccess*` usages (key rows become dormant in
existing DBs — acceptable, see §9). Update the fresh-user empty hint copy if it references quick
access.

---

## 7. Workstream W7 — Split navigation settings into 4 pages (+ General merge)

### 7.1 Backend `EmbeddedCatalog.java`
- Navigation module entries become (all `admin-settings`, roles `portal-navigation-edit`,
  color `#10b981`):

| contentKey | name | loadPath | sortOrder |
|---|---|---|---|
| `general` | "General" | `navigation-general` | 7 |
| `apps` | "App navigation" | `navigation-apps-nav` | 8 |
| `settings-tree` | "Settings navigation" | `navigation-settings-nav` | 9 |
| `user-tree` | "User settings navigation" | `navigation-user-nav-tree` | 10 |

  (Keys deliberately avoid the retired-cleanup keys `settings-nav`/`user-nav`.)
- Remove the old `portal-nav` entry (`navigation:portal`, loadPath `navigation-portal`).
- Settings module: remove the `general` entry (`settings:general`, loadPath
  `portal-general-settings`) — its only reader is the merged General page.
- Reconciler retired-cleanup lists (`Reconciler.java:336-355`) add:
  `("navigation", "portal-nav")`, `("settings", "general")`.

### 7.2 Frontend
- Delete `portal-nav-settings.component.*` (4-tab page).
- New `modules/navigation/` components (all standalone, lazy via `embedded-modules.ts`):
  - **`nav-general-settings.component`** (loadPath `navigation-general`): merged content of
    `portal-general-settings.component` (move/delete that component from `modules/settings/`) —
    Home-application dropdown (`settingsService.updateHomeApp` → `wb.setHomeApp` →
    `ensureHomeTab`) **plus** four toggles: `pinnedAppsEnabled`, `workspacesEnabled`,
    `pinnedSectionEnabled`, `workspacesSectionEnabled` (new flag, 7.3) — all via
    `nav.saveFeatures`.
  - **`nav-apps-nav.component`** — thin wrapper hosting `<app-shell-nav-editor
    category="applications" />` (the old App Navigation tab minus the pinned-section card, which
    moved to General).
  - **`nav-settings-nav.component`** — wrapper for `category="admin-settings"`.
  - **`nav-user-nav.component`** — wrapper for `category="user-settings"`.
- `embedded-modules.ts`: add the four load-path entries; remove `portal-dashboard` (W9) — update
  the embedded-catalog parity spec to match the new backend catalog.
- New pages land in the admin-settings tree under **Core settings** via the W3 seeding pass
  (groupKey NULL at insert → assigned by Reconciler; on dev they can also be dragged by admin).

### 7.3 New feature flag `workspacesSectionEnabled`
- `FeatureFlagsDto` (boolean), `NavigationSettingsService.DEFAULT_SETTINGS` (default true), PUT
  validation (`NavigationFeaturesController`), `NavigationStore.features` signal, General-page
  toggle. Runtime consumer: W8.1. Tenant policy: navigation settings are admin-owned unless
  overlaid — no tenant.json change required (defaults true).

**i18n**: page names/titles/descriptions `navigation.general.*` (title, description, homeApp label
reuse, hints), `navigation.appsNav.*`, `navigation.settingsNav.*`, `navigation.userNav.*`; the
feature-switch keys `navigation.features.pinnedApps/workspaces` and
`navigation.portal.pinnedSection` are reused/moved as needed.

---

## 8. Workstream W8 — Portal Navigation app page

`portal/ui/src/app/portal-core/modules/navigation/portal-navigation.component.{ts,html,css}`.

1. **Workspaces section** (port of the dashboard's "My workspaces", `dashboard.component.html:17-52`):
   grid of workspace cards — click opens (`wb.loadWorkspace(name, false)`), delete button
   (`wb.deleteWorkspace`), color avatar (`ws.color || gradient`), `timeAgo(ws.savedAt)` — fed from
   the **live** `wb.workspaces()` signal (fixes the dashboard's staleness bug; no snapshot copy).
   Gate: `features().workspacesEnabled && features().workspacesSectionEnabled` (new flag from W7.3).
   Collapsible via the same `collapsed` Set as other sections.
2. **Pinned apps section collapsible**: header chevron + `toggleCollapsed('pinned')`, matching the
   configured-section pattern (`portal-navigation.component.html:80-95`). Stays hidden entirely
   when empty/feature-off.
3. **Multi-level sections**: recursion already exists (`sectionTpl`, `:52-97`); polish sub-section
   headers (slight indent / stronger label hierarchy) and verify with the W3-seeded trees
   (Core > module sections). Empty groups already filtered in `mapNodes`.
4. **i18n migration**: replace `dashboard.search.placeholder` → `navigation.app.searchPlaceholder`
   and `dashboard.action.openInNewTab` → `navigation.app.openInNewTab` (needed before W9 deletes
   `dashboard.*`).
5. Ensure the page's section list also picks up group `color` (W3.1) for section header accents
   (small color dot on the header) — optional but consistent with section colors.

---

## 9. Workstream W9 — Delete the dashboard module

Goal: remove the `portal-dashboard` builtin entirely (its page is fully superseded by the Portal
Navigation page after W8).

- **Backend**:
  - `EmbeddedCatalog.java`: remove the `portal-dashboard` module (`:30-45`).
  - `Reconciler` retired-cleanup (`:324-355`): add module key `portal-dashboard` (builtin-guarded
    removal, same pattern as `llm-providers`/`ai-assistant`) and content
    `("portal-dashboard", "main")`.
- **Tenant configs**: remove `"portal-dashboard": true` from `modules.builtin` in
  `config-management/tenants-config/_default/tenant.json`, `config-management/tenants-config/e2e/tenant.json`,
  and `portal/server/src/test/resources/tenant-config-policy/_default/tenant.json`
  (unknown keys in the builtin map fail boot — must all three be updated).
- **Frontend**:
  - `embedded-modules.ts:11`: remove `'portal-dashboard'`.
  - Delete `portal/ui/src/app/portal-core/modules/home-dashboard/` (component + spec).
  - `workspaces.store.ts`: remove `FALLBACK_HOME_REF` (`:14, :157`); unresolvable home ⇒
    `ensureHomeTab` hides the Home tab (guard against null home content).
- **i18n catalog** (`portal/server/src/main/resources/i18n-catalog.json`): delete the
  `dashboard.*` block entries no longer referenced (`title`, `section.*`, `empty.*`, `time.*`);
  the two keys consumed by portal-navigation were renamed in W8. Removed keys leave dormant rows
  in existing DBs (documented; the seed is insert-if-absent).
- **Backend tests**: swap `portal-dashboard` for another guaranteed builtin (`navigation` or
  `settings`) in `ModulesServiceTest` (409 guard), `BuiltinManifestSynthesizerTest` (`:135`),
  `NavigationAgentToolsTest` (`homeApp` value → `navigation:portal`),
  `MsgCenterIngestionIT` / `MsgCenterEmailIT` / `MsgCenterAgentToolsTest` (module-key guards).
- **e2e**: `test/e2e/specs/admin/registry-content.spec.ts:34` (dashboard builtin assertion) →
  assert on another builtin; `test/e2e/specs/auth/limited-user.spec.ts:39` (homeApp PUT with
  `portal-dashboard:main`) → `navigation:portal`. Sweep e2e for other "Dashboard" references.

---

## 10. Workstream W10 — Settings path fix + editor/shell alignment

### 10.1 Module-scoped settings paths
- `settings.component.ts` + `user-settings.component.ts`:
  - `selectEntry` writes `coordinator.navigateFromModule(MODULE_KEY, '/' + ep.moduleKey + '/' +
    ep.contentKey)`.
  - `applyModulePath`: parse `/{moduleKey}/{contentKey}` and match on both; **back-compat**: a
    single-segment path resolves by bare `contentKey` (then rewrites the URL to the scoped form).
  - `path` segments are already kebab-validated server keys — no encoding concerns.
- Tests: coordinator spec + settings/user-settings component specs for scoped restore, legacy
  restore, and the duplicate-contentKey case (`settings:general` vs `i18n-settings:general`).
- URL grammar doc comment in `core/history/nav-state.ts` updated (`path` = module-internal path).

### 10.2 Editor vs settings-shell alignment (bug #4 remediation)
- Server: editor drops inactive-module items (W3.4).
- Editor rows annotated so remaining deltas are explainable:
  - hidden rows already dim (`dnd-node-hidden`) — add a small "hidden" meta badge on the row
    (dnd `meta` slot or title tooltip);
  - role-restricted groups get a lock/roles badge (data already present in `NavigationGroupDto.roles`);
- README design notes: document the deliberate delta (admin editor = full tree incl. hidden/role
  rows; runtime shell = per-user filtered) so future agents don't "fix" it again.
- Acceptance: with the dev tenant, the editor tree structure equals the settings shell structure
  node-for-node except for rows visibly marked hidden/role-restricted.

---

## 11. Data-model summary

| Store | Change | Migration |
|---|---|---|
| `navigation_groups` | + `color TEXT NULL` | **V38** |
| `navigation_settings` (jsonb singleton) | + `workspacesSectionEnabled` (default true) | none (code default) |
| `navigation_user_settings` (jsonb per user) | `sidebar.apps` → removed; + `showRecent`, `recentLimit`, `recentApps` | none (code defaults; legacy `apps` ignored) |
| `registry_module_contents` | unchanged (item colors already there) | — |
| Builtin rows | `settings:general`, `navigation:portal-nav`, `portal-dashboard`, `portal-dashboard:main` retired; 4 new navigation admin entries | Reconciler cleanup/insert at boot |

## 12. i18n inventory

- **New**: `dnd.applyToAll`, `dnd.rootNotAllowed`, `sidebar.recentlyUsed`,
  `navigation.sidebar.recentlyUsedHint`, `navigation.sidebar.recentLimit`,
  `navigation.general.*` (title/description/hints), `navigation.appsNav.*`,
  `navigation.settingsNav.*`, `navigation.userNav.*`, `navigation.app.searchPlaceholder`,
  `navigation.app.openInNewTab`, `navigation.app.workspaces`, `navigation.app.workspacesEmpty`,
  workspaces-section toggle copy.
- **Removed from catalog**: `dashboard.*` module-specific keys (title, section.*, empty.*, time.*).
- **Dormant after rename**: `sidebar.quickAccess*`, old `dashboard.search.placeholder` /
  `dashboard.action.openInNewTab` rows in existing DBs (insert-if-absent seeds — document in the
  PR; no cleanup migration unless requested).

## 13. Execution order & dependencies

```
W1 (ds-tree chevron)            — independent, small
W2 + W3 (tree model + backend)  — build together (shared semantics: colors, no-orphans)
W10.1 (settings paths)          — independent, small
W5 (toolbar + tab color)        — independent, small
W4 (sidebar layout)             — before W6 sections land
W6 (recently used)              — needs W4 layout + W6.1 backend
W9 (dashboard deletion)         — after W8's i18n renames
W7 (settings split)             — needs W3 seeding (new pages land in Core)
W8 (portal page)                — needs W7.3 flag; after W9 decision-wise (i18n renames first)
W10.2 (tree alignment)          — last polish, after W3.4
```

## 14. Verification

- Per workstream: targeted vitest specs (models, dnd-tree, shell-nav-editor, sidebar, tab-area,
  workspaces store, coordinator) and backend unit tests (ShellTreeService, user-settings service,
  feature flags).
- Full gate after each milestone: `cd portal/server && mvn spotless:apply && mvn verify`
  (Testcontainers — Docker must run) and `cd portal/ui && npm test && npm run build`
  (budgets enforced).
- Dev-stack manual pass (`docker compose up --build -d`, login `dev/dev`): tree seeding shape,
  drag/shift drops, avatar color + apply-to-all, section rename-on-click, sidebar overflow/chat
  dock/expanded pinned, home hidden, toolbar order, close button, workspaces + pinned sections on
  the Portal Navigation page, 4 settings pages, scoped deep links, recently used recording.
- e2e: update + run `registry-content.spec`, `limited-user.spec` (W9); add a smoke spec for the
  settings-tree/shell parity if cheap.

## 15. Risks & notes

- Reconciler boot pass is fail-fast and runs every boot — keep the new pass O(groups+contents) and
  strictly insert-if-absent; invalid data must fail boot loudly (same as today).
- The retired-cleanup list grows (`settings:general`, `navigation:portal-nav`,
  `portal-dashboard`, `portal-dashboard:main`) — same "remove once every install has booted past
  the change" note as the existing entries (AGENTS.md).
- Depth cap stays 3 by decision; "2nd-level section into sibling section" remains rejected — the
  toast copy (`dnd.moveRejected`) is the explanation users see.
- Portal Navigation page becomes the home app of record — keep its i18n keys stable before/after
  the dashboard deletion to avoid a broken home screen mid-rollout (W8 before W9).
- `embedded-modules.ts` parity spec is the contract test for the catalog reshuffles (W7/W9).

## 16. Deferred follow-ups (not in scope)

- Persisting section collapse state for the Portal Navigation page (today local-only, matching
  other sections).
- Sidebar showing the full application tree (today: home + pinned + recently used + workspaces).
- Cleaning the dormant i18n rows via an update pass.
- Removing the older retired-cleanup entries once long-lived installs have booted past them
  (AGENTS.md backlog).
