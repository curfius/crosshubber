# Plan: Task-Relevant Plan Loading with Dependency Resolution

## Goal
Agents load **only the plans relevant to their assigned task** (plus transitive dependencies), not all "active" plans. This is more precise than a global status filter.

## Core Mechanism

### 1. Plan Metadata (Front-matter)
```yaml
---
domain: [ai, ux, staffing, workflow, messaging, navigation, settings]
capabilities: [agent-tools, sub-agents, mcp, chat, scheduling, events, roles, workflow-dsl]
provides: [session-context-pack, tool-registry, citation-frames, workflow-engine]
depends_on: [plan/AI_PLAN.md, plan/UX_PLAN.md]
status: active | archived | parked
summary: "Session context pack + tool execution for portal agent"
---
```

### 2. Plan Index (`plan/index.json`)
```json
{
  "plans": [
    {
      "id": "AI_PLAN",
      "path": "plan/AI_PLAN.md",
      "domain": ["ai"],
      "capabilities": ["agent-tools", "sub-agents", "mcp", "chat", "citations"],
      "provides": ["session-context-pack", "tool-registry", "tool-dispatcher", "sub-agent-envelope", "citation-frames"],
      "depends_on": [],
      "status": "archived",
      "completed": "2026-10-08"
    },
    {
      "id": "STAFFING_IMPL",
      "path": ".opencode/plans/STAFFING_IMPLEMENTATION_PLAN.md",
      "domain": ["staffing"],
      "capabilities": ["entities", "workflow", "matching", "connectors", "analytics"],
      "provides": ["rfp-lifecycle", "candidate-process", "jev-matching", "connector-registry"],
      "depends_on": ["plan/AI_PLAN.md", "plan/UX_PLAN.md"],
      "status": "active"
    }
  ],
  "capability_index": {
    "agent-tools": ["AI_PLAN"],
    "jev-matching": ["STAFFING_IMPL"],
    "workflow-dsl": ["WORKFLOWER"]
  },
  "domain_index": {
    "ai": ["AI_PLAN", "EXTERNAL_MCP"],
    "staffing": ["STAFFING_IMPL", "STAFFING_MODULE"],
    "ux": ["UX_PLAN"],
    "workflow": ["WORKFLOWER"]
  }
}
```

### 3. Agent Loading Algorithm
When agent receives a task:
1. **Extract intent tags** from task description (LLM-assisted or keyword-based)
   - e.g., "add sub-agent support" → tags: `sub-agents`, `agent-tools`, `ai`
   - e.g., "implement JEV matching" → tags: `jev-matching`, `staffing`, `matching`
2. **Query capability_index** → find plan IDs providing those capabilities
3. **Resolve dependencies** → add all `depends_on` transitively
4. **Filter by status** → exclude `archived` unless explicitly requested
5. **Load full content** of selected plans only
6. **Return plan summaries** to agent context

### 4. Agent Tool: `load_relevant_plans(task_description)`
```typescript
interface PlanRef { id: string; path: string; domain: string[]; summary: string; }
interface LoadResult { plans: PlanRef[]; loaded_content: Map<string, string>; }

function load_relevant_plans(task: string): LoadResult {
  const tags = extract_tags(task);           // LLM or keyword
  const planIds = resolve_capabilities(tags); // capability_index lookup
  const withDeps = resolve_dependencies(planIds); // transitive closure
  const filtered = withDeps.filter(p => index[p].status !== 'archived');
  const content = filtered.map(id => ({ id, content: read_plan(index[id].path) }));
  return { plans: filtered.map(id => index[id]), loaded_content: content };
}
```

### 5. Explicit Overrides
- User can say: "load @archived AI_PLAN" or "include dependencies of STAFFING_IMPL"
- Agent can request: "load all plans tagged `workflow`"

## Implementation

### Phase 1: Metadata & Index (No Agent Changes)
1. Add front-matter to 15 plan files
2. Generate `plan/index.json` with capability/domain indexes
3. Create `scripts/plan-index.mjs` for generation/validation

### Phase 2: Agent Tool Integration
1. Create `.opencode/skills/task-relevant-loading/SKILL.md` with algorithm
2. Expose `load_relevant_plans` as agent tool (or built-in behavior)
3. Update system prompt: "Use `load_relevant_plans` at task start; load full content only for returned plans"

### Phase 3: Migration & Validation
1. Move 12 completed → `plan/archive/`, 2 parked → `plan/parked/`
2. Update cross-references
3. Add git hook for front-matter validation

## Example Scenarios

| Task | Tags Extracted | Plans Loaded |
|------|----------------|--------------|
| "Add MCP tool support" | mcp, ai, tool-registry | EXTERNAL_MCP (parked), AI_PLAN (archived, explicit) |
| "Implement JEV matching for staffing" | jev-matching, staffing, matching | STAFFING_IMPL (active), STAFFING_MODULE (active) |
| "Fix workspace rename bug" | ux, navigation, workspaces | UX_PLAN (active: Phase 2), NAV_WORKSPACES (archived) |
| "Build workflow orchestration module" | workflow, scheduling, human-tasks, agent-tools | WORKFLOWER (parked), AI_PLAN (archived for tool-registry), MESSAGE_CENTER (archived) |
| "Add command palette" | ux, search, navigation | UX_PLAN (active: Phase 3), NAV_WORKSPACES (archived) |

## Questions

1. **Tag extraction**: LLM-based (accurate, costs tokens) vs keyword-based (fast, may miss)?
2. **Capability granularity**: How specific should `provides` be? (e.g., "tool-registry" vs "builtin-tool-provider")
3. **Dependency direction**: `depends_on` = "this plan requires that plan" — correct direction for loading?
4. **Archived plans**: Load only on explicit request, or also when they're a dependency of an active plan? (Current: explicit only)
5. **Token budget**: Max plans to load per task? (Suggest: 5-7 plans max)

## Recommendation
Start with **Phase 1** (metadata + index) — no agent changes needed yet, just data foundation. Phase 2 builds the tool. This gives immediate structure for manual queries and enables the tool later.