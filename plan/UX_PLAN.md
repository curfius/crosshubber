# Portal UX Plan — Navigation & User Journeys

Roadmap for optimizing portal navigation UX across both personas (admin `dev`,
limited `devuser`) and all six target journeys: landing/orientation, module
browsing & launching, settings & admin, workspaces, global search/quick actions,
and cross-area wayfinding.

Status: **Phase 1 implemented (2026-09-22)**. Phases 2–3 are recommendations
awaiting a go decision.

## Design principles

- One canonical pattern per concern: one home concept, one launcher filter rule
  set, one chat entry, DB-driven entry points over hardcoded chrome where roles
  must apply.
- Every dead end gets feedback (toasts, disabled states, honest empty states).
- API changes are Crosshubber-owned: any `/api/*` response change needs its tests
  updated and `mvn verify` + `npm test` green (there is no parity harness).
- New user-facing strings are i18n keys only (no hardcoded English in
  templates); labels seed from `i18n-catalog.json` (insert-if-absent reconciler).

---

## Phase 1 — Quick wins (implemented)

| # | Item | Journeys | Disposition |
|---|---|---|---|
| 1 | `settings.general.placeholderHint` wrong-path copy | Settings & admin | **Verified dead**: key is referenced by no template — General settings now renders real controls (`portal-general-settings.component.html`). No copy change needed; key left as orphaned seed (removal = backlog). |
| 2 | `tabs.noTabsHint` copy ("open from sidebar" while Home is the real launcher) | Landing | Copy updated in catalog (4 languages) + `V24__ux_copy_navigation_labels.sql` for existing installs (seed is insert-if-absent) → "Open an app from Home or the sidebar to get started". |
| 3 | Unknown `?app=` deep link fails silently (console only) | Wayfinding, browsing | New `ToastService` (`core/toast/`) + toast rendered by Shell; `NavigationCoordinator` shows `nav.unknownApp` toast on cold-load unknown/link-type app keys. Spec extended. |
| 4 | Workspace rename/delete handlers dead (unbound in Shell) | Workspaces | Sidebar workspace rows now expose rename (inline input: Enter commit / Esc-blur cancel) and delete buttons; outputs wired to existing Shell handlers. **Also fixed** `WorkbenchService.renameWorkspace`: old implementation saved the *current* view state (data loss for non-active workspaces); now GETs the target snapshot and PUTs the new name by id (server renames in place). Store specs added. |
| 5 | Settings gear silently no-ops when `settings:settings-shell` EP missing | Settings & admin | `settingsAvailable` computed → button disabled with `shell.toolbar.settingsUnavailable` tooltip. |
| 6 | Sidebar Home button never highlights while the Home tab is active | Wayfinding | Shell computes `homeTabActive` (via `wb.isHomeTab`); Home row active state = home tab active OR (no workspace AND no app key). |
| 7 | Fresh sidebar is blank (pinned + quick-access both opt-in) | Landing/first-time | Empty-state CTA "Find apps on Home" (`sidebar.emptyApps`) shown when no pinned section and no quick-access picks (expanded rail only); emits `homeClick`. |

New i18n keys (×4 languages: en-GB, pt-PT, fr-FR, es-ES): `nav.unknownApp`,
`sidebar.emptyApps`, `sidebar.rename`, `shell.toolbar.settingsUnavailable`.
Changed: `tabs.noTabsHint`. Existing keys reused: `shell.toolbar.deleteWorkspace`,
`sidebar.home`, `common.*`.

Phase 1 files: `plan/UX_PLAN.md`, `core/toast/toast.service.ts`,
`shell.component.{ts,html}`, `sidebar.component.{ts,html}`,
`toolbar.component.{ts,html}`, `navigation-coordinator.service.ts` (+spec),
`workspaces.store.ts` (+spec), `i18n-catalog.json`, `V24__*.sql`, `AGENTS.md`.

Known follow-ups from Phase 1 (backlog):
- Delete confirmation consistency (sidebar/toolbar/dashboard currently confirm nowhere).
- Orphaned i18n keys (`settings.general.placeholder*`, `settings.general.favorites*`,
  `sidebar.apps`, `sidebar.services`) — candidates for removal.
- `i18n-catalog.json` is a generated seed with a removed regen script — Phase 1
  hand-edited it; safe to edit now (the catalog is owned by this repo; `contentVersion`
  is bumped by the reconciler).

---

## Phase 2 — Structural fixes (proposed)

| # | Item | Journeys |
|---|---|---|
| 8 | Shared "visible apps" filter helper — one rule set for Portal Navigation, Dashboard, sidebar, Add-app (each filters differently today) | Module browsing |
| 9 | Breadcrumbs in workarea: workspace → app → module `?path=` (none exist today) | Wayfinding |
| 10 | Close-tab available outside edit mode (split/add stay edit-only; loading a workspace currently hides close) | Workspaces |
| 11 | Quick Chat flyout opens via `ai-hub:quick-chat` entry point (today hardcoded in Shell; roles/active flags never consulted; EP orphaned) | Browsing + feeds AGENTS `chat`/`quick-chat` merge backlog |
| 12 | Hide Module registry from non-admins (server-side; currently visible to `devuser` behind a read-only banner) — **touches `/api/config`** | Settings & admin |
| 13 | Consolidate workspace CRUD affordances (sidebar menu = open/rename/delete; delete confirms) | Workspaces |

## Phase 3 — Larger rocks (proposed, own design pass)

| # | Item | Journeys |
|---|---|---|
| 14 | Command palette (Ctrl-K): apps + workspaces + settings sections — Add-app modal is the closest precedent but is edit-mode- and group-gated | Search + landing + browsing |
| 15 | Merge/demote Dashboard ↔ Portal Navigation (two "home" concepts; four competing launchers) | Landing + browsing |
| 16 | Merge chat / quick-chat duplicated logic (existing AGENTS.md backlog item) | Browsing |

## Open findings worth tracking

- **Reconciler fresh-boot label seed bug (fixed in Phase 1)**: the language
  insert loop never added fresh codes to `existingLanguages`, so the label pass
  skipped every language on a first-ever boot ("0 new labels"; labels — including
  the new Phase 1 keys — only appeared from the second boot on). Fixed by tracking
  the insert in the set (`Reconciler.reconcileI18n`).
- `w/:name` route param is never read by Shell — all nav state lives in
  `UrlSyncService`/coordinator; wildcard `**` → `''` swallows mistyped paths
  silently (consider a toast alongside it).
- Workspace rename has name-conflict retry ("name (n)") server-side; the PUT
  response returns the *requested* name — list refresh shows truth.
- No breadcrumbs, no global command palette, no toast system existed before Phase 1.
