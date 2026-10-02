# External MCP Integration Plan (AI_PLAN Phase E)

Status: **approved, not started (2026-10-02)** — parked until a concrete external
MCP server actually needs connecting. Extracted from `plan/AI_PLAN.md` Phase E
(deferred 2026-09-29 pending manifest tools; unblocked since D shipped; owner
confirmed the intent to connect external MCP servers eventually). Related:
`plan/AI_PLAN.md` (phases A–G), `docs/agent-protocol.md` (tool contracts),
`plan/AI_MODULES_PLAN.md` (module tools precedent).

## Goal

The portal agent can consume **external MCP servers**: their tools appear in the
tool catalogue next to builtin and manifest tools, callable through the same
dispatcher, audit, confirmation, and RBAC discipline — configured by admins at
runtime, with secrets encrypted at rest and fail-soft health.

## Decision record (E1 — resolved here, before coding)

| Decision | Choice | Rationale |
|---|---|---|
| Client vs. server | **Client only** | Portal-as-MCP-server (old E5) is a **no-go** (2026-10-02) — same rationale as the F4 drop: no current consumer; revisit only on a concrete external-agent need. |
| Transport | **MCP `2026-07-28` Streamable HTTP** | Current spec revision is stateless: POST-only JSON-RPC, no `initialize` handshake (retired), no `Mcp-Session-Id`, no GET SSE stream; every request carries `MCP-Protocol-Version` + `Mcp-Method`/`Mcp-Name` headers and `_meta` (protocolVersion, clientInfo, clientCapabilities). Fits the portal's request/response HTTP model exactly. stdio is out of scope (the dockerized portal cannot spawn local processes); the legacy `2024-11-05` HTTP+SSE transport is deprecated — out of scope. |
| Client library | **Hand-rolled** over the shared RestClient config | Repo precedent: hand-rolled Graph client (no SDKs). The v1 surface is two RPCs (`tools/list`, `tools/call`) + optional `server/discover`; an SDK pulls heavy deps for that. Pin the spec revision in one constant so a future bump is a code review, not an accident. |
| Config surface | **DB-backed admin registry** (new `mcp_servers` table), NOT `tenant.json` | Tenant config holds *policy* (desired state re-asserted by the reconciler); MCP servers are runtime infrastructure config like LLM provider tokens — admin-managed CRUD under `portal-ai-hub-edit`, secrets encrypted via `CryptoService` (AES-256-GCM), never in YAML/JSON files. |
| Consumed MCP surface | **Tools only** | `resources`/`prompts` have no portal consumer; `sampling`/`roots`/`logging` are deprecated in `2026-07-28`. |
| Auth | **Static `bearer` / custom `header` credentials** | OAuth 2.1 flows deferred (recorded under Deferred) — revisit when a real server requires them. |

## Work items

