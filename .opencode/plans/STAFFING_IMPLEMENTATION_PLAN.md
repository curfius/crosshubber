# Staffing Module v2 — Implementation Plan (new module, parallel run)

Status: **P-S re-scaffold + P0–P4 implemented, verified (2026-10-12)** — see the status sections
below. Executes the domain plan `.opencode/plans/STAFFING_MODULE_PLAN.md` (unchanged decisions
live there).

## P4-status (P4 done, verified 2026-10-12)

- **P4.1 registry**: `ConnectorCategory` (crm, talentSource, documentStore, messaging, hris) +
  `ModuleConnector` marker (category + kind) + `ConnectorRegistry` — beans declare
  (category, kind); the active kind comes from the `connectors` settings group per category
  (defaults: emulated ×4, documentStore=fake); resolution order configured kind → default kind →
  any single bean, so **every category always resolves — no missing-bean boot failures**.
  Unknown configured kinds log-and-degrade. Legacy `docsource` property seeds documentStore.
  Secret-masked per-category config: keys matching secret/apikey/token/password/key are
  encrypted at write (`CryptoService`), masked `***` on read, decrypted for connector runtime.
  `POST /api/settings/connectors/{category}/test` (admin) → deterministic pong for
  emulated/fake; `GET /api/settings/connectors` (admin) lists kinds + active kind.
- **P4.2 emulators**: `EmulatedCrmConnector` (fixture clients + opportunity log), 
  `EmulatedMessagingConnector` (send → outbox), `EmulatedHrisConnector` (deterministic fake
  employee-id) writing to the `connector_outbox` table (Flyway V3). `Emulated Messaging` send
  emits cursor-style rows viewable at `GET /api/settings/outbox` (admin, kind filter). The
  HRIS emulator is wired to the process → onboarded stage: called outside the TX, fail-soft
  (log), writes the outbox row.
- **P4.3 settings UI**: `th-settings` gains section switch (general / connectors / outbox):
  connectors card (per-category kind select + masked config display + test action + save via the
  settings PUT connectors map), outbox viewer.
- **P4.4 tests**: registry default resolution ×5 categories, configured-kind wins,
  unknown-kind degrade, emulated pong, outbox write/read rows (crm+messaging+hris).
- **Verified**: 177 server tests green; npm build green; container rebuilt + healthy, V3
  migration applied (11 tables in `talenthub`), manifest + bundle 200.

## P5-status (cutover executed, verified 2026-10-12)

Walkthrough (live, dev stack, full agent roles minted portal-format):
- capture (analysis unconfigured — no LLM creds, draft created) → approve (phase manual event)
  → position → CV scan (fake corpus) → process opened → assessment (rules, overall=maybe) →
  submitted → interviewing → offer → accepted → onboarding → onboarded, RFP phase deriving
  draft → approved → sourcing → offer_extended → accepted → pending_onboarding with phase
  history rows at every change; close (confirm + reason) → archive; analytics reflecting
  placements=1 bySource={fake}; sourcing-inbox claim created a candidate from the corpus CV;
  connectors test action pong; HRIS emulator outbox row (employee emp-…) on onboarded;
  msgcenter publishes verified against the live portal (rfp.captured + milestone smoke,
  streamSeq 82/84); agent tools dispatched (get_rfp_analytics via /agent/tools).
- Portal regression green: portal/server `spotless:apply` + `verify` (235 tests incl.
  Testcontainers smokes) + portal/ui `npm test` (247 tests) + `npm run build`.

Cutover steps executed:
1. Registry: `staffing` deactivated + its 2 content rows deleted (dev DB; service removed from
   compose next). talent-hub stays installed via registry quick-install (recommendation kept:
   NOT added to tenant.json modules.external — keeps portal boot decoupled from the module
   container).
2. Compose: `staffing` service block removed; stack recreated with `--remove-orphans`; the
   staffing container is gone.
3. `modules/staffing/` deleted (git rm + leftover target dirs cleaned); staffin image removed.
4. `DROP SCHEMA staffing CASCADE` (6 tables incl. flyway history).
5. Realm: staffing-manager/staffing-recruiter granted to the portal-admins group (roles were
   declared in the manifest since P-S but the realm import never granted manager/recruiter);
   role description updated; keycloak force-recreated + healthy.
6. Docs: `.gitignore` staffing bundle entries replaced by talent-hub ones; this plan + the
   P5-status marked executed. Archived plans stay historical. README grep clean.

Deferred/archived remainder (per §7 + earlier decisions): E2E pack coverage for the module
(specs ride the test/e2e backlog), fixtures retention, email-capture automation (P5+ slice).
No `modules.external[]` entry (decision documented above).

## P3-status (P3 done, verified 2026-10-12)

- **P3.1 engines**: `FitEngine` port (assess(candidate, requirements, actor)) + `FitVerdict`
  (strong|maybe|skip per dimension, worst-of overall, engine+at stamped) + `PositionRequirements`
  (from position jsonb, merged over rfp requirements at assess time) + `RulesFitEngine`
  (default: MatchingService score bands → Job verdict; availability-conflict → Environment; rate
  bands 1.10/1.25 + notice mismatch → Value; missing signals degrade to maybe/insufficient-data)
  + `JevFitEngine` (POST /assess to the configured base URL with the candidate payload and
  settings-resolved key; per-call read timeout) + `LlmFitEngine` (via LlmClient).
  `MatchingService` ported as the band base + used for `match_candidates`/match runs.
