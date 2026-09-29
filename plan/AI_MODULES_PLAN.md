# AI Dogfood Modules Plan — `solutions` & `staffing`

Two production-shaped remote modules for a consultancy agency that force the portal
through the full AI capability stack end to end: session context pack, tool registry,
manifest `agentContributions` v2, HTTP sub-agents, module-owned knowledge (RAG), and
the audit/confirmation UX. They are real domain modules, not stubs — the AI plan
(`plan/AI_PLAN.md`) phases A/B/C/D/F/G become concrete deliverables because these
modules need them.

Status: **planned — no implementation started.** Each work block below needs its own
go decision. Related: `plan/AI_PLAN.md` (phases A–G), `plan/UX_PLAN.md` (Phase C
dependency), README "Module manifest" (integration contract).

## Design principles

- **Modules are remote from day one.** Each module ships its own Spring Boot 4
  backend + Angular custom-element frontend, is installed via
  `{baseUrl}/.well-known/portal-module.json` through the existing registry pipeline,
  and is registered in dev via tenant config `modules.external[]` (the
  `huey`/`louie-mfe` pattern already wired in `Reconciler.fetchExternalManifests`).
  This is the truest end-to-end test of the integration surface: manifest fetch,
  validation, install, sandboxed MFE loading via `/api/mfe`, role sync.
- **Portal owns orchestration, modules own capability** (AI_PLAN principle). Module
  backends expose agents/tools over HTTP; the portal discovers them from the manifest
  and dispatches to them. No module-specific logic in portal code.
- **Repository credentials never leave the portal.** The document-source abstraction
  (connectors for OneDrive/S3/SharePoint) lives portal-side; modules reference
  documents by ref and call portal APIs. Module backends hold no repo credentials.
- **The user's identity travels with every agent call.** The portal forwards the
  user's OIDC access token to module agents/tools; module backends validate it
  (issuer/signature) and map portal roles. A tool never runs with elevated rights.
- **Agent actions are ordinary module APIs first** — mirrors AI_PLAN: a tool handler
  is a typed call into the module's own services with role enforcement.
- **Tests over parity.** Every layer has unit tests; the E2E scenarios in the last
  section are the acceptance bar. `mvn verify` + `npm test` per repo, green.

## Architecture decisions (2026-09-29)

| Decision | Choice | Rationale |
|---|---|---|
| Module shape | Remote MFE day one (backend + MFE per module) | Exercises manifest install, remote dispatch, HTTP sub-agents for real |
| Repo layout | `modules/solutions/{server,ui}`, `modules/staffing/{server,ui}` | Mirrors `portal/{server,ui}`; compose adds both services |
| Sub-agent transport | HTTP, user token forwarded (replaces AI_PLAN F3/F4 "in-process first") | Modules are separate processes; AI_PLAN E lessons absorbed up front |
| Doc repositories | Portal-side `modules/docsource` port + OneDrive (Microsoft Graph) adapter v1; S3/SharePoint later | Storage-agnostic per owner decision; credentials centralized/encrypted portal-side |
| CV source | OneDrive CV library via docsource | Reuses the same connector; no greenfield file storage |
| Module ↔ portal auth | Forwarded user OIDC token; Keycloak client per module backend; audience validation module-side | No separate service-token stack in v1; auditability stays per-user |
| AI config ownership | Per-module agent settings (prompt, model, tools, caps) in each module's `module_settings` key | AI Hub remains the single provider/token authority; modules pick from it |
| Sub-agent scope | `solutions` ships a module-owned agent; `staffing` ships tools only | Splits the risk: staffing proves tools+RAG, solutions proves sub-agents |

## Portal-side prerequisites (delta over AI_PLAN)

These are AI_PLAN phases plus the deltas the remote decision forces:

