# Agent Protocol — chat SSE frames, context pack, tool calls

The single contract between the portal chat backend and its UIs (and future module
agents). Server and UI versions evolve against this document. Current status:
implemented in `AiHubChatService` + `ChatCoreService` (AI plan phases A/B, 2026-09-29).

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
| error | `{"error": "…"}` | stream-level failure |
| `[DONE]` | `data: [DONE]` | sentinel terminating the stream |

`citation` frames are reserved for AI plan G3 (not emitted yet). UIs must ignore
unknown frames/fields.

New conversation ids are returned via the `X-Conversation-Id` response header.

## Tool loop semantics (AI plan B)

- Tools come from the server-side registry: built-in portal reads plus every active
  installed module's manifest `agentContributions.tools[]`. Remote tool names are
  flattened to `moduleKey_name` for provider compatibility (function names must be
  alphanumeric + `_`/`-`).
- The loop runs with **native model tool calling** (Spring AI), capped at 8
  dispatched tool calls per turn (`cap_reached` → the model must answer from what it
  has).
- Authorization: a tool declaring `roles[]` requires the caller to hold at least one;
  undeclared = any authenticated user. Denied calls are audited and narrated by the
  model — never a 500.
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
validate signature + expiry and enforce roles locally. Module base URLs are
admin-configured at install time, so no SSRF guard applies to this path; transport
failures are fail-soft (tool-result errors, never portal 500s).
