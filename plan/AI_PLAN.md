# Portal AI Plan — Agent Roadmap

Roadmap for evolving the portal from a plain chat UI (AI Hub) into an agent-capable
platform: a session-aware portal agent that can use module-contributed tools/skills,
delegate work to module-owned sub-agents, and retrieve module-owned knowledge.

Status: **in progress** (re-verified 2026-10-02). Implemented: Phases A, B, D via
H0a (2026-09-29); C1–C5 (C-phase complete — C1 EP-driven quick-chat landed
2026-10-01, C3/C4/C5 via H6 2026-09-30); F1/F2/F3/F5 via H0b
(2026-09-30 — HTTP sub-agent dispatch, depth structurally 1); portal-side G via H5
(2026-10-01 — G3 citation frames on the tool loop, sources rows in chat/quick-chat);
D4 via the registry agent-contributions sections (2026-10-02); F4 dropped
(2026-10-02 — module-owned HTTP agents proved the pattern; no current need for
portal-native ones). C2 was already done (`ChatCoreService`). Not started: E
(deferred), G2 embedding-quality decisions (module-side; Q5 open).
Phase A go scope remains A1–A3 + A5 (A4
deferred). Two dogfood modules (`solutions`, `staffing`) drive sequencing — see
`plan/AI_MODULES_PLAN.md` ("Phase H"); there they are the reference consumer for
phases A/B/D/F/G. Related: `plan/UX_PLAN.md` (navigation),
`plan/archive/OPTIMIZATIONS.md` (backend, closed). Note: the AGENTS.md
backlog item "merge chat/quick-chat duplicated logic" is done — `ChatCoreService`
owns both surfaces.

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
| Quick Chat entry | EmbeddedCatalog EP `ai-hub:quick-chat` (features, roles `[]`) + UI `quick-chat.component` | EP-driven since C1 (2026-10-01): Shell gates the flyout + sidebar dock button on the EP (server-filtered); availability drop auto-closes the flyout. Chat and quick-chat already share `ChatCoreService` (C2 prerequisite done) |
| Shell config / RBAC | `ShellConfigService` triple filter; `Roles`, `@PreAuthorize` on mutating endpoints | Agent tool dispatcher must reuse the same role checks |
| Module registry | `modules/registry/*` (manifest fetch, validate, install) | Source of installed modules' `agentContributions` |
| Event bus | NATS (compose service; health surfaced in `/api/config` services) | Portal currently only reports the NATS URL — no publish/subscribe client in Java yet |
| i18n / settings / workspaces | respective feature modules | Candidate first-wave tools (read + benign write) |

## Phase A — Session context pack (smallest slice)

**Goal:** every chat request carries a compact, structured portal session context so
the model answers with awareness of who is asking, where they are, and what is open.

| # | Work item | Detail |
|---|---|---|
| A1 | Define `SessionContextPack` DTO | `user` (name, roles), `tenant` (slug, locale), `location` (active tab key, app key, module path from URL/nav state), `openTabs` (key + title only), `workspace` (name or null), `contentVersion`. **Server-authoritative**: `user`, `tenant`, `contentVersion` are filled by the server from the authenticated principal/tenant context — the client cannot spoof them. No secrets, no tokens, no other users' data. |
| A2 | Client → server handoff | UI collects the fields it already has (Shell/workbench/url-sync signals) — `location`, `openTabs`, `workspace` only — and sends them on `POST /api/ai-hub/chat` as an optional `context` object. Server fills identity fields. Missing context = today's behavior (backwards compatible). |
| A3 | Server-side system prompt assembly | `AiHubChatService` prepends a system message: persona ("Crosshubber portal agent"), context pack JSON, active tool catalogue (Phase B). Keep under a hard budget (~1–2k tokens); drop oldest optional sections first. |
| A4 | Persist pack snapshot | **Deferred (2026-09-29 go decision).** Persisting the pack (or its hash + diff) with the conversation turn moves to a follow-up after A1–A3/A5 ship. |
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
| C1 | Quick Chat via EP — **DONE 2026-10-01** | Shell opens quick-chat only when `ai-hub:quick-chat` is served (server filters active/roles/hidden); sidebar dock button hidden without the EP; availability drop auto-closes the flyout. |
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
| D4 | Admin visibility — **DONE 2026-10-02** | Registry UI shows per-module agent contributions (read-only sections: Agent Tools / Agent Skills / Agents / Knowledge) via the manifest-diff engine; edit stays manifest-file driven. |
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
| F3 | Orchestrator | Super-agent can spawn one sub-agent per turn (depth cap 1–2); sub-agent runs with the **same user identity**; results fold back into the parent conversation as a tool-result-like frame. Phase H refines transport: remote modules make **HTTP-first** (forwarded user token) the v1 shape, replacing "in-process first" here. |
| F4 | ~~Portal-native sub-agents~~ — **DROPPED 2026-10-02** | Candidates were "navigation helper", "registry doctor", "i18n auditor". Module-owned HTTP agents (solutions projects-agent) proved the envelope pattern end to end; portal-native sub-agents have no current consumer. Revisit only on a concrete portal-native need. |
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

