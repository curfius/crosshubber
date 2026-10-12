# Plan: Simple Archived/Parked Context Filtering

## Core Insight
**Directory structure IS the filter.** No index, no capability graph, no custom tools needed.

## Solution

### 1. Three Directories (Already Exist or Trivial to Create)
```
plan/
  active/           # or just plan/ (current location)
    UX_PLAN.md              # active (Phases 2-3)
    WORKFLOWER_PLAN.md      # parked
    EXTERNAL_MCP_INTEGRATION_PLAN.md  # parked
  archive/
    AI_PLAN.md
    NAV_WORKSPACES_PLAN.md
    UX_PLAN_PHASE1.md
    ... (10 more)
  parked/
    WORKFLOWER_PLAN.md
    EXTERNAL_MCP_INTEGRATION_PLAN.md
```

### 2. Simple Front-matter (Optional, for Human Clarity)
```yaml
---
status: active | archived | parked
domain: ux | ai | staffing | workflow | messaging
summary: "One line"
---
```

### 3. Single Agent Instruction (System Prompt or Skill)
> **Default**: Only read plans from `plan/` (active).  
> **Explicit request**: "load @archived AI_PLAN" or "load @parked WORKFLOWER" → agent reads from `plan/archive/` or `plan/parked/`.  
> **Domain hint**: "plans about ux" → agent reads `plan/UX_PLAN.md` + checks `plan/archive/` for ux-related if needed.

That's it. No index, no capability graph, no dependency resolution, no custom tools, no LLM tag extraction.

## Why This Works
- **Directory structure is self-documenting** — `archive/` = done, `parked/` = on hold, `plan/` = active
- **Agents already know how to read files** — no new tools needed
- **User intent is explicit** — "load archived" = clear intent
- **Zero infrastructure** — no index generation, no validation hooks, no custom skills

## Implementation (3 Steps)

1. **Move files** (12 to `archive/`, 2 to `parked/`, trim UX_PLAN to Phases 2-3)
2. **Add front-matter** to 15 files (30 seconds each)
3. **Add one agent instruction** to system prompt or `.opencode/skills/context-filter.md`

## Cross-Reference Updates
- `AGENTS.md` → update paths to `plan/archive/...`
- `UX_PLAN.md` (retained) → update refs
- `EXTERNAL_MCP_INTEGRATION_PLAN.md` → update ref

## Done
Total: ~15 file moves + 15 front-matter adds + 1 instruction. No config changes, no tools, no index generation, no validation hooks.