- **P3.1 selection**: `FitEngineSelector` — matching.engine setting default, per-call override,
  unknown keys fail fast; `ModuleSettingsService` gains the `matching` group
  (engine, jevBaseUrl, masked jevApiKeyEnc, jevTimeoutMs; env fallbacks TALENTHUB_JEV_* →
  TalentHubProperties.Jev) and serves `effectiveJev()`.
- **P3.2 flow**: `POST /api/processes/{id}/assessment` (recruiter+) — pooled-only (409 others),
  engine call OUTSIDE the TX (502 fail-soft, process stays pooled), successful verdict stores
  fit jsonb {dimensions, overall, engine, at}, moves stage → assessed with history note, then
  rederive + stage-changed publish. `MatchRunService.createMatchRun(rfpId, topN, engine,
  actor)`: rules-only ranking (non-rules engines ship as assessments, not rankings),
  persisted with engine provenance + params + results.
- **P3.3 analytics**: `AnalyticsService` — pure compute (seeded-rows-tested) over rank windows
  (30d/90d/all): rfpCountsByPhase, rfpCountsByServiceType, outcomeCounts (active/closed/
  rejected/archived), topClients (top 5), timeInPhaseHours (avg from consecutive phase-history
  pairs), funnel (stage distribution), placements (onboarded count) per source prefix,
  verdictOutcomes (fit.overall → onboarded/rejected counts). `GET /api/analytics/summary`
  (manager+admin).
- **P3.6 tools**: `assess_fit`, `match_candidates` (rank vs rfp requirements), `create_match_run`
  (persisted, rules-only with provenance), `get_rfp_analytics` — 13 tools in controller +
  manifest; toolNames and settings default-toggles tests updated.
- **P3.4/P3.5 UI**: `th-analytics` (cards + CSS bars only: by-phase, outcomes, funnel,
  time-in-phase, placements-by-source, verdict→outcome, top clients; period select) and real
  `th-settings` (tools toggles list, llm card with masked key, matching card with engine select
  + jev masked key + timeout).
- **Verified**: `mvn spotless:apply` + `mvn verify` — **172 tests, 0 failures** (added:
  scoring 6, fit 8, selection 5, engines 5, assessment 3, match-run 4, analytics 5).
  `npm ci`/`test`/`build` green; container rebuilt + healthy; healthz ok; manifest serves all
  13 tools; bundle 200 (175.7 kB raw / 46.8 kB transfer, budget 300/600). Full stack healthy.

## P2-status (P2 done, verified 2026-10-12)

- **P2.1 reads**: `RfpQueryService` — filtered list (phase/client/serviceType/kind, newest
  first) + full detail aggregate (client card, positions, processes, submissions, phase
  history); `GET /api/rfps`, `GET /api/rfps/{id}`, `GET /api/clients/{id}` (card + RFP
  history, newest first, `active` flag). All staffing roles read.
- **P2.2 positions**: `PositionService` — create under an RFP (defaults headcount/status/
  orderIndex), patch with optimistic version check (`version` → 409 per house convention),
  status/serviceType validation; `POST /api/rfps/{id}/positions`, `PATCH /api/positions/{id}`
  (recruiter+).
- **P2.3 process lifecycle**: `CandidateProcessService` — open (candidate×position, one active
  guard 409) and validated stage transitions with history entries; every mutation re-derives
  the RFP phase via the workflow service and publishes process events OUTSIDE the TX scopes;
  milestones for submitted/offer-extended/accepted/onboarded with dedicated subjects;
  `rfp.phase-changed` now also publishes on derived changes (audience manager+recruiter+
  admin, never staffing-user).
- **P2.4 submissions**: batch send of assessed-only processes → submitted (history note with
  the submission id) + rederive + per-process events; response recorded once (409 on second
  response) with outcome/note/at + respondedAt; `POST /api/rfps/{id}/submissions`,
  `POST /api/submissions/{id}/response` (recruiter+).
- **P2.5 close/reject/archive**: controller routes wired to the workflow service
  (`{confirm:true, reason?}` close, `{reason}` reject, archive from closed/rejected);
  `rfp.closed`/`rfp.rejected` events published outside the TX with the count of cancelled
  processes available in the result.
- **P2.6 talent backend**: `CandidateService` (directory filters query/kind/availability/
  stale + detail with computed process history + manual add + patch with version conflict +
  rules-based enrich), `DocumentSource`-backed CV scan (naive dictionary extraction port from
  staffing v1: skills/seniority/languages/availability; re-scan marks old rows stale),
  `TalentSourceConnector` port + `EmulatedTalentSource` (3 fixtures linked to the fake corpus)
  + sourcing inbox claim (creates the candidate, publishes `candidate.added`);
  `GET /api/candidates`, `GET/POST /api/candidates`, `PATCH`, `POST .../enrich`, `POST .../scan`,
  `GET/POST /api/sourcing/inbox…`.
- **P2.7/P2.8 UI**: `th-ongoing` — phase board (PHASES groups, phase filter, title search) →
  detail view with client card, phase history trail, per-position process rows with
  next-stage action buttons (STAGE_TRANSITIONS mirror), open-process candidate picker,
  add-position form, submissions timeline with response states, Close/Reject dialogs listing
  the active processes that will be cancelled (reason input; reject requires reason), Archive
  for terminal phases; `th-talent` — directory (search/kind/stale-only filters), detail
  (skills badges, assignment, per-RFP process history), sourcing inbox tab with claim +
  CV-scan input, manual add form. Bundle 164.5 kB raw / 44.6 kB transfer (budget 300/600).
