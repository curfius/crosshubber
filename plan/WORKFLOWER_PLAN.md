# WORKFLOWER_PLAN — Workflow orchestration module (agent + scheduler + events + human tasks)

Status: **draft-to-later** (2026-10-06). Not scoped for build. Prerequisites: message center
Phases 0–2 shipped (`plan/MESSAGE_CENTER_PLAN.md`) and the AI tool stack live (`plan/AI_PLAN.md`).
This module is also the intended owner of the two hooks deferred there (agent-wake on response
events, expiry/timeout events).

## 1. Vision

An independent, module-scoped orchestration engine that chains the four capability families the
portal already has into declarative workflows:

- **Portal agent** — AI_PLAN tool loop, builtin + manifest tools, module sub-agents (HTTP
  envelope), Phase E MCP tools.
- **Scheduling** — cron and one-shot timers; the first real scheduler runtime in the portal
  landscape (nothing scheduled today by design).
- **NATS events** — subscribe to filtered subjects (incl. `portal.msg.>` / `portal.task.>` /
  `portal.task.response.>`), publish outputs on the shared `EventPublisher` contract.
- **Human tasks** — file tasks through the msgcenter envelope (`/publish` path or direct
  service call), await correlated responses, use claim-pool tasks (`groups[]` + claim + draft
  takeover) for "someone from this pool picks it up" steps.

Design stance: **workflows are recipes, not BPMN.** Linear step lists with typed nodes and
lightweight branching; no visual canvas in v1 (JSON/YAML DSL first), no sub-workflows, no
per-tenant definition marketplace at first.

## 2. Core model (sketch)

```jsonc
{
  "key": "expense-approval-flow",          // KEY_RE; definitions are versioned + immutable per version
  "version": 4,
  "trigger": {
    "kind": "event | schedule | manual | task-response",
    "subject": "portal.task.response.solutions.*",   // event trigger w/ pattern + optional payload filter
    "cron": "…",                                     // schedule trigger
  },
  "steps": [
    { "type": "agent",          "task": "…", "tools": "all | list" },          // headless agent run / tool dispatch
    { "type": "task",           "template": {"key": "…", "version": 3},                        // file msgcenter task
      "audience": {…}, "wait": true, "timeout": {"after": "PT48H", "then": "escalate"} },
    { "type": "waitForEvent",   "subject": "…", "timeout": … },                   // correlation handle
    { "type": "publishEvent",   "subject": "portal.task.solutions.…",  "envelope": … },
    { "type": "delay",          "for": "PT30M" },
    { "type": "condition",      "expr": "step.prev.outcome == approved", "then": […], "else": […] }
  ]
}
```

- **Definitions** are versioned and immutable; runs pin a definition version (same philosophy as
  mc task templates: what ran must stay reproducible).
- **Runs**: durable `wf_runs` / `wf_steps` tables; step-state machine persisted before side
  effects; idempotency per `(run_id, step ordinal)`; retry/backoff policies per node; failed
  instances land in a DLQ with reason, never loop silently (mirrors the msgcenter ingest
  discipline).

## 3. Human-in-the-loop steps

- A `task` step files a msgcenter task and parks the run (`waiting_task`). Resume = consumer on
  `portal.taskresponse.<moduleKey>.<event>` whose `eventRef` matches the filed task (this
  generalizes the "agent-wake" hook deferred from the message center plan; note the subject tree
  is `portal.taskresponse.*`, deliberately OUTSIDE `portal.task.>` so the msgcenter durable
  consumer never ingests completion events).
- Claim-mode tasks + `groups[]` audiences give workflow steps a **picker pool**: any pool member
  takes the task, drafts/takeover carry multi-session work; the run only cares about the final
  outcome event.
- Timeout handling: msgcenter expiry stays read-time-only (contract), so **Workflower owns
  timeouts for its own waits** — on deadline it files an escalation task (default) or wakes an
  agent run; the original task simply expires in the inbox.
- Multi-level approvals (e.g. 2-level approval) are composed here: template stage 1 → await
  response → conditionally file stage 2 referencing the same template family. The message center
  deliberately stays shape-only/no-business-logic inside the portal.

## 4. Agent steps

- Headless agent run API (open question §6): dispatch through the existing tool registry under a
  system or run-owner identity; same RBAC, same `agent_tool_calls` audit for free; no chat confirm
  loop — mutating steps require an explicit human `task` step instead (confirmation = a person,
  not a dialog).
- Sub-agents: call module-owned agents via the existing HTTP envelope (F1 contract), depth cap 1
  as today.

## 5. Guardrails (pin early)

Max concurrent runs; per-step output size caps; prompt/tool-iteration caps; **loop guard** — a
run may not trigger a workflow in its own namespace transitively (event → run → event cycles);
step timeouts; DLQ visibility in an admin surface; per-namespace subject allowlists.

## 6. Open questions (resolve when this plan is activated)

1. **Placement**: builtin module (direct access to agent/msgcenter services) vs external module
   (own schema + MFE, HTTP contracts) — lean builtin, decide with a real feature list.
2. **Scheduler runtime**: in-app scheduler + DB-loaded clock table vs Quartz vs dedicated
   container; multi-instance fencing deferred with the portal's HA backlog.
3. **Headless agent API**: shape of a non-chat agent invocation endpoint (AI_PLAN extension).
4. **Identity of event-triggered runs**: run-owner principal vs service principal (portal
   currently has no service-account concept).
5. **Definition authoring UX**: JSON/YAML-first with schema validation; visual canvas deferred.

## 7. Deferred

Visual canvas; sub-workflows; cross-tenant fan-out; per-tenant definition marketplaces; workflow
metrics/dashboards; workflow-triggered i18n content generation.