| # | Work item | Detail |
|---|---|---|
| P1 | AI_PLAN **A** (context pack) | As scoped 2026-09-29: A1–A3 + A5, A4 deferred. |
| P2 | AI_PLAN **B** (tool registry + dispatcher + audit + SSE frames) | Registry, RBAC dispatch, `agent_tool_calls` audit table, `needsConfirmation` for mutating tools, new SSE frames (`tool_call`, `tool_result`, `confirmation_required`, `citation`, `error`, `done`) documented in a protocol doc. |
| P3 | **Remote tool dispatch** (B delta) | Registry entries gain `kind: builtin | remote`; remote entries carry `path`/method resolved against the module `baseUrl`. Calls use the shared `RestClient` timeouts, run **outside** `@Transactional` (TX hygiene invariant), fail-soft (unreachable module → tool-result error the model can narrate, never a 500). |
| P4 | AI_PLAN **D** (manifest v2) | `tools[]` with `arguments` (JSON Schema), `mutates`, `roles[]`, optional `path` override; `skills[]`; `agents[]`; `knowledge[]`. Backwards compatible with v1 empties. |
| P5 | **Module auth** | Keycloak client per module backend; portal validates module tokens it issues; module backends validate forwarded user tokens (issuer + signature, map `portal-*`/module roles). Document the token flow in the protocol doc. |
| P6 | **`modules/docsource`** | See spec below. Port + OneDrive adapter + REST API + text extraction. |
| P7 | AI_PLAN **F** (sub-agent orchestrator, HTTP shape) | F1 envelope; F2 registration from manifest `agents[]`; F3 orchestrator with depth cap 1 and same-user identity (token forwarding); results fold back as tool-result-like frames. Home: new `modules/agent` package (resolves AI_PLAN open question 2). |
| P8 | AI_PLAN **G** (knowledge v1) | Fetch-on-demand + truncate (G2) over docsource; citations `citation` frames (G3); tenant-scoped, no cross-tenant leakage (G4). |
| P9 | AI_PLAN **C** UX (C3/C4) | Tool transparency rows, confirm dialog reuse, "Agent tool calls" audit list under AI Hub settings. |

## `modules/docsource` — document repository abstraction (P6 spec)

Storage-agnostic port so tomorrow's S3/SharePoint connectors slot in without touching
consumers.

- **Port** (`DocumentSource`): `testConnection(config)`, `listContainers(config, parentRef)`,
  `listDocuments(config, containerRef)`, `fetchDocument(config, documentRef) → DocumentContent { name, mimeType, size, text? }`.
  Registry of adapters keyed by `kind` (`onedrive` v1; `s3`, `sharepoint` future).
- **Config**: stored per module key in `module_settings` under a `docsource` section:
  `{ kind, rootContainer, credentials (encrypted via CryptoService), options }`.
  Browsing/selection writes happen through the module settings page.
- **REST API** (portal): `GET /api/docsource/{moduleKey}/containers?parent=`,
  `GET /api/docsource/{moduleKey}/documents?container=`,
  `GET /api/docsource/{moduleKey}/documents/{ref}/content`. Gated by the module's
  declared `securityRoles` (same `isModuleManager` pattern as module settings).
- **OneDrive adapter**: Microsoft Graph via the shared `RestClient` (HttpClientConfig
  10s/30s) — hand-rolled, no Graph SDK (matches the shared-HTTP-client invariant).
  Client-credentials flow, token cached with expiry; `GET /drives/{id}/root:/{path}:/children`
  for listing, `GET …/content` for download. Fixed host — SSRF not applicable here;
  future URL-kind sources must go through `SsrfGuard` (the only current user is
  `ManifestFetcher`).
- **Text extraction**: `text/markdown/plain` native; PDF/DOCX via Apache Tika
  (single portal-side dependency — needs approval, see open questions). Images out of
  scope v1.
- **RAG strategy** (AI_PLAN G2): fetch-on-demand + truncate per selection; no vector
  store until measured quality demands it. Per-document selection state
  (`ragEnabled`) lives in module data, not docsource.