- **P2.9 tools**: `list_rfps`, `get_rfp` (full aggregate), `create_rfp`, `approve_rfp`,
  `list_candidate_processes`, `search_candidates`, `open_candidate_process`,
  `advance_process_stage`, `close_rfp` (confirm + cancelled count) — toolNames sync test
  updated; manifest declares all nine with per-domain-plan §12 roles.
- **Verified**: `mvn spotless:apply` + `mvn verify` — **137 tests, 0 failures** (added: reads
  + client detail 5, positions 5, process lifecycle 9 (sync +0), submissions 5, ingestion/
  inbox 6, candidate service 6). `npm ci`+`test`+`build` green; container rebuilt + healthy;
  healthz ok; manifest serves the 9 tools; bundle 200; full stack healthy, staffing untouched.
- Notes for P3+: submissions response UI (record outcome) not yet in the ongoing app
  (server-side done); UI vitest specs for board/stage guards/close-dialog are P2.10 targets
  riding the P3 batch or the test-e2e pack.

## P-status (P1 done, verified 2026-10-12)

- **P1.5 events**: `EventEnvelopeFactory` (pure, strict-v3, self-checks against the portal's
  known-key set) + `PortalEventPublisher` (msgcenter HTTP ingest, HMAC secret
  `publishSecret(sessionSecret, "talent-hub")`, fail-soft on 401/403/422/429/5xx) +
  `talenthub.portal.{url,session-secret}` props. **Live-verified against the running portal**:
  hand-computed HMAC + envelope → 200 `{"published":true,"streamSeq":47}`.
- **P1.1 clients**: `ClientService`/`ClientController` (list/get/create; read all staffing
  roles, write manager+admin via `ModuleRoles.requireClientWriter`), camelCase DTO.
- **P1.2 documents**: `DocumentService` (volume dir `talenthub.documents.dir`, compose volume
  `talenthub_documents`, upload→`upload:<uuid>` ref + json sidecar), `DocumentController`
  (upload recruiter+, fetch all roles), port delegation for `fake:*` refs, null-safe 404 on
  port contract violation, 20 MB multipart cap.
- **P1.3 capture**: `RfpAnalysisService` (structured prompt, brace-span fallback for fenced
  LLM JSON, fail-soft `configured|unconfigured|failed`), `RfpCaptureService` (TX1 client+draft
  → LLM outside TX → TX2 analysis+backfill → publish outside TX), `RfpController`
  (`POST /api/rfps/capture` manager, `GET /api/rfps/drafts` manager+admin,
  `POST /api/rfps/{id}/approve` manager with optional reviewed-edits body that lands inside the
  approve TX: title/analysis.positions/requirements).
- **P1.4**: approve wired to `RfpWorkflowService.approve(id, actor, edits?)`;
  `rfp.approved` published outside the TX scope (recruiter+admin audience; matching
  `rfp.captured` on capture).
- **P1.7 tools**: `create_rfp` + `approve_rfp` in the tools controller + manifest (mutating,
  manager roles), toolNames sync test updated, settings tools view reflects the real list.
- **P1.6 capture UI** (`th-capture`): drafts list (`GET /api/rfps/drafts`), capture form
  (client create-inline name, title hint, description), analysis status badges
  (configured/unconfigured/failed), review view with editable title + positions (rows staged
  client-side and applied server-side via the approve-edits body), Approve (manager-only,
  `canManage` from `mount({user})`), `mount`/`unmount` lifecycle per the module-outlet
  contract. Bundle rebuilt (116.9 kB class → green build), container serving it (200).
- **Verified**: `mvn spotless:apply` + `mvn verify` — **98 tests, 0 failures** (added: envelope
  4, publisher 3, clients 8, capture-pipeline 7, tools-dispatch 6, documents 9, approve-edits
  1+controller). `npm ci`+`test`+`build` green; container rebuilt + healthy; healthz ok;
  manifest serves the 2 tools; bundle 200; msgcenter publish smoke 200.

## S-status (re-scaffold + P0 verified 2026-10-12; first scaffold was lost)

History: the first session's P-S scaffold was lost from disk before it could be committed
(untracked tree wiped; only the compose block + a stale image survived). This session
re-scaffolded P-S from the recipe (templates: `modules/staffing/{server,ui}`,
`modules/solutions/server/…/{LlmClient,CryptoService}`) and implemented P0 on top. During the
session the uncommitted compose block was wiped by an unrelated working-tree commit
(`e05babc` sample-sender, which did not intend it) — the S4 block was re-added verbatim.
RfpPhase.java/ProcessStage.java "corrupted leftovers": never present (nothing to delete).

Verified state (all commands exit 0):

- **P0 complete**: P0.1 entities+repos (client, rfp, position, candidate_process, submission,
  rfp_phase_history, extended candidate_profile, match_run + 8 repos), P0.2 `RfpPhase` +
  `ProcessStage` (transition table, terminal sets), P0.3 `PhaseDerivationService` (pure,
  table-driven), P0.4 `RfpWorkflowService` (approve/close/reject/archive/rederive;
  close/reject bulk-cancel active processes with history note; TX discipline via
  `TransactionTemplate` — publish/LLM orchestration stays outside TX scopes, P1 slots in),
  P0.5 `ModuleRoles` guards (requireManager/requireRecruiter/requireReader/requireAdmin;
  SettingsController refactored onto it), P0.6 `module-client.ts` domain interfaces +
  `PHASES`/`STAGES`/terminal sets + `hasRole`/`canManage`/`canRecruit`, P0.7 all named tests.