| # | Item | Detail |
|---|---|---|
| M1 | `mcp_servers` table (Flyway `V<n>__mcp_servers.sql`) | Columns: `key` (KEY_RE, unique), `name`, `url` (validated), `auth_type TEXT CHECK (none\|bearer\|header)`, `auth_secret_enc TEXT` (CryptoService, never read back in output), `auth_header TEXT` (custom header name for `header` type), `enabled BOOLEAN DEFAULT true`, `required_roles TEXT` (comma-joined, empty = any authenticated user), `allowed_tools TEXT` (comma-joined allow-list, empty = all), `created_at`/`updated_at`. |
| M2 | `McpClient` (`modules/agent/mcp/`) | One POST per RPC against `{url}` (the MCP endpoint): assemble `MCP-Protocol-Version: 2026-07-28`, `Mcp-Method`, `Mcp-Name`, auth headers, JSON-RPC body with `_meta` (protocolVersion + clientInfo `{name: "crosshubber-portal"}`). `tools/list` → map `inputSchema` → `AgentTool.arguments`, `annotations` (`readOnlyHint`/`destructiveHint`) → `mutates`; `tools/call` → result content (text blocks) flattened to the tool-result payload the dispatcher already consumes; shared timeouts (10s connect / 30s read); no `@Transactional` (TX hygiene). |
| M3 | Registry + dispatcher wiring | `Kind.MCP` in `AgentTool.Kind`; `ToolRegistry` hydrates enabled servers alongside module `RemoteCatalog`s (60 s cache, same skip-guard semantics); registry id `mcp:<serverKey>:<toolName>`, model-facing name `mcp_<serverKey>_<toolName>` with `-`→`_` flattening (provider-safe naming precedent); **collision check at config save** — a server key + tool name pair may not collide with an existing registry id (422/400 envelope at save time); dispatcher gains a `Kind.MCP` branch (calls `McpClient.call`, maps failures to narratable tool-result errors — fail-soft, never a 500); `required_roles` enforced portal-side (B4 discipline); `allowed_tools` filters the hydrated list. |
| M4 | Admin CRUD + settings UI | `GET/POST/PUT/DELETE /api/mcp/servers` (`@PreAuthorize("hasRole('portal-ai-hub-edit')")`; POST → 201, DELETE → 204 per house status-code conventions; secret fields masked via a presence flag, never echoed); URL validated through `SsrfGuard.assertSafeUrl` (private-IP policy identical to module manifests); AI Hub settings gains an "MCP servers" card following the providers-page pattern: server list + add/edit form + "Test connection" button (runs `tools/list` and shows the tool count). |
| M5 | Health + fail-soft | Per-server health state (cached, refreshed on `tools/list` attempts; states: `unknown`/`ok`/`unreachable`); `GET /api/config` services list gains one row per configured MCP server (`ShellServiceDto`: key/name/url/hint = state — same pattern as NATS); an unreachable or disabled server's tools simply vanish from the catalogue ≤60 s (same no-eviction decision as `ToolRegistry`/proxy caches) and portal boot is never blocked by MCP. |
| M6 | Tests | Fake MCP server on the JDK built-in `HttpServer` (ProxyServiceTest precedent) implementing `tools/list` + `tools/call` with spec-correct headers: happy paths, `MCP-Protocol-Version` enforcement, JSON-RPC error mapping, HTTP 4xx/5xx mapping, annotation→mutates mapping, `allowed_tools` filtering, name collision rejection, disabled/unreachable fail-soft, secret masking + no-leak logging assertions, `McpClient` unit tests (header/meta assembly). |
| M7 | Docs + live verify | `docs/agent-protocol.md` gains an "External MCP servers" section (namespace, mutates mapping, fail-soft, auth); README design notes (namespace + fail-soft + 60s catalogue semantics); AGENTS.md note. Live dev scenario: repo-owned stub MCP server (compose service) → admin adds it in AI Hub settings → chat prompt exercises `mcp_stub_*` tool → tool row + audit row; stop the stub → the agent narrates the tool as unavailable, portal stays healthy; delete the server row → tools gone ≤60 s. |

## Deferred (recorded, no-go for v1)

- **OAuth 2.1 authorization flows** for MCP servers (the `2026-07-28` spec hardens
  authorization) — v1 ships static `bearer`/`header` credentials only. Revisit
  when a real external server requires OAuth.
- **Streaming tool results / SSE response bodies** — v1 consumes JSON responses
  only.
- **resources / prompts surfaces** — no portal consumer.
- **E5 portal-as-MCP-server** — no-go (2026-10-02, F4 rationale).

## Open questions (decide at implementation start)

1. **Compose stub server**: repo-owned Java MCP stub (new `modules/` member or
   `test/` infra) vs. an official reference server image — decide at M7 start.
2. **Tool-description budget**: long MCP tool descriptions inflate the prompt
   catalogue — cap or truncate at hydration (measure first; the same problem
   will exist for module tools eventually).

## Estimates

| Item | Days |
|---|---|
| M1 migration + entity | 0.5 |
| M2 client | 1.5 |
| M3 registry/dispatcher | 1 |
| M4 CRUD + UI | 1.5 |
| M5 health | 0.5 |
| M6 tests | 1.5 |
| M7 docs + live verify | 0.5 |
| **Total** | **~7** |

## Decision log

| Date | Decision |
|---|---|
| 2026-09-29 | AI_PLAN open question 4 resolved: D (manifest tools) strictly first; E deferred. |
| 2026-10-02 | Owner confirmed external MCP servers are wanted eventually; Phase E extracted to this file with the full decision record above; status: approved, not started — build when a concrete external server exists. Transport pinned to MCP `2026-07-28` Streamable HTTP; hand-rolled client; DB-backed config (tenant.json stays policy-only); secrets via CryptoService; E5 no-go; static auth only. |
