# Agent Protocol — chat SSE frames, context pack, tool calls

The single contract between the portal chat backend and its UIs (and future module
agents). Server and UI versions evolve against this document. Current status:
implemented in `AiHubChatService` + `ChatCoreService` (AI plan phases A/B, 2026-09-29;
F sub-agent dispatch, 2026-09-30).

## Transport

`POST /api/ai-hub/chat` → `text/event-stream`. Each element is an SSE `data:` frame.
Spring's SSE writer may omit the space after `data:` — parsers must accept both.

## Request

```json
{
  "conversationId": "conv_… | null",
  "message": "user text",
  "context": {
    "location": { "tabKey": "ai-hub:main", "appKey": "ai-hub", "modulePath": "/chat" },
    "openTabs": [ { "key": "ai-hub:main", "title": "AI Hub" } ],
    "workspace": "Ops"
  },
  "toolConfirmation": { "callId": "call_…" }
}
```

- `context` is optional; missing context = pre-context behavior (AI plan A2). Only
  navigation state is trusted from the client — the server overwrites `user`,
  `tenant` and `contentVersion` from the authenticated principal and instance state
  (server-authoritative, AI plan A1).
- `toolConfirmation` confirms a pending mutating tool call (below). Only the user who
  triggered the call can confirm it; unknown/expired ids fail closed.

## Frames (response)

| Frame | Shape | Meaning |
|---|---|---|
| content | `{"content": "…"}` | text delta (backwards compatible — the only frame before phases A/B) |
| tool_call | `{"type":"tool_call","tool":"<name>","module":"<moduleKey>","mutates":bool}` | the model invoked a tool |
| tool_result | `{"type":"tool_result","tool":"<name>","status":"ok\|denied\|error\|needs_confirmation\|cap_reached","callId":"…"}` | dispatch outcome |
| confirmation_required | `{"type":"confirmation_required","tool":"<name>","callId":"call_…"}` | mutating tool parked; UI may offer a confirm affordance |
| citation | `{"type":"citation","tool":"<name>","citations":[{"title":"…","ref":"…","snippet":"…"}]}` | document sources behind a successful search (max 5, snippet truncated) |
| error | `{"error": "…"}` | stream-level failure |
| `[DONE]` | `data: [DONE]` | sentinel terminating the stream |

`citation` frames (AI plan G3) follow a successful `tool_result` whose payload carries a
`citations[]` or `snippets[]` array of `{title, ref|documentRef, snippet}` objects (at least
`title` is required; `ref` and `snippet` are optional). One frame per tool call, emitted only
for `status:"ok"`. UIs must ignore unknown frames/fields.

New conversation ids are returned via the `X-Conversation-Id` response header.

## Tool loop semantics (AI plan B)

- Tools come from the server-side registry: built-in portal reads plus every active
  installed module's manifest `agentContributions.tools[]` and
  `agentContributions.agents[]` (sub-agent delegations, below). Remote entry names are
  flattened to `moduleKey_name` for provider compatibility (function names must be
  alphanumeric + `_`/`-`; hyphens are folded to `_`).
- The loop runs with **native model tool calling** (Spring AI), capped at 8
  dispatched tool calls per turn (`cap_reached` → the model must answer from what it
  has).
- Authorization: a tool declaring `roles[]` requires the caller to hold at least one;
  undeclared = any authenticated user. Denied calls are audited and narrated by the
  model — never a 500. Matching is **exact string equality** against the caller's
  Keycloak realm roles.

## Role provisioning