## Module 1 — `solutions` (project delivery tracking)

Used by the solutions area to track engagements from market lead to closed delivery,
with a module-owned agent and RAG over project documentation.

### Workflow

Stages (linear spine with terminal branches):

```
lead → qualified → proposal → sent → negotiation → won → implementation → delivery → closed
                ↘ lost (terminal)     ↘ lost (terminal)          ↘ lost (terminal, from sent too)
```

Allowed transitions: `lead→qualified|lost`; `qualified→proposal|lost`;
`proposal→sent|lost`; `sent→negotiation|won|lost`; `negotiation→won|lost|sent`
(revised version re-sent); `won→implementation`; `implementation→delivery`;
`delivery→closed`. `lost` and `closed` are terminal. Every transition writes an
immutable `stage_event` row (from, to, actor, note, timestamp) — no edits, no deletes.

Stage gates (data required to enter the stage; validated server-side):

| Stage | Gate data |
|---|---|
| `lead` | name, client, source (`inbound\|referral\|event\|campaign\|partner`), market, contact, owner |
| `qualified` | goDecision (`go\|no-go\|parked`), pursuitOwner, estimate (band or €), decisionDate |
| `proposal` | sections scope/team/pricing/timeline each `draft\|final`, internalReview (reviewer, verdict) |
| `sent` | sentDate, version, channel |
| `negotiation` | version, clientFeedback, nextStep |
| `won` | contractRef, budget, startDate |
| `lost` | reason (`price\|scope\|competitor\|no-decision\|other`), lessons |
| `implementation` | kickoffDate, milestones[], budgetBurn, health (`on-track\|at-risk\|delayed`) |
| `delivery` | uatDate, acceptanceCriteria, signOff (by, date) |
| `closed` | handoverRef, retrospective |

### Entities (module schema)

`client` (name, industry, accountOwner, contacts[]), `project` (client, name, stage,
owner, budget, health, `stageData` JSONB validated per stage, optimistic lock),
`stage_event` (immutable), `document_link` (project ↔ docsource `documentRef`, title,
`ragEnabled` flag, addedBy), `note` (project, author, body).

### UI (MFE, custom element)

Pipeline board grouped by stage; project detail (info, stage history, linked documents
with RAG toggles via docsource browse, notes); client list. OneDrive folder browsing
and per-document RAG selection happen here.

### Module-owned agent ("Projects Agent")

HTTP sub-agent endpoint `POST /agent/tasks` (AI_PLAN F1 envelope). Capabilities:
answer project status questions from live data, narrate stage history, draft proposal
sections grounded in linked docs (RAG via portal docsource). The portal agent
delegates to it after install — "when registering the module the portal agent can
communicate with the module's agent" falls out of `agents[]` hydration (P7).

### Tools (declared in manifest, remote kind, callable by any agent with the roles)

| Tool | Kind | Notes |
|---|---|---|
| `solutions.list_projects` | read | filter by stage/health/client |
| `solutions.get_project` | read | full record incl. stage data |
| `solutions.get_stage_history` | read | stage_event trail |
| `solutions.update_project_stage` | **mutates** | gated transition validation; `needsConfirmation` flow |
| `solutions.search_project_docs` | read | RAG over `ragEnabled` links; module resolves links → fetches via portal docsource API (forwarded user token) → extracts → ranks → returns snippets with citations |

### Settings page (`content.adminSettings` entry, MFE in portal Settings tree)

Agent config (enabled, system prompt, model/provider/token picked from AI Hub
providers via portal API, max iterations), tool toggles (each tool on/off, per-tool
role narrowing), OneDrive root container config (writes the `docsource` section via
portal API), RAG limits (max docs per query, truncation budget).

## Module 2 — `staffing` (body shop / RFP→CV matching)

Used by the outsourcing area: react to RFPs/RFQs, analyze the CV repository, produce
ranked shortlists. Proves tools + RAG without a module-owned agent.

### Flow