- Server: `mvn spotless:apply` + `mvn verify` green — **57 tests, 0 failures**
  (derivation 11, stages 6, rfp-phase 5, token 5, settings 6, settings-controller 3,
  tools 3, workflow 10, role-guards 8 — one parameterized ×3), checkstyle/spotless clean.
- UI: `npm ci` + `npm test` + `npm run build` green — 5 elements (`th-capture/ongoing/
  talent/analytics/settings`, stubs until P1/P2), bundle 116.85 kB raw / 32.36 kB transfer
  (budget 300/600), installed to `server/…/public/mfe/talenthub.js` via `build-mfe.mjs`.
- Flyway: stale `talenthub` schema dropped, fresh V1 (8 tables) + V2 migrated
  (`flyway_schema_history`: baseline + module settings, success=t).
- Runtime: compose service re-added (8093/28093, `PGSCHEMA=talenthub`, `PORTAL_URL` wired
  for P1 publishing); container rebuilt + healthy; healthz `{"ok":true,"db":"up","app":
  "talent-hub"}`; manifest 200 (key `talent-hub`, 4 apps + settings, roles per D6, 13
  published events declared, 0 tools); bundle 200. Full stack healthy, staffing untouched.
- Deviations from plan: `Map.ofEntries` for the 11-entry transition map (Map.of caps at 10);
  `rfp.deadline` nullable in V1 (capture drafts may not have one yet); LLM settings group
  named `llm` per domain plan §11 (solutions names its analogue `agent`); position status +
  profile kind stay validated strings (no enums beyond the P0.2-specified two);
  `rfp_phase_history.trigger` quoted identifier (Postgres reserved word); batch-1 gate merged
  with the application class (Boot repackage needs a main class).
- S6 (registry quick-install): **still pending** — needs a portal browser session (dev realm
  has direct access grants disabled; the `portal-admin` service account carries no
  `portal-registry-edit`). Rides the first live walkthrough (P5.1) or an ad-hoc install.

## 0. Decisions record (all locked)

| # | Decision |
|---|---|
| D1 | **New module built on the side; old `modules/staffing` runs untouched until cutover, then deleted** (registry uninstall → compose service removed → dir deleted → docs updated, same change). |
| D2 | **Old schema data discarded** — clean `V1` baseline in the new module, no migration code. |
| D3 | RFP phases **derived from candidate processes**; manual only approve / close / reject / archive. **Close (and reject) cancel unfinished candidate processes behind a confirmation dialog**. |
| D4 | `REJECTED` = **engagement declined** (manual, manager, reason required); all-offers-rejected simply derives back to `SOURCING`. |
| D5 | `SOURCING` entered **automatically on first candidate process** opened. |
| D6 | Roles: `staffing-manager`, `staffing-recruiter`, `staffing-admin` + **`staffing-user` kept as read-only**. Role keys unchanged → existing Keycloak grants survive the swap. |
| D7 | Matching: **J/E/V structured fit assessment, engine configurable** (`rules` default / `jev` / `llm`). |
| D8 | Apps: **capture / ongoing / talent / analytics + settings**, one bundle, 5 custom elements. |
| D9 | Integrations behind module-owned ports; **MVP ships emulated impls only**; selection at runtime via module settings (per tenant), default `emulated`. |
| D10 | Notifications/tasks: **reuse msgcenter** (publish milestone envelopes; no own task schema). |

**Module name: `talent-hub`** (user-confirmed 2026-10-09) — `modules/talent-hub`,
package `com.crosshubber.talenthub`, schema `talenthub`, port 8093 / host 28093,
proxy `/api/mfe/talent-hub`, elements `th-*`, publish secret HMAC key `talent-hub`
(valid caller key: dash allowed by the authenticator regex and kebab-case `KEY_RE`).

---

## P-S — Scaffold (≈1 day)

Copy templates, don't invent: server skeleton from `modules/staffing/server`
(pom, checkstyle.xml + header, Dockerfile, application.yml, `web/` Health +
GlobalExceptionHandler, `security/` AgentAuthenticationFilter + AgentCallTokenService +
AgentPrincipal(s), `config/SecurityConfig`), UI skeleton from `modules/staffing/ui`
(package.json, angular.json, tsconfig*, `scripts/build-mfe.mjs`, `src/main.ts`,
`shared/{module-client,ready,styles}.ts`), plus from `modules/solutions/server`:
`agent/LlmClient.java` and `settings/CryptoService.java`.

Work items:

- S1 Server scaffold: rename packages/artifact (`com.crosshubber.talenthub`,
  `crosshubber-talenthub`, `TalentHubApplication`), port 8093,
  `talenthub.agent-shared-secret`
  (`${AGENT_SHARED_SECRET:${SESSION_SECRET:...}}` — same env_file path as staffing),
  `talenthub.llm.*` env fallbacks (`TALENTHUB_LLM_*` — no underscore inside the
  prefix, so relaxed binding maps cleanly), crypto key `TALENTHUB_CRYPTO_KEY`
  (64 hex, dev default in yml per house convention).
