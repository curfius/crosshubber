# Portal AI Plan — Agent Roadmap

Roadmap for evolving the portal from a plain chat UI (AI Hub) into an agent-capable
platform: a session-aware portal agent that can use module-contributed tools/skills,
delegate work to module-owned sub-agents, and retrieve module-owned knowledge.

Status: **backlog — nothing implemented yet.** Phases below are proposed; each needs
its own go decision. Related: `plan/UX_PLAN.md` (navigation), `portal/server/OPTIMIZATIONS.md`
(backend), AGENTS.md backlog (chat/quick-chat merge).

## Design principles

- **Portal owns orchestration, modules own capability.** The portal agent is the
  super-agent loop (context, planning, tool dispatch, safety). Modules contribute
  tools, skills, knowledge, and sub-agents via the manifest — never hardcode
  module-specific logic into the shell.
- **Manifest is the only integration surface.** Everything a module exposes to the
  agent lives under `agentContributions` (v1 today: `tools[]`, `skills[]` as empty
  default arrays). Validation stays in `ManifestValidator`; the reconciler keeps
  stored manifests normalized.
- **Agent actions are ordinary portal APIs first.** A tool is a typed call into an
  existing service (registry, settings, workspaces, i18n…) with the caller's roles
  enforced — the agent never gets a privilege bypass. New agent-only endpoints are
  the exception, not the rule.
- **Streaming and fail-fast match existing chat.** AI Hub already streams SSE
  (`{"content":"..."}` frames + `[DONE]`); tool-call events must ride the same
  channel or a sibling stream without inventing a second transport.
- **No silent side effects.** Every mutating tool call is logged with actor
  (user vs. agent), tool id, and args summary; destructive tools require an
  explicit confirmation step in the UI (mirrors UX Plan "every dead end gets feedback").
- **Tests over parity.** Agent behavior is Crosshubber-owned: unit-test the tool
  registry, dispatcher, and context pack builders; integration-test the SSE protocol;
  `mvn verify` + `npm test` green (there is no parity harness).

## Foundations today (what exists to build on)