Phase H refinement (2026-09-29): module-owned knowledge is retrieved through
module-declared tools — G1/G2/G4/G5 become module-internal concerns, and the
portal-side scope of this phase reduces to G3 (citation frames + transparency).

## Phase H — Dogfood modules (solutions & staffing)

Two remote MFE modules planned 2026-09-29 as concrete consumers of A/B/D/F/G —
`solutions` (project delivery tracking, module-owned agent, OneDrive RAG) and
`staffing` (RFP→CV matching, tools-only). Modules are fully self-contained (own
datasources, credentials, and LLM access); the only portal↔module coupling is the
manifest contract plus the portal-issued agent-call token. Full spec, manifest v2
examples, portal prerequisite deltas (remote tool dispatch, module auth) and E2E
acceptance scenarios live in **`plan/AI_MODULES_PLAN.md`**.
Phase H drives the sequencing below; A/B/D land before the module domain work, F/G
land as H0b/H5.

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
- **UX dependency:** C1 intentionally overlaps UX Plan #11 (C2 is done via
  `ChatCoreService`) — sequence with that plan so Shell does not grow a third chat
  entry point.

## Suggested sequencing

```
A (context pack)  →  B (built-in tools + audit)  →  C (UX consolidation)
        →  D (manifest v2)  →  E (MCP)  →  F (sub-agents)  →  G (knowledge)
```

A→B is the vertical slice that proves value with zero external dependencies.
D unlocks module contributions; E/F/G each need an explicit go decision.

**Phase H ordering (2026-09-29, dogfood-driven):** A + B (+ remote dispatch) + D
first, then module domain builds in parallel (modules own their datasources and LLM
access — no portal prerequisites remain), then F (HTTP sub-agents) + G (citations)
as H0b/H5. E stays deferred (open question 4). See
`plan/AI_MODULES_PLAN.md` § Sequencing.

## Open questions

1. **Model choice / multi-provider:** AI Hub already supports Anthropic + OpenAI
   models via providers — do tool-calling features gate on provider capability
   (function calling vs. forced JSON)?
2. **Where does the super-agent live:** same `AiHubChatService` (grow it) vs. a new
   `modules/agent` package orchestrating AI Hub — **resolved 2026-09-29**: new
   `modules/agent` package (Phase H0b / AI_MODULES_PLAN P7).
3. **NATS role:** use the existing broker for sub-agent events (F) or stay
   in-process until there is a second process that needs it?
4. **MCP priority** vs. native manifest tools (D): **resolved 2026-09-29** — D
   strictly first (it is on the Phase H critical path); E remains deferred.
5. **Knowledge embeddings:** defer vector store until retrieval quality of G2
   fetch-on-demand is measured.

## Decision log