- S2 UI scaffold: `PROXY_BASE=/api/mfe/talent-hub`,
  `TOKEN_URL=/api/agent/module-token/talent-hub`;
  elements array → `th-capture`, `th-ongoing`, `th-talent`, `th-analytics`,
  `th-settings` (components stubbed in P-phases; main.ts defines all 5 up front so the
  manifest and bundle move together).
- S3 Flyway: fresh `V1__baseline.sql` (see §1), `V2__module_settings.sql` (copy).
- S4 Compose: add `talent-hub` service (build `./modules/talent-hub/server`, env_file,
  `PGSCHEMA=talenthub`, `PORT=8093`, `PORTAL_URL=http://portal:3000`, host port
  `${TALENT_HUB_HOST_PORT:-28093}`). Ports 8093/28093 verified free (staffing 8091/28091,
  solutions 8090/…, sample-sender 8092/28092). Old staffing service untouched.
- S5 Manifest v1 (`public/.well-known/portal-module.json`): key `talent-hub`, 4
  applications + settings entry (roles per D6/D8), `security.roles` (4 roles, keys
  unchanged per D6), `events.published` declared (§4), `events.consumed: []`,
  `agentic.tools` stub list (grows per phase; `AgentToolsController.toolNames()`
  test asserts sync — copy that test).
- S6 Install path: registry quick-install (existing convention; dev `tenant.json`
  keeps `external: []`). Health: `/healthz`.
- S7 Green check: `mvn spotless:apply && mvn verify` (server), `npm ci && npm test &&
  npm run build` (ui), `docker compose up --build -d` boots with both old and new
  modules healthy.

## 1. V1 baseline schema (fresh)

| Table | Key columns (beyond id/created_at/updated_at/version where noted) |
|---|---|
| `client` | name, industry, account_owner, contacts jsonb, notes, external_refs jsonb |
| `rfp` | client_id FK, title, kind (`rfp\|rfq`), **phase** (`draft\|approved\|sourcing\|offer_extended\|accepted\|pending_onboarding\|closed\|rejected\|archived`), service_type (`tm\|direct`), source (`manual\|email`), deadline, description (raw capture text), requirements jsonb (top-level defaults), analysis jsonb `{status: configured\|unconfigured\|failed, positions[], openQuestions[], model, at}`, document_refs jsonb, created_by. Index on phase, client_id |
| `position` | rfp_id FK, title, headcount int, service_type (nullable override), requirements jsonb, status (`open\|filling\|filled\|cancelled`), order_index. Index rfp_id |
| `candidate_process` | rfp_id, position_id, candidate_id FKs, stage (`pooled\|assessed\|submitted\|interviewing\|offer\|accepted\|onboarding\|onboarded\|rejected\|withdrawn\|cancelled`), stage_history jsonb `[{stage,at,by,note}]`, fit jsonb, notes, created_by. Indexes (position_id), (candidate_id), (rfp_id). No DB unique — app guards one *active* process per (position,candidate) |
| `submission` | rfp_id, process_ids jsonb, note, response, sent_by, sent_at, responded_at. Index rfp_id |
| `rfp_phase_history` | rfp_id, from_phase, to_phase, trigger (`manual\|derived`), actor, note, at. Index rfp_id |
| `candidate_profile` | existing V1 columns (source_ref, name, headline, skills, seniority, languages, availability, status, parsed_at) + kind (`internal\|external`), contact jsonb, assignment jsonb `{project, client, allocationPct, startAt, expectedEndAt, benchSince}`, expectations jsonb `{rate, currency, location, remote, noticePeriod, motivation}`, consent jsonb, last_enriched_at, notes |
| `match_run` | existing + engine (`rules\|jev\|llm`) |
| `module_settings` | V2 copy (key, value jsonb, updated_at) |

## 2. P0 — Foundations (≈4–5 days)

- **P0.1 Entities & repos**: `Client`, `Rfp` (phase instead of status), `Position`,
  `CandidateProcess`, `Submission`, `RfpPhaseHistory`, extended
  `CandidateProfile`, `MatchRun` — follow existing getter/setter style (no Lombok),
  `@JdbcTypeCode(SqlTypes.JSON)` jsonb fields (Jackson 3 / `tools.jackson.*` only).
- **P0.2 Enums**: `RfpPhase` (phase values, manual-terminal set =
  {closed, rejected, archived}; derived set = {approved, sourcing, offer_extended,
  accepted, pending_onboarding}); `ProcessStage` (allowed-transitions map + terminal
  set {onboarded, rejected, withdrawn, cancelled}; `cancelled` only via close/reject).
- **P0.3 `PhaseDerivationService`** — pure function, first-class unit-test target:
  ```
  derive(rfp, processes):
    if phase in {closed, rejected, archived}: return phase        # manual sticky
    if phase == draft: return draft                                # only approve moves it
    wins  = processes with stage in {accepted, onboarding, onboarded}
    if ∃ stage ∈ {onboarding, onboarded}: return pending_onboarding
    if ∃ stage == accepted:                 return accepted
    if ∃ stage == offer:                    return offer_extended
    if ∃ any process:                       return sourcing       # D5, sticky
    return approved
  ```
  Manual actions (service-validated, manager-only): `approve` (draft→approved),
  `close` {confirm, reason?} and `reject` {reason} (any non-terminal → set phase,
  bulk-set active processes stage=`cancelled` + history note), `archive`
  (closed|rejected→archived). Every change appends `rfp_phase_history`.