| Area | Where | Notes |
|---|---|---|
| Manifest `agentContributions` | `ManifestValidator.java` (defaults `tools[]`/`skills[]`), README table | v1 schema only — arrays are never populated or read by runtime code |
| Chat orchestration | `modules/aihub/chat/AiHubChatService` | Spring AI `ChatClient` per (provider, token, model), 60s cache, `ChatMemory` → `ai_hub_chat_memory`, SSE stream |
| Providers/tokens/models | `modules/aihub/providers/*` | Admin-managed under `portal-ai-hub-edit`; encrypted tokens (`CryptoService`) |
| Conversations | `modules/aihub/conversations/*` | Per-user CRUD + message history endpoints |
| Quick Chat entry | EmbeddedCatalog EP `ai-hub:quick-chat` (features, roles `[]`) + UI `quick-chat.component` | Orphaned EP — Shell hardcodes the flyout (UX Plan #11 / #16) |
| Shell config / RBAC | `ShellConfigService` triple filter; `Roles`, `@PreAuthorize` on mutating endpoints | Agent tool dispatcher must reuse the same role checks |
| Module registry | `modules/registry/*` (manifest fetch, validate, install) | Source of installed modules' `agentContributions` |
| Event bus | NATS (compose service; health surfaced in `/api/config` services) | Portal currently only reports the NATS URL — no publish/subscribe client in Java yet |
| i18n / settings / workspaces | respective feature modules | Candidate first-wave tools (read + benign write) |

## Phase A — Session context pack (smallest slice)

**Goal:** every chat request carries a compact, structured portal session context so
the model answers with awareness of who is asking, where they are, and what is open.

| # | Work item | Detail |
|---|---|---|
| A1 | Define `SessionContextPack` DTO | `user` (name, roles), `tenant` (slug, locale), `location` (active tab key, app key, module path from URL/nav state), `openTabs` (key + title only), `workspace` (name or null), `contentVersion`. No secrets, no tokens, no other users' data. |
| A2 | Client → server handoff | UI collects pack fields it already has (Shell/workbench/url-sync signals) and sends them on `POST /api/ai-hub/chat` as an optional `context` object. Missing context = today's behavior (backwards compatible). |
| A3 | Server-side system prompt assembly | `AiHubChatService` prepends a system message: persona ("Crosshubber portal agent"), context pack JSON, active tool catalogue (Phase B). Keep under a hard budget (~1–2k tokens); drop oldest optional sections first. |
| A4 | Persist pack snapshot | Store the pack (or its hash + diff) with the conversation turn for audit and for "what did the agent know then?" debugging. |
| A5 | Tests | Unit: pack builder + prompt assembler (stable ordering, redaction). Integration: chat SSE still streams with and without `context`. |

**Exit criteria:** ask "what settings can I change here?" and the reply reflects the
caller's roles and current module; no schema break for clients that omit `context`.

## Phase B — Tool execution (portal as tool host)

**Goal:** the chat loop can call portal operations as tools, with RBAC and audit.

| # | Work item | Detail |
|---|---|---|
| B1 | Tool registry v1 | Server-side registry of built-in tools: id, JSON Schema args, description, required roles, mutability (read/write), handler (`BiFunction`/service method). Seed with low-risk reads: `getShellConfig`, `listModules`, `listEntryPoints`, `getI18nLabels`, `listWorkspaces`, `getInstanceSettings`. |
| B2 | Manifest `agentContributions.tools` → registry entries | At reconciler/install time, parse each installed module's `tools[]` entries (v2 schema, Phase D) into the same registry shape. Unknown/invalid entries fail validation loudly (fail-fast reconciler). |
| B3 | Model tool-calling loop | Spring AI tool-calling (`ChatClient` tools) wired to the registry; support multi-step loops with a max-iteration cap; stream tool-call / tool-result events on the existing SSE channel (new frame types alongside `{"content"}` — document the protocol). |
| B4 | Authorization | Dispatch runs as the authenticated user: tool handler checks `Roles` / `@PreAuthorize` equivalents. Agent never elevates. Denied tool → tool-result error the model can narrate, not a 500. |
| B5 | Audit log | New table (Flyway `V<n>__agent_tool_calls.sql`): timestamp, user, conversation, tool id, args hash/summary, outcome, duration. UI: read-only list under AI Hub settings (Phase C). |
| B6 | Confirmation for writes | Mutating tools return `needsConfirmation` until the UI shows a confirm dialog (reuse `ConfirmDialog`); only then is the real call made. Read tools skip this. |
| B7 | Tests | Registry schema validation; dispatcher RBAC matrix; loop cap; SSE frame protocol snapshot tests. |

**Exit criteria:** as `devuser`, "rename workspace X" either uses an allowed tool or
explains refusal — never mutates without role; every call lands in the audit table.

## Phase C — Agent UX consolidation

**Goal:** one agent surface instead of hardcoded flyouts; agent capabilities visible.

| # | Work item | Detail |
|---|---|---|
| C1 | Quick Chat via EP | Follow UX Plan #11: Shell opens quick-chat only when `ai-hub:quick-chat` is active and roles allow; retire hardcoded `toggleQuickChat` path. |
| C2 | Merge chat / quick-chat | Existing AGENTS backlog: one chat core service, two shells (full page vs. flyout). |
| C3 | Tool transparency | Streaming UI shows collapsible "used tool X" rows (name, status), not raw JSON dumps. |
| C4 | Confirmation + audit surfaces | Confirm dialog for write tools; AI Hub settings gains "Agent tool calls" list (filter by user/tool/outcome). |
| C5 | i18n | All new strings are catalog keys (`agent.*`) seeded via reconciler + migration for existing installs. |

## Phase D — Manifest `agentContributions` v2

**Goal:** modules declare agent surface with enough structure for validation, UI, and
dispatch — still Crosshubber-owned schema (no external standard mandated).

| # | Work item | Detail |
|---|---|---|
| D1 | Schema draft | `tools[]`: `{ name, description, arguments (JSON Schema), mutates: bool, roles[] }`; `skills[]`: `{ name, description, prompts[] | promptRef }`. Keep unknown-field rejection consistent with current validator style. |
| D2 | `ManifestValidator` v2 | Validate shapes, name uniqueness (`^[a-z][a-z0-9-]*$` style), `roles[]` kebab-case, `arguments` is an object schema. Backwards compatible: v1 empty arrays still normalize. |
| D3 | Registry hydration | Install/reconcile → tool registry entries scoped by module key; uninstall removes them. |
| D4 | Admin visibility | Registry UI shows per-module agent contributions (read-only table; edit stays manifest-file driven). |
| D5 | Catalog / seed | Embedded modules that should contribute tools declare them in `EmbeddedCatalog` (e.g. future portal-native skills). |
| D6 | Tests | Validator fixtures (valid/invalid v2 manifests); registry add/remove on install/uninstall. |

## Phase E — MCP tool execution

**Goal:** the portal can act as an MCP client (consume external tool servers) and/or
expose selected portal tools over MCP for external agents — without weakening RBAC.

| # | Work item | Detail |
|---|---|---|
| E1 | Decision record | Client-only vs. client+server; stdio vs. HTTP/SSE transport; which config surface (instance settings vs. tenant config). Document in this file before coding. |
| E2 | MCP client config | Instance settings form: list of external MCP servers (name, transport, URL/command, enabled, allowed-tools). Secrets via existing encrypted settings paths — never plaintext YAML. |
| E3 | Connection management | Startup + lazy connect, health in `/api/config` services list (alongside NATS pattern), fail-soft: unreachable server disables its tools, does not kill portal boot. |
| E4 | Tool namespace | External tools appear as `mcp:<server>:<tool>` in the registry; collisions rejected at config time. |
| E5 | Portal-as-MCP-server (optional stretch) | Expose a curated allow-list of built-in tools behind an MCP endpoint authenticated with a portal service token; still enforce role mapping. Separate go/no-go from E2–E4. |
| E6 | Tests | Fake MCP server in tests; namespace collision; fail-soft health; no secret leakage in logs. |

## Phase F — A2A-shaped sub-agents

**Goal:** modules (or the portal itself) can run specialized sub-agents and hand off
tasks with a typed envelope — Agent-to-Agent *shape*, not necessarily a full A2A stack.

| # | Work item | Detail |
|---|---|---|
| F1 | Envelope draft | `{ task, context, expectedOutput, constraints, timeoutMs, callback? }` + result `{ status, output, artifacts[], auditRef }`. Own the schema; note where it could map to A2A tasks later. |
| F2 | Sub-agent registration | Manifest `agentContributions.agents[]` (name, description, endpoint or in-process handler, roles). Validator + reconciler as in Phase D. |
| F3 | Orchestrator | Super-agent can spawn one sub-agent per turn (depth cap 1–2); sub-agent runs with the **same user identity**; results fold back into the parent conversation as a tool-result-like frame. |
| F4 | Portal-native sub-agents | Candidates: "navigation helper" (read shell tree + suggest), "registry doctor" (validate installed manifests), "i18n auditor" (find missing keys). In-process first; HTTP sub-agents only after E transport lessons. |
| F5 | UX | Sub-agent activity visible in the transcript (Phase C3 pattern); no separate agent chat window in v1. |
| F6 | Tests | Envelope round-trip; identity propagation; depth/timeout caps; failure → parent-visible error. |

## Phase G — Module-owned knowledge retrieval

**Goal:** the agent answers "how does this module work?" from module-provided knowledge
without the portal indexing the world.

| # | Work item | Detail |
|---|---|---|
| G1 | Knowledge source contract | Manifest `agentContributions.knowledge[]`: `{ id, title, kind: markdown|url, ref }` — content either inline (small) or fetched from module `baseUrl` at ask-time / install-time. |
| G2 | Retrieval strategy v1 | Keyword/BM25 or simple embedding store per tenant schema; start with **fetch-on-demand + truncate** for ≤N docs and only add a vector store if quality demands it (avoid premature infra). |
| G3 | Citation | Answers cite `knowledge[]` ids/titles in the stream (Phase C3 row type `citation`). |
| G4 | Privacy | Knowledge is tenant-scoped (per-schema), never cross-tenant; URL fetches go through `SsrfGuard` + existing HTTP client config. |
| G5 | Tests | Retrieval ranking fixtures; SSRF rejection; tenant isolation. |

## Cross-cutting backlog (do not skip)

- **Protocol doc:** single markdown (or OpenAPI) describing chat SSE frames:
  `content`, `tool_call`, `tool_result`, `confirmation_required`, `citation`, `error`,
  `done` — UI and server versions evolve against it.
- **Cost/abuse controls:** per-user token budget, max tool iterations, max sub-agent
  depth — configurable, default-safe.
- **Observability:** structured logs + audit table metrics; no prompt/PII in logs.
- **i18n:** user-visible agent strings only via catalog keys.
- **Flyway owns DDL:** any new tables (audit, knowledge cache) = new `V<n>__*.sql`.
- **TX hygiene:** LLM/MCP HTTP calls stay **outside** `@Transactional` (AGENTS.md
  invariant) — orchestrate outside, persist inside.
- **UX dependency:** C1/C2 intentionally overlap UX Plan #11/#16 — sequence with that
  plan so Shell does not grow a third chat entry point.

## Suggested sequencing

```
A (context pack)  →  B (built-in tools + audit)  →  C (UX consolidation)
        →  D (manifest v2)  →  E (MCP)  →  F (sub-agents)  →  G (knowledge)
```

A→B is the vertical slice that proves value with zero external dependencies.
D unlocks module contributions; E/F/G each need an explicit go decision.

## Open questions

1. **Model choice / multi-provider:** AI Hub already supports Anthropic + OpenAI
   models via providers — do tool-calling features gate on provider capability
   (function calling vs. forced JSON)?
2. **Where does the super-agent live:** same `AiHubChatService` (grow it) vs. a new
   `modules/agent` package orchestrating AI Hub — prefer new package once B3 lands.
3. **NATS role:** use the existing broker for sub-agent events (F) or stay
   in-process until there is a second process that needs it?
4. **MCP priority** vs. native manifest tools (D): D is strictly higher leverage
   for in-ecosystem modules; E matters when external tool ecosystems are required.
5. **Knowledge embeddings:** defer vector store until retrieval quality of G2
   fetch-on-demand is measured.

## Decision log

| Date | Decision |
|---|---|
| 2026-09-22 | Plan authored as backlog; Phases A–G outlined; no implementation started. |
