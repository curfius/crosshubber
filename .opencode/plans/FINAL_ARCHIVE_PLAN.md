# Plan: Archive Completed Plans + Setup Simple Context Filtering

## Goal
Archive all completed/dropped plans, set up the simple directory-based context filtering system (active/parked/archive), and update cross-references.

## Current State

### Completed → Archive
| Source | Destination | Reason |
|--------|-------------|--------|
| `plan/AI_PLAN.md` | `plan/archive/AI_PLAN.md` | All phases done/dropped (A,B,C1-5,D,F,G3,H) |
| `plan/NAV_WORKSPACES_PLAN.md` | `plan/archive/NAV_WORKSPACES_PLAN.md` | 7 phases shipped 2026-10-09 |
| `plan/UX_PLAN.md` (Phase 1) | `plan/archive/UX_PLAN_PHASE1.md` | Implemented 2026-09-22 |
| `.opencode/plans/SAMPLE_SENDER_UX_PLAN.md` | `plan/archive/SAMPLE_SENDER_UX_PLAN.md` | Committed e05babc |
| `.opencode/plans/ACTORS_AND_ROLES_DEFINITION.md` | `plan/archive/ACTORS_AND_ROLES_DEFINITION.md` | Superseded by STAFFING_MODULE_PLAN §4 |
| `.opencode/plans/ADMIN_SETTINGS_EXPANSION.md` | `plan/archive/ADMIN_SETTINGS_EXPANSION.md` | Superseded by STAFFING_MODULE_PLAN §11 |
| `.opencode/plans/DEPENDENCIES_AND_INTEGRATIONS.md` | `plan/archive/DEPENDENCIES_AND_INTEGRATIONS.md` | Superseded by STAFFING_MODULE_PLAN §9 |
| `.opencode/plans/ENHANCED_AGENTIC_TOOLS.md` | `plan/archive/ENHANCED_AGENTIC_TOOLS.md` | Superseded by STAFFING_MODULE_PLAN §12 |
| `.opencode/plans/ENHANCED_WORKFLOW_PROPOSAL.md` | `plan/archive/ENHANCED_WORKFLOW_PROPOSAL.md` | Superseded by STAFFING_MODULE_PLAN §3 |
| `.opencode/plans/EVENTS_SPECIFICATION.md` | `plan/archive/EVENTS_SPECIFICATION.md` | Superseded by STAFFING_MODULE_PLAN §10 |
| `.opencode/plans/NOTIFICATION_AND_TASK_SCHEMA.md` | `plan/archive/NOTIFICATION_AND_TASK_SCHEMA.md` | Superseded by STAFFING_MODULE_PLAN §10 |

### Active (Stay in `plan/`)
| File | Retained Content |
|------|------------------|
| `plan/UX_PLAN.md` | **Only Phases 2–3** (structural fixes, command palette, home merge) |
| `plan/WORKFLOWER_PLAN.md` | Draft-to-later (prereqs met, awaiting activation) |
| `plan/EXTERNAL_MCP_INTEGRATION_PLAN.md` | Approved, parked until real MCP server exists |

### Active (Stay in `.opencode/plans/`)
| File | Status |
|------|--------|
| `.opencode/plans/STAFFING_IMPLEMENTATION_PLAN.md` | Active — P-S+P0 done, P1+ pending |
| `.opencode/plans/STAFFING_MODULE_PLAN.md` | Active — Unified domain plan |

### Parked (Move to `plan/parked/`)
| File | Reason |
|------|--------|
| `plan/WORKFLOWER_PLAN.md` | Draft-to-later, awaiting activation |
| `plan/EXTERNAL_MCP_INTEGRATION_PLAN.md` | Approved, parked until real MCP server |

---

## New Directory Structure

```
plan/
  UX_PLAN.md                 # active (Phases 2-3 only)
  archive/
    AI_PLAN.md
    NAV_WORKSPACES_PLAN.md
    UX_PLAN_PHASE1.md
    SAMPLE_SENDER_UX_PLAN.md
    ACTORS_AND_ROLES_DEFINITION.md
    ADMIN_SETTINGS_EXPANSION.md
    DEPENDENCIES_AND_INTEGRATIONS.md
    ENHANCED_AGENTIC_TOOLS.md
    ENHANCED_WORKFLOW_PROPOSAL.md
    EVENTS_SPECIFICATION.md
    NOTIFICATION_AND_TASK_SCHEMA.md
  parked/
    WORKFLOWER_PLAN.md
    EXTERNAL_MCP_INTEGRATION_PLAN.md

.opencode/plans/
  STAFFING_IMPLEMENTATION_PLAN.md   # active
  STAFFING_MODULE_PLAN.md           # active
  ARCHIVE_COMPLETED_PLANS.md        # this plan
  CONTEXT_FILTERING_PLAN.md         # reference
  TASK_RELEVANT_LOADING_PLAN.md     # reference
  SIMPLE_CONTEXT_FILTERING.md       # reference
```

---

## Context Filtering Setup (Simple)

### 1. Front-matter (Optional, for Human Clarity)
Add to each plan file:
```yaml
---
status: active | archived | parked
domain: ux | ai | staffing | workflow | messaging
summary: "One line"
---
```

### 2. Agent Instruction (System Prompt or Skill)
> **Default**: Load no plans.  
> **Explicit**: "check plan for X" → agent reads relevant plan from `plan/` (active) or `plan/archive/` or `plan/parked/` as appropriate.  
> **No default loading** — agent loads only when explicitly asked.

### 3. Cross-Reference Updates
| File | Updates |
|------|---------|
| `AGENTS.md` | Update any plan paths to `plan/archive/...` |
| `plan/UX_PLAN.md` (retained) | Update AI_PLAN/NAV_WORKSPACES refs → archive |
| `plan/EXTERNAL_MCP_INTEGRATION_PLAN.md` | Update AI_PLAN ref → archive |
| `plan/WORKFLOWER_PLAN.md` | MESSAGE_CENTER_PLAN ref already archive ✓ |
| `.opencode/plans/STAFFING_IMPLEMENTATION_PLAN.md` | STAFFING_MODULE_PLAN ref stays (active) |

---

## Execution Steps

1. **Move 11 files** to `plan/archive/`
2. **Move 2 files** to `plan/parked/`
3. **Trim `plan/UX_PLAN.md`** to keep only Phases 2–3
4. **Add front-matter** to 15 plan files
5. **Update cross-references** in 5 files
6. **Add agent instruction** to system prompt or `.opencode/skills/context-filter.md`

---

## Questions

1. **Front-matter**: Add to all 15 files, or only moved ones?
2. **Agent instruction location**: System prompt addition, or `.opencode/skills/context-filter.md` skill file?
3. **Validation**: Add git hook to enforce front-matter on new plan files?
3. **Trim UX_PLAN.md now or later?**: Trim Phase 1 out now (makes active file cleaner) or keep as-is and just move Phase 1 to archive?

---

## Estimated Effort
- File moves: ~13 moves
- Front-matter adds: ~15 files × 30 sec = 7.5 min
- Cross-ref updates: ~5 files × 2 min = 10 min
- **Total: ~30 min**