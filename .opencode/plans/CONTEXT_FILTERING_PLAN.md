# Plan: Archived & Parked Plan Status with Context Filtering

## Problem
- 12 plan files in `.opencode/plans/` + 5 in `plan/` + archived plans in `plan/archive/`
- No metadata distinguishing active/archived/parked status
- Agents load entire plan files when exploring, polluting context
- No automatic filtering — agents must read full files to determine relevance

## Solution Overview

### 1. Plan Metadata Standard (Front-matter)
Add YAML front-matter to every plan file:
```yaml
---
status: active | archived | parked
completed: 2026-10-12  # for archived
tags: [ai, ux, staffing]  # for filtering
depends_on: [plan/UX_PLAN.md]  # optional
summary: "One-line description for index"
---
```

### 2. Plan Index/Manifest (`plan/index.json` or `.opencode/plans/index.json`)
Single source of truth for all plans with status:
```json
{
  "plans": [
    { "path": "plan/AI_PLAN.md", "status": "archived", "completed": "2026-10-08", "tags": ["ai"], "summary": "..." },
    { "path": "plan/UX_PLAN.md", "status": "active", "tags": ["ux"], "summary": "..." },
    { "path": "plan/WORKFLOWER_PLAN.md", "status": "parked", "tags": ["workflow"], "summary": "..." },
    { "path": ".opencode/plans/STAFFING_IMPLEMENTATION_PLAN.md", "status": "active", "tags": ["staffing"], "summary": "..." }
  ]
}
```

### 3. Opencode Configuration Updates (`opencode.json`)
```json
{
  "$schema": "https://opencode.ai/config.json",
  "skills": {
    "paths": ["./.opencode/skills"]
  },
  "plans": {
    "index": "plan/index.json",
    "auto_load": "active_only",  // active | active+parked | all
    "archived_dir": "plan/archive/",
    "parked_dir": "plan/parked/"  // optional separate dir
  },
  "context": {
    "max_plan_tokens": 8000,
    "prefer_summary": true,
    "load_full_on_demand": true
  }
}
```

### 3. Agent Instructions (System Prompt Addition)
Add to opencode's agent instructions or a dedicated skill:
- **Default**: Only load plans with `status: active` from index
- **On explicit request**: Load parked/archived plans
- **Summary-first**: Read front-matter + summary first; full content only when asked
- **Cross-reference awareness**: Follow `depends_on` links only when relevant

### 4. Directory Structure (Optional but Recommended)
```
plan/
  index.json           # manifest
  UX_PLAN.md           # active (Phases 2-3)
  WORKFLOWER_PLAN.md   # parked
  EXTERNAL_MCP_INTEGRATION_PLAN.md  # parked
  archive/             # archived plans (moved here)
    AI_PLAN.md
    NAV_WORKSPACES_PLAN.md
    ...
  parked/              # optional separate dir for parked
    WORKFLOWER_PLAN.md
    EXTERNAL_MCP_INTEGRATION_PLAN.md

.opencode/
  plans/
    index.json         # mirror or symlink to plan/index.json
    STAFFING_IMPLEMENTATION_PLAN.md  # active
    STAFFING_MODULE_PLAN.md          # active
```

### 5. Tooling (Scripts)
- `scripts/plan-status.mjs` — CLI to transition status (active→archived, active→parked, parked→active)
- Auto-generates `index.json` from front-matter
- Validates all plans have required metadata
- Git hook to enforce metadata on new plan files

## Implementation Steps

### Phase 1: Metadata & Index (Core)
1. Add front-matter to all existing plan files (15 files)
2. Create `plan/index.json` with current statuses
3. Create `scripts/plan-index.mjs` to generate index from front-matter
4. Update `opencode.json` with plans config

### Phase 2: Agent Behavior
1. Create `.opencode/skills/context-filtering/SKILL.md` with agent instructions
2. Update system prompt or create agent config to reference this skill
3. Test: agent should only load active plans by default

### Phase 3: Directory Migration & Archiving
1. Move completed plans to `plan/archive/` (12 files per earlier plan)
2. Move parked plans to `plan/parked/` (2 files: WORKFLOWER, EXTERNAL_MCP)
3. Trim `plan/UX_PLAN.md` to active phases only
4. Update cross-references in AGENTS.md, retained plans

### Phase 4: Validation & Automation
1. Add git pre-commit hook to validate front-matter on new plans
2. CI check: `node scripts/plan-index.mjs --validate`
3. Document workflow in AGENTS.md

## Alternative: Lightweight Approach (No Config Changes)
If opencode config can't be changed:
- Use naming convention: `plan/archive/AI_PLAN.md`, `plan/parked/WORKFLOWER_PLAN.md`
- Agents instructed to only read `plan/*.md` and `.opencode/plans/*.md` by default
- Explicit `@archived` or `@parked` tag in user query to load others
- `plan/index.md` (human-readable) lists all plans with status badges

## Questions for You

1. **Where should the index live?** `plan/index.json` (single source) vs `.opencode/plans/index.json` (opencode-local)
2. **Directory migration?** Move parked plans to `plan/parked/` or keep in `plan/` with status metadata?
3. **Agent instruction mechanism?** Skill file (`.opencode/skills/context-filtering/`) vs system prompt addition vs `opencode.json` config?
4. **Validation strictness?** Require front-matter on all `.md` in `plan/` and `.opencode/plans/`?
5. **Token budget?** What's a reasonable `max_plan_tokens` for default context?

## Recommendation
Start with **Phase 1 + lightweight Phase 2** (metadata + index + skill file) — minimal config changes, immediate context reduction. Directory migration (Phase 3) can follow once the system proves useful.