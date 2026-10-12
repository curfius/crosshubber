---
status: archived
domain: navigation
summary: "Navigation trees unification, workspaces relocation, avatar colors — 7 phases shipped 2026-10-09"
---

# Navigation Trees, Workspaces Relocation & Avatar Colors

Topic plan for the 2026-10-09 navigation pass: unify all portal navigation onto the
shell-tree model, fix workarea/dnd-tree behavior gaps, fold workspaces into the
navigation module, and give entry points deterministic avatar colors. Approved and
shipped 2026-10-09 (Phases 1–7). Related: `plan/UX_PLAN.md` (shipped-since note),
`plan/archive/NORMALIZATION_PLAN.md` (registry_/navigation_ renames predecessor),
`plan/archive/TEST_PLAN.md` (regression pack).

## Phases (all implemented)

| Phase | Scope | Shipped |
|---|---|---|
| 1 | Workarea fixes: split-divider drag direction, tab DnD outside edit mode, close-area flows, home-tab visibility persisted per saved workspace | ✓ |
| 2 | Dnd-tree fixes: append-on-folder-into drop, rejection toast for invalid moves, tabindex/focus styling with spec | ✓ |
| 3 | Unify navigation trees: migrate `navigation_layout` → shell-tree model (V33–V35), fold legacy `settings` category into `admin-settings`, category gate on `NavigationShellTreeController`, tree editors for all three categories, UI fixture fixes | ✓ |
| 4 | Registry reconciliation: install/uninstall appends contents at the tail (sort_order append), uninstall prunes `module_settings` orphans (V36) | ✓ |
| 5 | Avatar colors: deterministic hash fallback everywhere avatars render, explicit picker in the tree editor, color round-trip through `ShellItemPayload` | ✓ |
| 6 | Workspaces relocated into the navigation module (server package move, UI store move, agent-tool attribution) | ✓ |
| 7 | i18n orphan cleanup (V37), docs, full verify | ✓ |

## Key decisions (user-approved)

- **Trees as the visibility layer**: one model for applications/admin-settings/user-settings;
  sections, hidden flags, sort order and entry-point colors live on tree rows. `navigation_layout`
  was migrated and dropped.
- **Category fold**: `settings` → `admin-settings` (`ModuleContentCategory.parse`), API `@Pattern`s
  reject the legacy value with 400.
- **Registry installs add contents visible/unassigned at the tail**: new contents get
  `max(sort_order)+10` when created without an explicit order, and existing rows keep their order
  (COALESCE semantics) so registry-initiated upserts never reorder a curated tree.
- **Avatar color = deterministic hash fallback + explicit picker** (`AVATAR_COLORS`, FNV-1a `avatarColor(ref)`;
  picker in the shell-nav editor via dnd-tree `colorPick`; registry keeps its existing picker).
- **Home toggle persists per saved workspace** (Phase 1; `home_visible` column V33).
- **Workspaces live under the navigation module**. HTTP paths unchanged (`/api/workspaces`),
  agent tool `listWorkspaces` now attributed to `navigation` (gated by module active state,
  surfaces in the navigation manifest as `list_workspaces`; previously platform-global).

## Deviations from the original draft (with rationale)

1. **UI workspaces store path** — moved to `src/app/core/workspaces/workspaces.store.ts`, not into
   `portal-core/modules/navigation/`: `core/bridge` imports the store, and core must not depend on
   portal-core. All 21 importer files updated.
2. **`module_settings` prune strategy** — service-level delete in `ModulesService.remove()` plus data
   migration V36 instead of an FK: the Reconciler runs last among ApplicationRunners while the SMTP
   config seed runner (`@Order(200)`) runs first on fresh DBs; an FK would break fresh-tenant SMTP seeding.
3. **`navigation.portal.appNavTab` was missing from the seed catalog** — the Phase 3 template rename
   (`layoutTab` → `appNavTab`) predated the catalog edit, so the tab label rendered as a raw key on
   installs that had seeded `layoutTab`. Fixed in the catalog and V37 (delete 4 retired keys incl.
   `navigation.portal.newSection/treeTitle/empty/layoutTab`, insert `appNavTab`, bump
   `content_version`).
4. **dnd-tree color picker as an actions-row button** (`showColorPicker` input + `colorPick` output)
   instead of repurposing the row `itemClick`: row clicks also fire after drags, so a click-to-pick
   would open the palette after every drag of an item.

## Verification

- `mvn verify` green: 328 server tests (incl. `NavigationFeaturesControllerTest`,
  `NavigationShellTreeControllerTest`, `ModuleContentsServiceTest`, `NavigationGroupsServiceTest`,
  `ModulesServiceTest`, extended `ShellTreeServiceTest` with color save/preserve/clear/reject,
  relocated `WorkspacesAgentToolsTest` now asserting `navigation` attribution).
- `npm test` green: 247 tests / 27 files (incl. `tree.model.spec.ts`, avatar-color helpers,
  `setNodeColor`, dnd `colorPick` emission, prefetched store specs after move).
- `npm run build` EXIT 0 (one pre-existing, untouched warning: `markdown-editor.component.css`
  style budget).
- Live dev stack: migrations V33–V37 applied, `/healthz` + `/api/branding` 200, no boot errors;
  i18n labels for the new keys present in all four languages; orphan `navigation.portal.*` rows
  deleted; `content_version` bumped so clients drop cached retired keys.

## Backlog pointers / deferred

- E2E coverage of the tree editor + avatar picker in `test/e2e/` (msg-center coverage note in
  `plan/archive/MESSAGE_CENTER_PLAN.md` applies equally here).
- The tab-area color dot consumes `tab.content.color` only (no fallback) — deliberate: small
  category dot, not a letter avatar.
- UX_PLAN Phase 2/3 items (shared visible-apps filter, breadcrumbs, command palette) remain open.