Register RFP/RFQ (manual in v1; spec document attachable from a docsource container)
→ requirements extraction (LLM-assisted, schema-validated) → match run → shortlist →
`submitted`/`archived`. Statuses: `new → analyzing → matched → shortlisted →
submitted → archived`. Graph watch-folders for automatic RFP ingest are explicitly
**deferred** (documented; revisit after E transport lessons).

### CV ingestion

CVs live in a OneDrive CV library (docsource). Scan job lists the container, fetches
each document, extracts text (Tika), runs LLM-assisted structured extraction
(skills, seniority, languages, availability, headline) into `candidate_profile`
rows cached in the module DB; re-scan on demand marks stale profiles. No raw CV
cache — always re-fetch content by ref.

### Entities (module schema)

`rfp` (client, title, kind `rfp|rfq`, status, deadline, requirements JSONB,
specDocRef), `candidate_profile` (sourceRef, name, headline, skills[], seniority,
languages[], availability, parsedAt, status `parsed|stale`), `match_run` (rfp, params,
status `running|done|failed`, results JSONB [{candidateId, score, rationale}]),
`shortlist` (rfp, candidateIds[], createdBy).

### Matching strategy v1

Keyword + structured scoring over parsed profiles (skills overlap, seniority fit,
availability) with LLM rationale generated for the top N only. No embeddings in v1
(same reasoning as AI_PLAN G2); revisit if ranking quality demands it.

### Tools (for the portal agent)

| Tool | Kind | Notes |
|---|---|---|
| `staffing.list_rfps` | read | filter by status |
| `staffing.get_rfp` | read | incl. extracted requirements |
| `staffing.search_cvs` | read | query → structured profiles |
| `staffing.match_candidates` | read | rfpId or ad-hoc requirements + topN → ranked list with rationale |
| `staffing.create_match_run` | **mutates** | persists a match run; `needsConfirmation` flow |

### Settings page

Tool toggles, CV library container config (docsource), matching params (topN, weights),
extraction model selection via AI Hub.

## Manifest `agentContributions` v2 (concrete shapes, D spec)

```json
{
  "manifestVersion": 1,
  "key": "solutions",
  "name": "Solutions",
  "baseUrl": "http://solutions:8090",
  "content": { "applications": [ { "key": "pipeline", "name": "Project Pipeline", "type": "mfe", "path": "/mfe/main.js", "element": "solutions-pipeline", "requiredRoles": ["solutions-user"] } ],
               "adminSettings": [ { "key": "settings", "name": "Solutions Settings", "type": "mfe", "path": "/mfe/settings.js", "element": "solutions-settings", "requiredRoles": ["solutions-admin"] } ] },
  "security": { "roles": [ { "key": "solutions-user", "name": "Solutions User" }, { "key": "solutions-admin", "name": "Solutions Admin" } ] },
  "agentContributions": {
    "tools": [
      { "name": "list_projects", "description": "List delivery projects with stage/health filters",
        "arguments": { "type": "object", "properties": { "stage": { "type": "string" }, "health": { "type": "string" } } },
        "mutates": false, "roles": ["solutions-user"] },
      { "name": "update_project_stage", "description": "Move a project to the next workflow stage",
        "arguments": { "type": "object", "properties": { "projectId": { "type": "string" }, "toStage": { "type": "string" }, "note": { "type": "string" } }, "required": ["projectId", "toStage"] },
        "mutates": true, "roles": ["solutions-user"] }
    ],
    "skills": [],
    "agents": [
      { "name": "projects-agent", "description": "Delivery status, stage history narration, proposal drafting from project docs",
        "endpoint": "/agent/tasks", "roles": ["solutions-user"] }
    ],
    "knowledge": []
  }
}
```

Tool dispatch convention (P3): a tool named `X` of module `M` is invoked as
`POST {baseUrl}/agent/tools/X` with the F1 envelope body unless the entry overrides
`path`. Module `staffing` uses the same shape minus `agents[]`.