- **P0.4 Workflow orchestration & TX discipline**: `RfpWorkflowService` — DB work in
  `@Transactional` methods; **event publish + LLM calls strictly outside TX**
  (orchestrate-after-commit helper or split public-orchestrator/private-TX pattern;
  AGENTS invariant: no blocking I/O inside TX, Hikari pool = 5).
- **P0.5 Role guards**: small controller helper (settings precedent:
  `SettingsController.requireAdmin`) — `requireManager()`, `requireRecruiter()`
  (recruiter OR manager OR admin), read endpoints allow `staffing-user`.
  Enforced server-side; UI only hides.
- **P0.6 UI base**: `module-client.ts` extended (`Phase`, `Position`, `Process`,
  `Client`, `Candidate` interfaces, `PHASES` const replacing `RFP_STATUSES`).
  Mount contract confirmed: portal calls `el.mount({user: PortalUser|null,
  language})` (`module-outlet.component.ts:216`) and `PortalUser.roles:
  string[]` (`core/models/index.ts:72`) → UI action-hiding reads
  `user().roles` against the `staffing-*` keys (server enforces regardless).
- **P0.7 Tests**: `PhaseDerivationServiceTest` (table-driven: every phase incl.
  regressions and manual stickiness), `ProcessStageTest` (transition table),
  `RfpPhaseTest`, ported `AgentCallTokenServiceTest` + `toolNames` sync test.
  Green: spotless + verify + npm test + build.

## 3. P1 — Capture app + events (≈5 days)

- **P1.1 Clients API**: `ClientController` (thin) + service — create/list/get
  (read: all roles; write: manager+admin).
- **P1.2 Documents**: `POST /api/documents` (upload → ref) + `DocumentSource` port
  copied with `Emulated/FakeDocumentSource` (`talenthub.docsource.kind`, default fake;
  **every kind value must have a bean** — known conditional-bean gotcha). Storage
  backend: module volume dir for real files, fake refs in emulated mode.
- **P1.3 Capture pipeline**:
  `POST /api/rfps/capture` {clientRef|newClient, kind, serviceType, title?, deadline?,
  description, documentRefs[]} → creates RFP `draft` (source=manual) inside TX →
  outside TX runs `RfpAnalysisService` → `LlmClient` (solutions pattern; structured
  JSON prompt → positions[] + requirements + deadline + title + openQuestions, parsed
  with module ObjectMapper) → persists `analysis` jsonb + best-effort field backfill.
  **LLM unconfigured/failed → draft still created** with `analysis.status =
  unconfigured|failed` (fail-soft; capture never blocked).
- **P1.4 Approve**: `POST /api/rfps/{id}/approve` (manager) → D3/D4 phase rules.
- **P1.5 Module event publisher**: `PortalEventPublisher` —
  POST `{PORTAL_URL}/api/msgcenter/publish`, headers
  `X-MsgCenter-Key: talent-hub`,
  `X-MsgCenter-Secret: base64url_nopad(HMAC-SHA256(key=SESSION_SECRET,
  data="talent-hub"))` — **confirmed**: `PublishAuthenticator.expectedSecret`
  (`Base64.getUrlEncoder().withoutPadding()`; key=sessionSecret, data=callerKey),
  sample-sender `server.mjs:34` identical (`digest('base64url')`). Note: the
  authenticator enforces **30 publishes/min per caller key** (Caffeine token
  bucket) — fail-soft logs on 429 too.
  **Envelope is strict-v3** (`EnvelopeValidator`): `v:1`, `type:"message"`
  (or `notification`), `moduleKey:"talent-hub"` (must exist in the registry —
  `MsgCenterPublishService:71`, fine, talent-hub is installed), `id` (UUID),
  `createdAt` (ISO), `audience` object **required** with ≥1 channel
  (`users|roles|groups|allUsers`), `title:{en:...}` (≤500), `body:{en:...}`
  optional (+≤5 `sections`), `severity`, optional `subject`/`threadId`/
  `link{moduleKey,path}`/`sender` — **unknown keys reject the envelope (422)**.
  **Custom subjects confirmed**: `subjectOf` (`MsgCenterPublishService:25-38`)
  uses a declared subject when it starts with `portal.msg.` or `portal.task.`,
  else falls back to `portal.msg.<moduleKey>.published` → use
  `portal.msg.talent-hub.<event>` per event (e.g. `portal.msg.talent-hub.rfp.captured`).
  Message type + no `task.template` → `resolveTemplate` is a no-op. Fail-soft:
  publish errors (401/422/429/503) logged, never fail the business op.
  Events in P1: `rfp.captured`, `rfp.approved`; P2+: `rfp.phase-changed`,
  `process.stage-changed`, milestone subjects (`process.submitted`,
  `offer-extended`, `accepted`, `onboarded`), `candidate.added`, `rfp.closed`,
  `rfp.rejected`. Audience per event: actor-relevant `roles` only — e.g.
  captured/approved → recruiter+admin; close/reject/phase-changed →
  manager+recruiter+admin; never `staffing-user` (read-only, cannot act on
  task-shaped content).