| Date | Decision |
|---|---|
| 2026-09-22 | Plan authored as backlog; Phases A–G outlined; no implementation started. |
| 2026-09-29 | Status re-verified against code: no A–G work started; chat/quick-chat merge (C2 prerequisite) confirmed done via `ChatCoreService`. |
| 2026-09-29 | Phase A go: scope A1–A3 + A5, A4 (pack snapshot persistence) deferred. Identity fields (`user`, `tenant`, `contentVersion`) are server-authoritative; the client supplies only `location`/`openTabs`/`workspace`. |
| 2026-09-29 | Phase H planned: dogfood modules `solutions` + `staffing` as remote MFEs (see `plan/AI_MODULES_PLAN.md`). Resolves open questions 2 (`modules/agent` package) and 4 (D before E). F3/F4 transport: HTTP-first with forwarded user tokens. Portal-side `modules/docsource` abstraction with OneDrive adapter first. |
| 2026-09-29 | Supersedes the docsource part of the previous entry: **module-owned datasources** — each module implements its own connectors, holds its own credentials, and configures its own LLM provider keys; the portal hosts no module-domain infrastructure. Portal-side Phase G scope reduces to citations/transparency. See AI_MODULES_PLAN decision log. |
| 2026-09-29 | H0a implemented: A1–A3+A5 (context pack, server-authoritative identity), B1–B6 portal core (`modules/agent`: tool registry, builtin tools, RBAC dispatcher, pending confirmations, audit table `agent_tool_calls` V29, SSE tool frames), manifest v2 (tools/skills/agents/knowledge; snake-case names via `Keys.AGENT_NAME_RE`), remote dispatch + portal-signed `X-Portal-Agent` tokens. Protocol doc: `docs/agent-protocol.md`. UI sends `context` and parses typed frames. |
| 2026-09-30 | H6 implemented: C3 (tool-activity rows in chat/quick-chat via shared `ChatToolFlow`), C4 (confirm dialog for `needsConfirmation` dispatches + admin-gated "Agent tool calls" list under AI Hub settings — latest 200 rows, client-side search/outcome filters), C5 (27 `agent.*` labels × 4 languages, reconciler insert-if-absent — no migration needed for new keys). Fixed en route: `AiHubService.init(config.user)` was never called from Shell (`canManage` permanently false). Live-verified on dev: endpoint 401/200/403 matrix, confirm → audit `outcome=confirmed`, devuser → `outcome=denied` with no mutation. Details: AI_MODULES_PLAN decision log. |
| 2026-09-30 | H0b implemented: F1 envelope (`SubAgentInvoker` — `task`/`expectedOutput` from tool args, `context.conversationId`, `timeoutMs` 25s inside the shared 30s read timeout), F2 (`agents[]` hydrates into the tool catalogue as `Kind.AGENT` entries named `moduleKey_name`, hyphens flattened for provider safety), F3 (dispatcher maps F1 `status:"done"`→`ok` else `error`; manifest `roles[]` enforced portal-side — modules only authenticate; depth structurally 1 since the module turn cannot re-enter the portal tool loop; no confirmation parking), F5 (delegations ride ordinary `tool_call`/`tool_result` frames + audit rows). Protocol doc: new "Sub-agent dispatch" section (also fixed the stale claim that modules enforce roles locally). Live-verified (S6): direct envelope probe `status=done` in 4.7s, chat delegation → `solutions_projects_agent` row → audit `outcome=ok` 3.8s → narration folded back. Tests: server 144 (+11: +6 dispatcher, +5 new `ToolRegistryTest`). |
| 2026-10-01 | H5 implemented (portal-side G / G3): `CitationExtractor` (modules/agent) pulls `{title, ref|documentRef, snippet}` items out of a successful tool-result payload (`citations[]` then `snippets[]`, cap 5, snippet truncated at 200); `DispatchingToolCallback` emits one `citation` frame right after the `tool_result` (ok only); `ChatToolFlow` gained a `citations` signal + `ChatCitationRow` (chat and quick-chat render a sources block under the tool rows, DS tokens only); `agent.citations.label` i18n key x 4 languages; persona prompt now tells the model to name the document titles it used. Protocol doc: citation frame defined (was "reserved"). Scope: `knowledge[]` manifests stay empty for now; sub-agent (F1) results carry no citations in v1; no vector store. Live-verified (S4): "Summarize the proposal documents for the ERP Rollout project" -> `solutions_search_project_docs` row -> Sources block with the seeded doc titles -> narrated summary, no confirm dialog. Tests: server 151 (+5 extractor, +2 callback), UI 18 files all green, build exit 0. |
| 2026-10-01 | **C1 (UX Plan #11) landed — quick-chat is EP-driven.** Shell gates the flyout on the served `ai-hub:quick-chat` module content (`wb.findModuleContent('ai-hub','quick-chat')` — ShellConfigService already filters by module active, content active, hidden and roles, so the client check is authoritative-by-construction); `toggleQuickChat` no-ops without the EP; an effect auto-closes the flyout when availability drops mid-session (registry/config refresh after deactivation or role change); Sidebar gained a `quickChatAvailable` input and hides the dock button without the EP (its `moduleContents` input is applications-only, so the computed lives in Shell and passes down as a plain input). Live-verified on dev: button visible + flyout opens as `dev`; EP `active:false` via registry API -> button hidden, flyout absent; re-activated -> both return. UI-only change: npm test 155 green (18 files), build exit 0. |
| 2026-10-02 | **D4 landed; F4 dropped.** D4: `PortalModuleManifest` gained the typed `agentContributions` (tools/skills/agents/knowledge, matching the server validator schema); `buildDiffSections` emits four keyed sections (`Agent Tools`/`Agent Skills`/`Agents`/`Knowledge`, keyed by name/id) — the registry module detail renders them read-only via the existing generic-sections loop, and install/upgrade previews show contribution diffs for free; `fieldValue` formatter prints objects as JSON (no more `[object Object]`); 11 new `registry.section/field.*` keys x 4 languages (reconciler insert-if-absent). Live-verified: Solutions shows 5 tools (mutates=true on update_project_stage) + projects-agent, Staffing shows 5 tools with no Agents section, AI Hub shows none. Tests: UI 18 files green (+3 diff-engine specs), build exit 0. **F4 dropped by owner decision** — module-owned HTTP sub-agents (H0b/S6) proved the envelope pattern; portal-native sub-agents have no current consumer; revisit on a concrete need. |