## Sequencing

```
H0a: P1 (A) + P2 (B) + P3 (remote dispatch) + P4 (D) + P5 (module auth)   ← portal core
H1:  P6 docsource port + OneDrive adapter                                  ← portal core
H2:  solutions domain backend + UI (no AI)          ┐ parallel
H3:  staffing domain backend + UI (no AI)           ┘ (independent of H0b)
H0b: P7 sub-agent orchestrator (modules/agent) + hydration wiring           ← portal core
H4:  install both modules via registry; tools/agent catalogue live (B2/D3 first real consumers)
H5:  RAG integration (P8): search_project_docs, match rationale, citations
H6:  C-phase UX (P9) + E2E acceptance scenarios below
```

H0a → H4 is the critical path; H2/H3 run in parallel once their manifest keys/roles
are fixed (they only need P4 for shape). E (MCP) stays out of scope — D is strictly
higher leverage, per AI_PLAN open question 4 (now resolved).

## E2E acceptance scenarios (H6 exit criteria)

| # | Scenario | Proves |
|---|---|---|
| S1 | Install both modules via registry UI from their baseUrls; agent catalogue lists their tools + agent | D hydration, remote dispatch wiring, RBAC |
| S2 | As dev: "Which projects are at risk?" → context pack shapes the question, `solutions.list_projects` called, narrated answer | A (context pack) + B dispatch + SSE frames |
| S3 | "Move project X to delivery" → `confirmation_required` frame → UI confirm → stage transition + audit row; as devuser without role → narrated refusal, no mutation | B4/B6 + audit + RBAC matrix |
| S4 | "Summarize the proposal docs for project X" → `solutions.search_project_docs` → answer with `citation` frames | docsource + G retrieval + citations |
| S5 | "Find best matches for RFP Y" → `staffing.match_candidates` → ranked shortlist with rationale | staffing tools + structured scoring |
| S6 | Delegate: "Ask the solutions agent for project X's status" → sub-agent envelope round-trip folds into transcript | F orchestrator + identity propagation |
| S7 | Uninstall a module → its tools/agent/knowledge vanish from the catalogue; audit rows retained | registry lifecycle |

## Testing

- **Portal**: registry/dispatcher/pack-builder unit tests; manifest v2 validator
  fixtures (valid/invalid tools/agents/knowledge); SSE frame protocol snapshot tests;
  docsource port tests with a fake adapter + OneDrive adapter contract tests against a
  stub Graph server; `PortalSmokeTest` extension (testcontainers) covering remote
  dispatch against a stub module server.
- **Modules**: each module repo runs its own `mvn verify` + `npm test` (Testcontainers
  for module DBs); workflow transition matrix and matching scorer are the highest-value
  unit targets.
- **Compose**: `solutions` + `staffing` services join the dev stack; boot-time
  registration via tenant config `modules.external[]` (fail-soft retries already
  exist).

## Open questions

1. **Azure app registration** for the dev tenant (Graph client-credentials) — external
   dependency; needs owner/secret provisioning via compose env, never in code.
2. **Apache Tika** dependency for PDF/DOCX extraction — size/CPW approval needed.
3. **MFE frontend stack** for the two modules: Angular 22 custom elements (stack
   consistency, heavier bundles) vs a lighter web-component build. Default: Angular
   custom elements.
4. **CV cache staleness policy**: re-scan on demand only, or scheduled refresh? v1:
   on demand + stale marker.
5. **Compose budget**: two more JVMs + module schemas on the shared Postgres — sizing
   check before H2/H3.

## Decision log

| Date | Decision |
|---|---|
| 2026-09-29 | Plan authored. Remote MFE day one for both modules; portal-side storage-agnostic `docsource` abstraction (OneDrive first); HTTP sub-agents with forwarded user tokens; `solutions` ships a module agent, `staffing` ships tools only; separate plan file with Phase H pointer in AI_PLAN. |
