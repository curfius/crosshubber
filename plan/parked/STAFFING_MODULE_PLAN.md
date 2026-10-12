---
status: parked
domain: staffing
summary: "Staffing Module v2 domain plan — unified plan superseding 7 exploratory docs; parked per decision"
---

# Staffing Module Development Plan (v2)

Status: **draft for review — 2026-10-09**. Supersedes the seven exploratory docs in
`.opencode/plans/` (ENHANCED_WORKFLOW_PROPOSAL, ACTORS_AND_ROLES_DEFINITION,
NOTIFICATION_AND_TASK_SCHEMA, EVENTS_SPECIFICATION, ADMIN_SETTINGS_EXPANSION,
ENHANCED_AGENTIC_TOOLS, DEPENDENCIES_AND_INTEGRATIONS). Those remain as raw
reference material; this file is the single working plan.

Owner decisions baked in (2026-10-09):
- Matching: **configurable engine, JEV combined** — structured Job/Environment/Value fit
  assessment with a selectable scoring backend (rules / external Jev API / LLM).
- RFP phases: **derived from sub-processes**; manual only for approve / close / reject /
  archive. Close shows a confirmation dialog and **cancels unfinished candidate processes**.
- App structure: **4th Talent app** (capture / ongoing / talent / analytics + settings).

---

## 1. Context and business model

We are a **staffing agency** consuming the Crosshubber SaaS portal:
- Clients send **RFP/RFQ staffing needs** (MVP: entered manually by an account manager;
  automation — primarily email capture — comes later).
- We deliver either **outsourcing (time & materials staff augmentation)** or
  **headhunting (direct placement)**. Service type is recorded per RFP with a
  per-position override (mixed RFPs are possible).