Module roles are module-owned: the manifest `security.roles[]` declares the keys
(`solutions-user`, `solutions-admin`, `staffing-user`, …) and every tool/content
`roles[]` must use exactly those names. On install (and each boot for
tenant-config-declared externals), the portal creates any missing realm roles in
Keycloak — requires `portal.kc-admin.*` (all four values, secret sourced as
`KC_ADMIN_CLIENT_SECRET` from `secrets.env`). Granting is manual: assign the role to
a user or group (typically the tenant's admin group) in the Keycloak console. A
near-miss role name created by hand (e.g. `portal-solutions-edit`) never satisfies
`solutions-user` — the dispatcher will deny with
`you do not have the required roles for <tool>`.
- In the dev stack the Keycloak realm is **ephemeral** (`KC_DB: dev-file`, no data
  volume): every container recreate reimports `config-management/tenants-config/dev/realm.json`
  and drops console-made changes. Put durable grants in that import (`roles.realm` +
  group `realmRoles`) and recreate Keycloak after editing it.
- **Mutating tools never execute on first call.** They park in the pending store
  (10-minute TTL) and return `needs_confirmation` with a `callId`. The UI confirms by
  sending `toolConfirmation.callId` on the next chat request; the portal executes the
  call and folds a system note (status + result) into that turn so the model narrates
  the outcome.
- Every dispatch attempt lands in the `agent_tool_calls` audit table: user,
  conversation, tool, truncated args summary, outcome
  (`ok|denied|error|needs_confirmation|confirmed|cap_reached`), duration.
- Gate: the loop is active while the `agent.enabled` AI Hub setting is true
  (default). Disabling it restores the plain streaming chat (no tool frames).

## Remote module dispatch (AI_MODULES_PLAN P3/P5)

The portal calls remote module tools as `POST {baseUrl}{path|/agent/tools/{name}}`
with body `{"tool": name, "arguments": {...}}` and header
`X-Portal-Agent: <token>` — a short-lived (5 min) HMAC-SHA256-signed token carrying
`{iss:"portal", sub, name, roles, exp}`, keyed with the portal session secret. There
is no OIDC bearer token to forward (portal sessions are cookies); module backends
validate signature + expiry (roles are enforced portal-side by the dispatcher, before
dispatch — modules authenticate the token but do not check roles). Module base URLs are
admin-configured at install time, so no SSRF guard applies to this path; transport
failures are fail-soft (tool-result errors, never portal 500s).

## Sub-agent dispatch (AI_PLAN F / AI_MODULES_PLAN P7)

Manifest `agentContributions.agents[]` entries (`name`, `description`, `endpoint`,
`roles[]`) hydrate into the same catalogue as tools — surfaced to the model as a
delegating tool named `moduleKey_name` (e.g. `solutions_projects_agent`). Calling it
runs the F1 task-envelope round-trip:

```
POST {baseUrl}{endpoint}
X-Portal-Agent: <same token as tool dispatch>
{
  "task": "<from tool args — required>",
  "expectedOutput": "<optional, from tool args>",
  "context": { "conversationId": "conv_…" },
  "timeoutMs": 25000
}
→ { "status": "done|failed", "output": "…", "artifacts": [], "auditRef": null }
```

The model only supplies `task` (required) and optional `expectedOutput` — the portal
builds the rest of the envelope. `status:"done"` folds back as `tool_result` status
`ok`; any other status folds back as `error` (payload preserved), so the model
narrates module-side failures (e.g. module LLM not configured) instead of treating
them as answers.

- **Roles are enforced portal-side only**: the dispatcher checks the caller against
  the manifest `roles[]` before the HTTP call — modules authenticate the token but
  never check roles.
- Agents never park for confirmation (there is no `mutates` on `agents[]`); manifest
  `roles[]` is the mutation-safety gate.
- **Delegation depth is structurally 1**: the sub-agent runs module-side as a single
  model turn with no path back into the portal tool loop (F3 depth cap), so it cannot
  spawn further sub-agents.
- Sub-agent activity is visible in the transcript as ordinary `tool_call` /
  `tool_result` frames (F5, Phase C3 rows) and every attempt lands in the
  `agent_tool_calls` audit table with duration — a sub-agent turn includes module-side
  LLM latency.
- Fail-soft: unreachable module or `status:"failed"` → tool-result error; never a
  portal 500.
