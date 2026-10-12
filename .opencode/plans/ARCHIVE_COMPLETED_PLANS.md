# Archive Completed Plans — Plan

## Goal
Move plans that are fully completed (all items done or explicitly dropped) to `plan/archive/`, keeping only active/ongoing work in `plan/`.

## What to Archive

| Source | Destination | Rationale |
|--------|-------------|-----------|
| `.opencode/plans/SAMPLE_SENDER_UX_PLAN.md` | `plan/archive/SAMPLE_SENDER_UX_PLAN.md` | Work committed (`e05babc`), all acceptance criteria verified |
| `plan/AI_PLAN.md` | `plan/archive/AI_PLAN.md` | All phases completed (A, B, C1–5, D, F, G3, H) or dropped (A4, F4, G2) / extracted (E → separate plan) |
| `plan/NAV_WORKSPACES_PLAN.md` | `plan/archive/NAV_WORKSPACES_PLAN.md` | All 7 phases shipped 2026-10-09, `mvn verify` + `npm test` green |
| `plan/UX_PLAN.md` (Phase 1 only) | `plan/archive/UX_PLAN_PHASE1.md` | Phase 1 quick wins implemented 2026-09-22; Phases 2–3 remain proposed |

## What Stays in `plan/` (Active / Not Started)

| File | Status | Notes |
|------|--------|-------|
| `plan/UX_PLAN.md` | **Keep (trimmed)** | Retain only Phases 2–3 (structural fixes + larger rocks) |
| `plan/WORKFLOWER_PLAN.md` | **Keep** | Draft-to-later, prerequisites met but not activated |
| `plan/EXTERNAL_MCP_INTEGRATION_PLAN.md` | **Keep** | Approved, parked until external MCP server exists |

## Steps

1. **Move files to archive:**
   - `mv .opencode/plans/SAMPLE_SENDER_UX_PLAN.md plan/archive/SAMPLE_SENDER_UX_PLAN.md`
   - `mv plan/AI_PLAN.md plan/archive/AI_PLAN.md`
   - `mv plan/NAV_WORKSPACES_PLAN.md plan/archive/NAV_WORKSPACES_PLAN.md`

2. **Extract UX Phase 1 to archive:**
   - Create `plan/archive/UX_PLAN_PHASE1.md` with Phase 1 content (lines 31–52 + related i18n/migration notes)
   - Edit `plan/UX_PLAN.md` to retain only the header, design principles, Phase 2 table, Phase 3 table, and open findings

3. **Update cross-references:**
   - Check `AGENTS.md` for references to archived plans → update paths to `plan/archive/...`
   - Check `plan/UX_PLAN.md` (retained) for references to AI_PLAN/NAV_WORKSPACES → update to archive paths
   - Check `plan/WORKFLOWER_PLAN.md` for reference to MESSAGE_CENTER_PLAN (already in archive, correct)
   - Check `plan/EXTERNAL_MCP_INTEGRATION_PLAN.md` for reference to AI_PLAN → update to archive path

4. **Verify:**
   - `grep -r "AI_PLAN\|NAV_WORKSPACES\|SAMPLE_SENDER_UX" --include="*.md" .` — should only point to `plan/archive/`
   - `grep -r "UX_PLAN" --include="*.md" plan/` — should only reference the retained Phases 2–3

## Notes

- The `plan/archive/` directory already exists and contains prior completed plans (MESSAGE_CENTER_PLAN, TEST_PLAN, AI_MODULES_PLAN, TENANT_FORK_PLAN, NORMALIZATION_PLAN, OPTIMIZATIONS, GAP_CLOSURE)
- No code changes required — this is purely documentation reorganization
- The `plan/AI_PLAN.md` decision log (lines 229–248) is valuable history; preserve it in the archived copy
- The `plan/NAV_WORKSPACES_PLAN.md` deviation log (lines 39–55) and verification notes are similarly valuable