- Success depends on two knowledge bases the module must own:
  1. **Talent pool** — existing consultants (current assignment, allocation, "what they
     are doing", bench status) **and** potential candidates accumulated from past and
     ongoing sourcing, kept fresh through an enrichment/staleness framework.
  2. **Client intelligence** — as much as possible about the client beyond the RFP text
     (contacts, history, preferences), enriched from the tenant's CRM when connected.

**SaaS / multi-ecosystem constraint**: at install time we do *not* know which CRM, ATS,
document store, comms, or HRIS a tenant runs. Therefore:
- Every external integration goes behind a **module-owned port (interface)**.
- MVP ships an **emulated implementation per port** (fixtures, deterministic, offline) —
  extending the existing `DocumentSource`/`FakeDocumentSource` pattern.
- Off-the-shelf adapters for popular products are later slices, selected at **runtime via
  module settings** (per tenant), not at build time.
- Platform-level gap-filling story for tenants: manifest tools (this module), custom
  modules (module directory), and the approved-but-unstarted **external MCP server**
  plan (`plan/EXTERNAL_MCP_INTEGRATION_PLAN.md`) for tenant-specific systems.

The portal remains the integration point; the staffing module only owns its own ports.

---

## 2. Domain model

```
Client 1 ─── * Rfp 1 ─── * Position 1 ─── * CandidateProcess * ─── 1 Candidate
                 │                              │
                 └── documents, analysis        └── stage history, fit assessment
```

| Entity | Purpose | Notes |
|---|---|---|
| `Client` (new) | Client intelligence: name, industry, contacts[], notes, external refs (CRM ids when connected), RFP history (computed) | MVP lightweight, module-owned; CRM adapter enriches when configured |
| `Rfp` (existing, extended) | Engagement header: title, kind (rfp/rfq), serviceType (tm\|direct), deadline, client FK, phase (derived/stored), `source` (manual\|email), `analysis` jsonb (AI-extracted), documents, createdBy (staffing-manager) | Replaces current status semantics (see §14 migration) |
| `Position` (new) | Req line inside an RFP: title, headcount, serviceType override, requirements jsonb (skills/seniority/languages/availability/rate…), derived status (open→filling→filled\|cancelled) | Answers "1 RFP, multiple positions" |
| `CandidateProcess` (new) | **The sub-process**: one candidate × one position. Own stage, stage history jsonb, fit assessment jsonb, notes | Forks freely — interview #1 while #2 is submitted is the normal case |
| `Candidate` (extend `CandidateProfileEntity`) | Talent pool record: profile (skills, seniority, languages), kind (internal consultant \| external), **current assignment** (project, client, allocation %, start/expected end, bench), expectations (rate, location, remote, notice), contact (emulated source), consent/GDPR, enrichment status (lastEnrichedAt, stale) | Internal consultants = billable staff; external = sourcing pipeline |
| `Submission` (evolves `ShortlistEntity`) | Batch of candidate processes sent to the client at a point in time: rfpId, processIds[], sentBy, sentAt, client response | "shortlist" today becomes "submission to client" |
| `MatchRun` (existing, extended) | Persisted ranking run + `engine` field | Kept as-is plus engine provenance |
| `FitAssessment` (jsonb on process) | J/E/V verdicts per dimension (see §9) | Produced by configured engine |
| `RfpPhaseHistory` (new table or jsonb) | Phase changes: from, to, at, by, trigger (manual\|derived) | Feeds analytics time-in-phase + funnel |

---

## 3. Workflow

### 3.1 RFP macro phases (derived)

```
DRAFT ──(manager approves)──▶ APPROVED ──(first candidate process opened)──▶ SOURCING
   ──(≥1 offer outstanding)──▶ OFFER_EXTENDED ──(≥1 offer accepted)──▶ ACCEPTED
   ──(accepted ≠ all onboarded)──▶ PENDING_ONBOARDING ──(all positions done)──▶ CLOSED ▶ ARCHIVED

REJECTED: engagement declined/cancelled before successful placement (manual, manager, reason required).
```

- **Derived** (stored on the row, recomputed by a `PhaseDerivationService` after every
  candidate-process mutation): `SOURCING`, `OFFER_EXTENDED`, `ACCEPTED`,
  `PENDING_ONBOARDING` — including regressions (e.g. accepted candidate withdraws →
  recompute; all offers rejected with live positions → back to `SOURCING`).
- **Manual** (validated actions): `DRAFT→APPROVED` (staffing-manager), any active
  phase → `CLOSED` or `REJECTED` (staffing-manager, **confirmation dialog** — close
  cancels all unfinished candidate processes and records which ones were cancelled),
  `CLOSED/REJECTED → ARCHIVED`.
- Every phase change writes phase history (trigger: manual|derived).

**Aggregation rules (proposal — confirm during review):**

| Phase | Enters when |
|---|---|
| DRAFT | created by manager in capture |
| APPROVED | manager approves (manual) |
| SOURCING | first candidate process opened for any position |
| OFFER_EXTENDED | ≥1 candidate process in `OFFER` stage |
| ACCEPTED | ≥1 candidate process in `ACCEPTED` stage |
| PENDING_ONBOARDING | ≥1 accepted process not yet `ONBOARDED`, no more outstanding offers |
| CLOSED | manual; all positions filled/cancelled or engagement concluded |
| REJECTED | manual with reason; engagement lost/cancelled pre-placement (open point §17) |
| ARCHIVED | terminal |

### 3.2 Candidate process stages (the sub-process)

```
POOLED ──▶ ASSESSED ──▶ SUBMITTED ──▶ INTERVIEWING ──▶ OFFER ──▶ ACCEPTED
   │            │            │              │            │          │
   │            │            └─ REJECTED ◀──┴── REJECTED └─ REJECTED
   └─ WITHDRAWN (candidate exits at any point; terminal)
                                              ACCEPTED ──▶ PENDING_ONBOARDING ──▶ ONBOARDED (terminal)
```

- Each process carries its own stage history (jsonb: stage, at, by, note) — the forking
  behaviour you described (interviewing #1 while #2 was already submitted) falls out
  naturally because stages live on the process, never on the RFP.
- `REJECTED` (by client) / `REJECTED`-offer / `WITHDRAWN` are terminal for *that*
  process; the RFP keeps going with sibling processes. A new process for the same
  candidate+position can be opened explicitly (re-engagement with reason).
- Client submission = creating/updating a `Submission`; submission status (pending /
  interviewed / feedback) tracked on the submission.

### 3.3 Why not the v1 linear workflow

v1's 14-stage linear chain (ENHANCED_WORKFLOW_PROPOSAL) is **dropped**: it cannot model
multiple positions or parallel candidate tracks. Only the *ideas* survive: SLA/phase
history data collection, iteration paths (now implicit in per-process stages).

---

## 4. Roles (3, replacing 4)

| Role | Who | Can |
|---|---|---|
| `staffing-manager` | Internal requester = **account manager** for the client (merges v1 staffing-requester) | Capture/create RFPs, review+edit AI draft, APPROVE, client card, submissions oversight, CLOSE/REJECT (dialog), analytics, own drafts |
| `staffing-recruiter` | Recruitment/sourcing team | All ongoing operations: open candidate processes, source/assess candidates, run matching, submit, record interviews/offers/outcomes, manage talent pool |
| `staffing-admin` | Module admin | Settings (connectors, matching engines, tool toggles), everything else read-only |

- The **client's hiring manager is external** — no portal account (client portal: out of
  scope, future).
- `staffing-user` is **retired** — migration note in §14 (existing installs: keep the
  role declared for one release as a deprecated alias or accept the breaking change).

App ↔ role visibility:

| App entry | key | requiredRoles |
|---|---|---|
| RFP Capture | `capture` | staffing-manager, staffing-admin |
| Ongoing RFPs | `ongoing` | staffing-manager, staffing-recruiter, staffing-admin |
| Talent Pool | `talent` | staffing-manager, staffing-recruiter, staffing-admin |
| RFP Analytics | `analytics` | staffing-manager, staffing-admin |
| Staffing Settings | `settings` (adminSettings) | staffing-admin |

---

## 5. Apps (one bundle, four + settings elements)

Proven pattern: N `shell.applications` entries sharing `path: /mfe/staffing.js`,
`main.ts` registers N custom elements (solutions already registers 2; validator imposes
no cardinality limit).

### 5.1 Capture (light)
- Form: client (pick existing / create), title, kind, service type, deadline,
  free-text description, document upload (via DocumentStore port).
- **AI analysis** (module `LlmClient`, reuse solutions pattern): produces structured
  draft — positions[] with requirements, deadline, extracted entities, confidence notes.
  Manager reviews/edits the draft (no silent auto-approve).
- Save → `DRAFT`; **publish `staffing.rfp.captured`** (§10); APPROVE action →
  `APPROVED` + publish.
- App has no complex views: drafts list + draft editor. Automated capture (email → this
  pipeline) is explicitly **out of MVP** but the event contract is built now.

### 5.2 Ongoing (robust — the core app)
- Board grouped by **derived RFP phase** (filters, client, service type).
- RFP detail:
  - Header: phase badge + phase history trail (who/when/trigger), client card
    (inline: contacts, past RFPs, notes), documents.
  - **Positions list**, each expandable to its own candidate-process board
    (columns = process stages) → the forking view.
  - Process card: candidate summary, J/E/V fit verdicts, stage actions
    (assess, submit, schedule interview record, offer, accept/reject/withdraw) — all
    stage transitions validated server-side.
  - Submissions timeline (sent/response), activity log.
  - CLOSE/REJECT buttons → confirmation dialog (close: lists unfinished processes that
    will be cancelled).
- Interview/offer events are **records** (who/when/outcome), not calendar integration
  (calendar port = later).

### 5.3 Talent (4th app)
- Candidate directory: filters (skills, seniority, availability, kind, staleness,
  currently-assignable vs bench), profile detail.
- **Internal consultants**: current assignment block (project/client/allocation/
  expected end), bench days — "what they are doing".
- **Sourcing inbox**: intake from TalentSource port (emulated fixtures for MVP),
  CV scan (existing `scanCv` path), manual add → triage → add to pool with source tag.
- Enrichment/staleness framework: `lastEnrichedAt`, stale flag, "re-enrich" action
  (engine: rules for MVP), consent/GDPR fields on profile.
- Process history per candidate (every RFP/position they went through).

### 5.4 Analytics (light)
- Closed/archived RFPs only: counts by client/service type; **time-in-phase
  distribution** (phase history); candidate-process **funnel conversion** (stage
  history); placements per period; sourcing-source effectiveness (candidates in pool →
  onboarded); matching engine hit-rate (assessment verdicts → outcomes).
- All queries straight off the history tables — no warehouse.

### 5.5 Settings (existing surface, extended)
See §11.

---

## 6. Talent pool & sourcing framework (dependency analysis)

What we must build inside the module (no external dependency):

| Capability | MVP | Later |
|---|---|---|
| Candidate records incl. assignment/bench | ✅ schema + CRUD | assignment sync from HRIS/PSA port |
| Process history ("been through what") | ✅ computed from CandidateProcess | — |
| Expectations (rate/availability/motivation) | ✅ captured during assessment | freshness prompts |
| Intake channels (CV scan, manual, emulated source) | ✅ emulated | real adapters (ATS/job boards/LinkedIn via TalentSource port) |
| Enrichment/staleness framework | ✅ timestamps + re-enrich action | automated scheduled enrichment, LLM enrichment |
| Skills inference | naive dictionary (existing) | LLM-assisted extraction |
| GDPR/consent | ✅ fields + consent flag gating outreach | automated DSAR flows |

External systems (later, behind ports): ATS (Greenhouse/Lever/…), professional networks,
job boards — **emulated in MVP**.

---

## 7. Client intelligence (dependency analysis)

- MVP: module-owned `Client` entity (contacts, notes, history, preferences) maintained
  in Ongoing/Capture. Every RFP links a client; client card aggregates past/active
  RFPs, submission outcomes, and (later) billing rates.
- CRM port: emulated for MVP (fixture client records, fake "opportunity created" log);
  off-the-shelf Salesforce/HubSpot adapters later; selection runtime via settings.
- Consumed CRM events (opportunity updated etc.) are a **later** slice — they need the
  platform's consumed-event story (§10) plus a real adapter.

---

## 8. Matching — configurable engine, JEV combined (owner decision)

Design: **fit structure** is always Job/Environment/Value; the **scoring backend** is
configurable.

| Dimension | Maps to | Signals |
|---|---|---|
| **J**ob fit | profile ↔ requirements | skills, seniority, experience, languages |
| **E**nvironment fit | candidate ↔ work context | location/remote, client/team context, allocation, work conditions |
| **V**alue fit | motivation ↔ offer | rate/compensation expectations, career goals, notice period, motivation notes |

- Each dimension returns a typed verdict `{strong | maybe | skip}` + rationale + score;
  overall verdict derived (worst-of or weighted — configurable).
- `matching.engine` setting: `rules` (default — deterministic, offline, evolution of
  the existing `MatchingService`), `jev` (external TypeSafe Jev decision API:
  baseUrl + masked API key + timeout, one decision call per candidate↔position with the
  J/E/V questions; fan-out ranking), `llm` (module LlmClient with a structured prompt).
- `MatchingService` stays as the always-available baseline (rank + rationale); the new
  engine is what produces **fit assessments** at the ASSESSED stage. Engine choice is
  per-assessment with settings default; MatchRun records engine provenance.
- Unit-test target: verdict aggregation + rules engine (pure functions).

---

## 9. Integrations — ports, emulators, later adapters

Ports owned by the staffing module (Spring interfaces + runtime registry):

| Port | Direction | MVP emulator | Later off-the-shelf |
|---|---|---|---|
| `CrmConnector` | clients/opportunities sync | fixture clients, fake opportunity log | Salesforce, HubSpot, Dynamics |
| `TalentSourceConnector` | sourcing inbox intake | fixture candidates | Greenhouse, Lever, LinkedIn… |
| `DocumentStoreConnector` (extends existing `DocumentSource`) | fetch + store RFP/CV docs | local FS / fake refs | SharePoint, S3, Google Drive |
| `MessagingConnector` | notifications + future email capture | outbox log (viewable in settings for debugging) | SMTP/Outlook/Gmail (Graph) |
| `HrisConnector` | onboarding → employee record | fake employee-id log | BambooHR, Workday |
| `CalendarConnector` (deferred past MVP) | interview scheduling | — | Outlook/Google |

**Key implementation decision**: connector selection moves from the current
`@ConditionalOnProperty(kind)` (boot-time, env-driven) to a **runtime registry**:
all implementations register as beans keyed `(category, kind)`; the active kind comes
from `module_settings` (per tenant, editable in Settings UI — SaaS requirement: one
deployment serves many tenants). Every category must always resolve (default
`emulated`) — never a missing-bean context failure (the known gotcha in the current
FakeDocumentSource pattern).

Secrets in settings are **encrypted at rest** (reuse solutions' `CryptoService`
pattern) and masked on read.

---

## 10. Events, notifications, tasks

### Published (via `POST /api/msgcenter/publish` HMAC — the external-module path)

| Subject (`portal.msg.staffing.*`) | When |
|---|---|
| `rfp.captured` | AI-structured draft ready (capture → ongoing contract) |
| `rfp.approved` | manager approves |
| `rfp.phase-changed` | every derived/manual phase change (from,to,trigger) |
| `rfp.closed` / `rfp.rejected` / `rfp.archived` | terminal transitions |
| `process.stage-changed` | candidate process stage changes (rfp, position, candidate, stage) |
| `process.submitted` / `offer-extended` / `accepted` / `onboarded` | key milestones |
| `candidate.added` / `candidate.enriched` | talent pool intake (other modules + future automation) |

- Manifest `events.published[]` documents them (declaration is documentation-grade in
  the platform today — no backend enforcement; that's fine and matches house style).
- **Intra-module flow**: capture writes the RFP row **and** publishes; ongoing reads the
  DB (source of truth) and can subscribe to NATS for live board refresh (nice-to-have,
  not MVP). The event is the integration contract — msgcenter ingests it into the inbox
  (audit trail) and any tenant module can react (sample-sender precedent: direct NATS
  subscribe).

### Notifications & tasks — reuse msgcenter, don't rebuild
- v1's NOTIFICATION_AND_TASK_SCHEMA (own tables, templates, escalation engine) is
  **dropped**: msgcenter already ships messages→inbox, group addressing, task templates
  (Template Studio), task completion events (`portal.taskresponse.*`), and the email
  channel. MVP = publish milestone messages (they land in the platform inbox / email
  mirror). Deeper workflow (approval tasks with completion events, SLA reminder
  tasks) = phase 2 on top of msgcenter tasks; orchestration/timeout ownership stays
  with `plan/WORKFLOWER_PLAN.md` when it lands.
- Consumed events MVP: **none** (empty `events.consumed[]`). Future: email-channel
  capture and `portal.taskresponse.staffing.*` if we file approval tasks.

---

## 11. Admin settings (module_settings groups)

| Group | Contents |
|---|---|
| `tools` (existing) | per-tool toggles — updated tool list (§12) |
| `matching` (evolves `match`) | `engine` (rules\|jev\|llm), jev baseUrl/apiKey(masked)/timeout, rules topN + dimension weights, verdict aggregation mode |
| `connectors` (new) | per category: `{kind: emulated\|<product>, config jsonb, secrets encrypted}` for crm / talentSource / documentStore / messaging / hris |
| `llm` (new, solutions `agent`-group precedent) | provider, baseUrl, apiKey (masked), model, temperature — powers capture analysis + llm engine |
| `workflow` (new, minimal MVP) | close/reject permissions extras, (later: SLA thresholds) |

Not built (deliberately deferred): v1's 8-category settings mega-list. Add SLA,
retention, GDPR automation, reporting config only when their features land.

---

## 12. Agentic tools (manifest `agentic.tools`)

All via existing `POST /agent/tools/{name}` dispatch + manifest role gating +
settings toggles; mutating tools keep `mutates: true` (portal confirmation flow).

| Tool | Mutates | Roles |
|---|---|---|
| `list_rfps` (phase/status filter) | no | manager, recruiter |
| `get_rfp` (with positions + processes) | no | manager, recruiter |
| `get_client` | no | manager |
| `list_positions` / `list_candidate_processes` | no | manager, recruiter |
| `search_candidates` (talent pool: skills/availability/assignment-aware — evolves `search_cvs`) | no | manager, recruiter |
| `assess_fit` (J/E/V verdict via configured engine) | no* | recruiter |
| `match_candidates` / `create_match_run` (existing, engine-aware) | run: yes | recruiter |
| `open_candidate_process` (add candidate to position) | yes | recruiter |
| `advance_process_stage` (assess/submit/offer/accept/reject/withdraw) | yes | recruiter |
| `approve_rfp` / `close_rfp` (close includes cancelProcesses flag) | yes | manager |
| `create_rfp` (for future automated capture) | yes | manager |
| `get_rfp_analytics` | no | manager |

- v1's ENHANCED_AGENTIC_TOOLS (7 categories, ~40 tools) is **trimmed** to this
  operational set; task/notification tools disappear (msgcenter owns that); integration
  tools disappear (ports + settings own that).
- Agents: **tools-only for MVP** (AI_MODULES_PLAN precedent: solutions ships one agent,
  staffing shipped none). A `sourcing-agent` (autonomous candidate research/enrichment)
  is a later slice once enrichment ports exist. Manifest `skills`/`knowledge` slots can
  hold an RFP-analysis skill later — not MVP.

---

## 13. API surface (module backend, via portal proxy)

Extend the existing thin-controller pattern (`RfpController` + `AgentToolsController`):

- `POST/GET /api/rfps`, `GET /api/rfps/{id}` (+ `/positions`, `/processes`,
  `/submissions`, `/phase-history`)
- `POST /api/rfps/{id}/approve|close|reject|archive` (close body: confirm + reason)
- `POST /api/positions`, `PATCH /api/positions/{id}`
- `POST /api/positions/{id}/processes`, `PATCH /api/processes/{id}/stage`
- `POST /api/submissions` (batch send to client), `POST /api/submissions/{id}/response`
- `GET/POST /api/clients`, `GET /api/clients/{id}`
- `GET/POST /api/candidates`, `GET /api/candidates/{id}` (+ `/history`),
  `POST /api/candidates/scan` (existing), `POST /api/sourcing/inbox` (emulated intake)
- `GET /api/analytics/...` (funnel, phases, placements)
- `GET/PUT /api/settings` (existing, extended groups)
- `POST /agent/tools/{name}` (existing)

---

## 14. Data model, migrations, migration of legacy data

Module-owned Flyway (module has its own schema `PGSCHEMA:staffing`, existing
`V1__baseline.sql`, `V2__module_settings.sql`):

- `V3`: `client`, `position`, `candidate_process`, `submission` (evolve shortlist),
  `rfp_phase_history`, extend `rfp` (client FK, source, serviceType, analysis, phase),
  extend `candidate_profile` (kind, assignment, expectations, contact, consent,
  enrichment), `match_run.engine`.
- `V4` (or fold into V3): legacy data mapping — old `NEW→DRAFT`,
  `ANALYZING|MATCHED|SHORTLISTED|SUBMITTED→SOURCING` (no per-process history exists),
  `ARCHIVED→ARCHIVED`; old `shortlist` rows → `submission` rows.
- `RfpStatus` enum replaced by new `RfpPhase` + transition logic replaced by
  `PhaseDerivationService` (pure rule functions — prime unit-test target).
- **Role retirement**: dropping `staffing-user` — either declare it in the manifest one
  release as a deprecated alias mapping to recruiter visibility, or accept a documented
  breaking change (Keycloak keeps the old grant; nothing references it).
- Zero-warning discipline: spotless:apply → mvn verify; Jackson 3 conventions; no
  TODOs; DDL only in Flyway.

---

## 15. Testing strategy

- **Server**: `PhaseDerivationService` (all aggregation + regression rules) and
  `MatchingService`/verdict aggregation = pure-function unit suites; service tests per
  feature (capture analysis with a fake LLM, process stage validation, close-cancels
  logic, connector registry defaulting to emulated); extend the 2 Testcontainers smoke
  tests if tenant-visible behaviour changes (Docker required).
- **UI**: vitest colocated specs — board grouping (derived phases), process stage
  transitions, talent filters, settings form validation.
- **E2E** (`test/e2e/` Playwright pack): add one capture→approve→sourcing happy-path
  spec when the stack is booted (msg-center coverage gap noted in AGENTS.md — staffing
  rides the same pack).

---

## 16. Phasing (MVP slices)

| Phase | Scope | ~Days |
|---|---|---|
| **P0 Foundations** | V3/V4 migrations, `RfpPhase` + `PhaseDerivationService`, entities (client/position/process/submission), role swap (3 roles), manifest 4 apps + tools, build-mfe 4 elements, legacy data mapping | 4–5 |
| **P1 Capture** | Capture app, LlmClient + analysis prompt, draft review, approve action, `rfp.captured`/`rfp.approved` publish (msgcenter HMAC), client picker/creation | 4–5 |
| **P2 Ongoing + Talent** | Ongoing app (phase board, positions, forking process board, stage actions, submissions, close/reject dialogs, client card), Talent app (directory, assignment view, sourcing inbox emulated, CV scan, enrichment timestamps) | 8–10 |
| **P3 Matching + Analytics** | J/E/V assessment + engines (rules default, jev + llm configurable), MatchRun engine field, analytics app (funnel, time-in-phase, placements), remaining agent tools | 4–5 |
| **P4 Connectors polish** | Runtime connector registry complete (5 ports × emulated), settings connectors + llm cards (masked secrets), messaging outbox view, notification messages wired to milestones | 3–4 |
| **P5+ (post-MVP)** | Email capture automation, off-the-shelf adapters (priority per demand), calendar port, approval tasks via msgcenter tasks, sourcing agent, consumed CRM events, client portal | — |

Each phase ends green: `mvn spotless:apply && mvn verify`, `npm test`, `npm run build`.

---

## 17. Open decisions (resolve during review / at implementation start)

1. **REJECTED semantics**: plan proposes "engagement declined/cancelled pre-placement"
   (manual, reason required) — vs your intent possibly "all offers rejected". Confirm;
   if the latter, REJECTED is not an RFP phase (regression to SOURCING covers it).
2. **ACCEPTED vs PENDING_ONBOARDING boundary**: proposal — ACCEPTED when ≥1 offer
   accepted, PENDING_ONBOARDING while accepted processes haven't onboarded; if you see
   ACCEPTED/REJECTED as *offer outcomes only*, the RFP-level list shrinks.
3. **First-candidate-process opens SOURCING** (derived pickup) vs an explicit recruiter
   "take ownership" action — proposal is derived-on-first-process.
4. **Message addressing**: confirm with msgcenter which mc group(s) staffing milestone
   messages target (default staffing group? per-client groups?).
5. **Jev API specifics**: hosted TypeSafe endpoint vs self-hosted — decide at P3 start;
   `rules` engine ships regardless so this never blocks MVP.
6. **staffing-user retirement**: keep one release as alias vs hard cut.

---

## 18. Risks / gotchas (from research)

- Conditional-bean adapters fail the boot context when a `kind` has no impl — runtime
  registry with mandatory default avoids this.
- Manifest events are documentation-grade — don't design logic that *requires* portal
  event enforcement.
- External module publish needs the HMAC secret derivation (HMAC(SESSION_SECRET, key),
  sample-sender precedent) — staffing must compute it the same way; direct NATS is the
  alternate (tenant-network trust).
- `ProxyService.findMfeContent` uses the module's first MFE row origin — all app entries
  must share `baseUrl` (they do by construction; don't add cross-origin entries).
- No tenant config passthrough for module settings — runtime config lives in
  `module_settings` only (per-tenant schema ⇒ automatically tenant-scoped).
- Bundle budgets: 4 elements in one bundle — keep lazy `@defer` habits (quick-chat
  precedent); build fails on budget overrun.
- Token lifetime: portal module tokens live 5 min with one re-mint (module-client
  pattern) — reuse it verbatim for all 4 elements.
