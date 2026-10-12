# Context Filter Skill

## Purpose
Control which plans are loaded into agent context. Plans are organized by status in the filesystem — no global loading by default.

## Plan Locations

| Status | Directory | Meaning |
|--------|-----------|---------|
| active | `plan/` | Currently being worked on in this session *(currently empty)* |
| parked | `plan/parked/` | Work paused, can resume later |
| archived | `plan/archive/` | Done, reference only |

## Agent Instructions

### Default Behavior
**Load no plans by default.** Only load plans when explicitly asked.

### Loading Plans
When user asks "check plan for X" or "look at the plan for X":
1. Determine which plan(s) are relevant from the task description
2. Read the relevant plan file(s) from the appropriate directory:
   - `plan/` — active plans
   - `plan/parked/` — paused work, can resume
   - `plan/archive/` — completed work, reference only
3. Load only the relevant plan(s), not all plans

### Explicit Requests
User may explicitly specify:
- "check the parked plan for X" → read from `plan/parked/`
- "check the archived plan for X" → read from `plan/archive/`
- "check the active plan for X" → read from `plan/`

### Plan Front-matter
Each plan has YAML front-matter:
```yaml
---
status: active | parked | archived
domain: ux | ai | staffing | workflow | messaging | navigation
summary: "One line description"
---
```

## Cross-References
- `AGENTS.md` references archived plans at `plan/archive/...`
- `plan/EXTERNAL_MCP_INTEGRATION_PLAN.md` (parked) references `plan/archive/AI_PLAN.md`
- `plan/WORKFLOWER_PLAN.md` (parked) references `plan/archive/MESSAGE_CENTER_PLAN.md`
- `.opencode/plans/STAFFING_IMPLEMENTATION_PLAN.md` (parked) references `plan/parked/STAFFING_MODULE_PLAN.md`

## No Default Loading
**Critical**: Do not load any plans on session start. Only load when explicitly requested by the user.