- **P1.6 Capture UI** (`th-capture`): drafts list + capture form (client
  combobox/create-inline, kind, serviceType, deadline, description, doc upload),
  analysis review editor (editable positions/requirements from `analysis`,
  banner when `unconfigured/failed`), Approve button (manager role). Reuse
  `SHARED_STYLES` + ready dispatcher; OnPush + signals, no router (portal switches
  elements — solutions pattern).
- **P1.7 Tool**: `create_rfp`, `approve_rfp` (mutating, manager) added to manifest +
  dispatch + toolNames test.
- **P1.8 Tests**: analysis service with stubbed LLM (unconfigured path, happy path,
  malformed-JSON path), approve transition, HMAC secret derivation unit test,
  envelope shape test (valid + unknown-key rejection), controller tests.

## 4. P2 — Ongoing + Talent apps (≈9 days)

- **P2.1 Reads**: `GET /api/rfps` (phase/client/kind filters), `GET /api/rfps/{id}`
  (positions + processes + submissions + phase history), `GET /api/clients/{id}`
  (card incl. RFP history).
- **P2.2 Positions**: `POST /api/rfps/{id}/positions`, `PATCH /api/positions/{id}`
  (recruiter+, 409 on @Version conflict per house convention).
- **P2.3 Process lifecycle**: `POST /api/positions/{id}/processes` {candidateId}
  → stage `pooled` (+ active-dup guard) → derive (D5 → sourcing) → history + event;
  `PATCH /api/processes/{id}/stage` {toStage, note} — validated against
  `ProcessStage` transitions, appends stage_history, re-derives RFP phase, publishes
  `process.stage-changed` (+ milestone subject on submitted/offer/accepted/onboarded).
- **P2.4 Submissions**: `POST /api/rfps/{id}/submissions` {processIds[], note} →
  submission row + each process → `submitted`; `POST /api/submissions/{id}/response`
  {outcome, note}.
- **P2.5 Close/reject/archive**: manager-only; close/reject body
  `{confirm:true, reason?}` → phase set + active processes `cancelled` (history note
  "cancelled by rfp close/reject") + `rfp.closed`/`rfp.rejected` event; archive from
  closed/rejected. UI confirmation dialog lists the processes that will be cancelled
  (fetch active ones, show count + names).
- **P2.6 Talent backend**: `GET /api/candidates` (filters: query, kind, availability,
  stale), `GET /api/candidates/{id}` (+ computed process history), `POST`
  (manual add), `PATCH` (expectations/assignment/contact/consent), existing
  `POST /api/candidates/scan` ported; `GET /api/sourcing/inbox` +
  `POST /api/sourcing/inbox/{ref}/claim` on a `TalentSourceConnector` port with
  `EmulatedTalentSource` (fixture candidates, reuse fake CV refs);
  `POST /api/candidates/{id}/enrich` (rules-based re-pass + `lastEnrichedAt`).
- **P2.7 Ongoing UI** (`th-ongoing` — the robust one): phase board (columns per
  derived phase, filters, search) → RFP detail: header with phase badge + phase
  history trail, inline client card, documents, **per-position candidate-process
  board** (columns = process stages, cards = candidate + fit summary + stage
  actions), submissions timeline, Close/Reject dialogs (with cancel list).
  Evolves current `rfps.component`/`rfp-detail.component` structure (they are the
  reference, not carried over verbatim).
- **P2.8 Talent UI** (`th-talent`): directory table + filters, detail view
  (profile / assignment+bench / expectations / consent / process history), sourcing
  inbox tab (emulated fixtures → claim), scan + manual add.
- **P2.9 Tools**: `list_rfps`, `get_rfp`, `list_candidate_processes`,
  `search_candidates`, `open_candidate_process`, `advance_process_stage`,
  `close_rfp` (mutating flags per manifest).
- **P2.10 Tests**: stage-transition service tests incl. close-cancels + derive-on-
  mutation; submission flow; role-guard 403s (staffing-user read-only verified);
  UI specs: board grouping by phase, stage-action guards, close-dialog content,
  talent filters.

## 5. P3 — Matching (J/E/V) + Analytics (≈5 days)

- **P3.1 `FitEngine`** interface → `FitVerdict {dimension: {verdict
  strong|maybe|skip, rationale, score?}×3 (job/environment/value), overall,
  engine, at}`:
  - `RulesFitEngine` (default): maps existing `MatchingService` score bands → J from
    skills/seniority/languages; E from location/remote/availability vs position;
    V from expectations vs position rate/notice; missing data → `maybe` +
    "insufficient data". (Port `MatchingService` + its tests as the base.)
  - `JevFitEngine`: HTTP per candidate↔position with the three questions (typed
    verdicts); settings: baseUrl + masked apiKey + timeout (CryptoService).
  - `LlmFitEngine`: structured prompt via `LlmClient`.
  - Selection: settings `matching.engine` (default `rules`), per-call override
    allowed; engine recorded on result.
- **P3.2 Assessment flow**: `POST /api/processes/{id}/assessment` {engine?} → runs
  engine (outside TX), stores `fit` jsonb, enables pooled→assessed;
  `create_match_run`/`match_candidates` become engine-aware (MatchRun.engine).
- **P3.3 Analytics**: `GET /api/analytics/summary` {period} — RFP counts by
  phase/outcome/client/service_type, time-in-phase (from `rfp_phase_history`),
  process funnel (stage distribution), placements per period (onboarded), source
  effectiveness (candidate source → onboarded), assessment-verdict → outcome.
- **P3.4 Analytics UI** (`th-analytics`): light cards + tables, CSS bars only (no
  chart lib — bundle budget).
- **P3.5 Settings cards**: `matching` (engine select, jev fields masked, rules
  topN/weights) + `llm` (solutions pattern) added to settings element; tools card
  updated to full list.
- **P3.6 Tools**: `assess_fit`, `match_candidates`, `create_match_run`,
  `get_rfp_analytics`.
- **P3.7 Tests**: RulesFitEngine + verdict aggregation (pure), engine selection from
  settings, analytics query service (seeded rows), UI specs.

## 6. P4 — Connector registry + notification polish (≈4 days)

- **P4.1 Registry**: `ConnectorRegistry` — beans declare `(category, kind)`;
  categories `crm | talentSource | documentStore | messaging | hris`; settings group
  `connectors` picks kind per category (default `emulated`; **every category always
  resolves — never a missing-bean boot failure**; migrate `docsource` property onto
  the registry with env as seed default). Config jsonb per category, secrets via
  CryptoService, masked on read, `test` action (emulated → deterministic pong).
- **P4.2 Emulated impls** (fixtures, deterministic): `EmulatedCrmConnector`
  (fixture clients + fake opportunity log), `EmulatedMessagingConnector` (outbox
  table/log viewable at `GET /api/settings/outbox`), `EmulatedHrisConnector`
  (fake employee-id log — wired to process→onboarded, called outside TX),
  documentStore/sourcing already from P1/P2.
- **P4.3 Settings UI**: connectors card (per-category kind select + masked fields +
  test button); outbox view.
- **P4.4 Tests**: registry default resolution (every category), settings validation
  (unknown kind → 400), emulated connector behavior, outbox write path.

## 7. P5 — Cutover & decommission (≈1 day, after P0–P4 validated live)

1. Live dev-stack walkthrough: capture→approve→source→assess→submit→offer→accept→
   onboard→close (incl. dialog cancel-list), talent inbox claim, analytics populated,
   msgcenter inbox shows milestones, agent tools via chat.
2. Portal regression: portal `mvn verify` + `npm test` (external module shouldn't
   touch portal code — this proves it).
3. Uninstall old `staffing` from Module Registry (removes nav entries + tool
   catalogue rows).
4. Compose: delete `staffing` service block; `docker compose down && up --build`.
5. Delete `modules/staffing/`; `DROP SCHEMA staffing CASCADE` (manual psql — note in
   cutover commit message).
6. Docs: `STAFFING_MODULE_PLAN.md` + this file → status "implemented"; grep repo for
   `modules/staffing` references (AGENTS.md backlog pointers, README) and update;
   archived plan files (`plan/archive/AI_MODULES_PLAN.md`) stay historical.
7. Optional: add `talent-hub` as a `modules.external[]` entry in
   `tenants-config/dev/tenant.json` for boot-managed installs — **evaluate tradeoff
   first**: Reconciler fail-fast fetches the manifest at portal boot (6 retries), so
   portal boot would then depend on the talent-hub container; registry quick-install
   keeps them decoupled (current convention — recommend staying with it).

## 8. Cross-cutting conventions

- Formatting/quality: `mvn spotless:apply` then `mvn verify` per server change;
  Prettier for TS; Google Java Style (2-space, 100 cols); zero warnings; **no
  TODO/FIXME comments**; Jackson 3 (`tools.jackson.*`); DDL only in Flyway.
- TX hygiene: LLM, msgcenter publish, connector calls **outside** `@Transactional`.
- UI: zoneless + OnPush + signals API; shadow-DOM encapsulation; module UI copy is
  **English-only** (external-module convention — portal i18n doesn't apply);
  no chart/i18n libs added; bundle budget enforced by `npm run build`
  (staffing `angular.json`: **300kB warn / 600kB error**) — if 5 elements
  overrun, trim first (shared styles emitted once, `@defer` per app) and only
  then consciously raise the budget.
- Testing: every service gets a test class; pure logic (derivation, stages, fit
  rules) is the highest-value target; no Testcontainers needed unless a module IT is
  added (optional, Docker-gated).
- Per-phase green gate: server `spotless:apply + verify`, ui `test + build`,
  `docker compose up --build` healthy.

## 9. Risks / open items (resolve at stated phase)

Settled by code grounding — no longer open: subject handling (custom
`portal.msg.talent-hub.*` works), HMAC derivation + 30/min publish rate limit,
mount-user roles shape, ports 8093/28093 free (grep: no other reference in
repo), budget numbers (300kB/600kB), `effectiveLlm()` pattern
(`ModuleSettingsService.java:119` — stored-settings-first via `firstNonBlank`,
env fallback).

| Item | When |
|---|---|
| Bundle budget headroom with 5 elements (measure at first `npm run build`) | P2.7 / P3.4 gate |
| Registry-install vs tenant.json external entry for the new core app (recommendation: stay registry) | P5.7 |

## 10. Estimates

| Phase | Days | Depends |
|---|---|---|
| P-S scaffold | 1 | — |
| P0 foundations | 4–5 | P-S |
| P1 capture + events | 5 | P0 |
| P2 ongoing + talent | 9 | P1 |
| P3 matching + analytics | 5 | P2 |
| P4 connectors + polish | 4 | P2 (parallel to P3) |
| P5 cutover | 1 | P3+P4 |
| **Total** | **~30** | P3 ∥ P4 |
