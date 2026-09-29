# MESSAGE_CENTER_PLAN — Message Center module (inbox, notifications, tasks)

Status: **approved plan** (2026-09-29). Builtin embedded module `msgcenter` that renders notifications,
messages and tasks derived from NATS to the right audience, acts as the audit trail, and pushes task
completion events back onto the broker. No business logic inside the portal — task ownership stays
with the publishers (other modules or third-party apps).

Decisions locked during planning (2026-09-29):

1. **DB as the focused store, NATS as transport** — inbox state does not live in JetStream acks.
2. **JetStream** (`-js` on the compose NATS image), at-least-once with durable pull consumer + DLQ.
3. **Envelope `"v": 1` pinned from day 1** (later shared with agent sub-agent/A2A event traffic).
4. **Task expiry** is computed at read time from `expiresAt` — no scheduler in v1.
5. **Light validation** = JSON Schema draft 2020-12 fragments per field, strict keyword allowlist;
   `networknt:json-schema-validator` 3.x on the JVM (native Jackson 3 `tools.jackson` line),
   `ajv` 8 + `ajv-formats` in the browser.
6. **Audience** `users[] / roles[] / allUsers` declared in the envelope; one row per event; roles
   resolve at read time (role changes retroactively visible); `users[]` pins. `completion: "each"`
   requires a concrete `users[]`.
7. **Sender metadata** from existing registry: `registry_modules.name` + icon, with optional
   `sender.name/color` overrides in the envelope. Optional body **deep-link** to the origin module.
8. **Sections** in v1: `body.sections[]` (titled paragraphs) and `task.sections[]` (form grouping
   with title/description referencing flat `fields[]` by name).
9. **Plain-text rendering only** — no markdown/HTML.

---

## 1. Goals & non-goals

**Goals**

- Builtin embedded module `msgcenter` ("Message Center"): renders notifications, messages and tasks
  to the right audience. All content enters via NATS (JetStream); nothing is written by portal
  business logic.
- Render-only responsibility: consume → store → resolve audience → render; act → record → republish.
- Durable audit trail: every consumed event and every user action persisted in `mc_*` tables
  (precedent: `agent_tool_calls`, V29).
- Light form validation via standard JSON Schema with a keyword allowlist, so task senders describe
  fields in a standard language and the portal never learns field semantics.

**Non-goals (v1)**

- No markdown/HTML (plain text only, line breaks honored); no attachments; no multi-select/array
  fields; no nested objects; no conditional-required/cross-field rules; no default values.
- No Keycloak groups; no named recipient lists (§14 backlog).
- No retention/archiving job; no per-user content overrides.
- No scheduler — expiry computed at read time.
- No execution of task logic inside the portal — responses are events; ownership stays with publishers.

## 2. Architecture — broker as transport, DB as focused store

**Core decision.** NATS is *not* the inbox; Postgres is. JetStream provides at-least-once delivery
and a bounded replay window; `mc_messages` is the durable working set and the audit trail.

Rationale (from the planning discussion):

- Read/unread per-user state cannot round-trip through acks (ack ≠ read state).
- One durable consumer per user × subject filter does not scale (consumer limits, slow pulls).
- Audience via roles is a dynamic app-level concept — resolving it requires parsing payloads, i.e.
  writing events down anyway.
- Task state transitions (`open → approved/denied/done`) cannot live in ack state.

**Current state (measured 2026-09-29):** the backend has **no NATS client at all** — no `io.nats`
dependency in `portal/server/pom.xml`, no subject naming, no publish/subscribe code
(`plan/AI_PLAN.md:52` acknowledges this). NATS exists only as: compose container
(`docker-compose.yml` `nats:` service, image `nats:2.12-alpine`, health-checked, client port 4222
*not* published), the `portal.nats-http-url` monitoring-URL property
(`PortalProperties.java:62-63`, `ShellConfigService.java:213-217` service hint card), and the
manifest declaration slots (`events.published[]` / `events.consumed[]`, `ManifestValidator.java:41,
102-119`) which are documentation-grade only today.

**NATS layout**

- Compose: `nats:2.12-alpine` gains `-js` (JetStream); publish client port 4222 to host as
  `${NATS_CLIENT_PORT:-32253}` (dev CLI/debug convenience only).
- Stream `PORTAL_MESSAGES`: subjects `portal.msg.>` + `portal.task.>`, `limits` retention, 8 days,
  max ~1 MB per message.
- Consumer: single durable pull consumer `portal-msgcenter` (filter both prefixes), manual acks,
  `max-deliver: 5`; on final failure publish a copy + reason to `portal.dlq.msgcenter` and continue —
  no poison-pill.
- Config: `portal.nats-url: ${NATS_URL:}` — blank = whole feature off (fail-soft, mirrors the
  kc-admin pattern where absent config skips the feature, `Reconciler` style). Portal container env
  in compose: `NATS_URL: nats://nats:4222`. `application-test.yml` blanks it; the IT supplies its
  own value from Testcontainers.

## 3. Envelope v3 — single contract for all traffic

```jsonc
{
  "v": 1,
  "type": "notification | message | task",
  "moduleKey": "solutions",                    // required; must exist in registry_modules
  "sender": { "name": "Timesheet", "color": "#0ea5e9" },   // optional display overrides
  "subject": "portal.msg.solutions.timesheet-closed",      // informational (NATS subject is routing)
  "id": "uuid",                                // publisher-supplied; ingest idempotency key
  "createdAt": "2026-09-29T10:15:00Z",
  "audience": {
    "users":    ["<kc-sub>"],                  // ≤ 200; pinned recipients
    "roles":    ["portal-approver"],           // ≤ 20; resolved at read time
    "allUsers": false
  },
  "title": { "en": "Timesheet due", "de": "Zeiterfassung fällig" },
  "body": {
    "en": "Intro paragraph — plain text.",
    "sections": [                              // ≤ 5
      { "title": {"en": "Details", "de": "Details"}, "text": {"en": "…", "de": "…"} }
    ]
  },
  "severity": "info | success | warning | error",           // notification/messages
  "threadId": "corr-id",                                    // optional correlation
  "link": { "moduleKey": "solutions", "path": "/approvals/42" },  // optional deep-link
  "task": {                                                // type=task only
    "kind": "approval | collect",
    "completion": "any | each",                            // each ⇒ audience.users required
    "completionEvent": "hours.submitted",                  // dotCase; REQUIRED for collect, optional for approval
    "expiresAt": "2026-10-05T17:00:00Z",
    "sections": [                                          // optional form grouping, ≤ 5
      { "title": {"en": "Hours", "de": "Stunden"},
        "description": {"en": "Per project", "de": "Pro Projekt"},
        "fields": ["hours", "comment"] }                   // references flat fields[] by name
    ],
    "fields": [                                            // ≤ 20
      { "name": "email", "required": true,
        "label":  { "en": "Email", "de": "E-Mail" },
        "schema": { "type": "string", "format": "email", "maxLength": 200 } },
      { "name": "hours", "required": true,
        "schema": { "type": "number", "minimum": 0, "maximum": 24 } },
      { "name": "comment",
        "schema": { "type": "string", "maxLength": 500 }, "multiline": true }
    ]
  }
}
```

**Conventions**

- **Recipients (`audience`)**: one row per *event* in `mc_messages`, never per recipient; per-user
  state lives in side tables (`mc_message_reads`, `mc_task_responses`). At least one channel must be
  set. Role-addressed messages follow role changes (read-time resolution:
  `users ∋ me OR roles ∩ myRoles OR allUsers`); `users[]` pins the audience at send time.
  Recipients are format-validated but **not existence-checked** — no Keycloak I/O in the ingest
  path; unknown values simply never match (harmless dead recipients).
  `completion: "each"` requires a concrete `users[]` (validated at publish; role/allUsers + each is a 422).
- **Sender display**: `sender.name/color` if present, else `registry_modules.name` + icon, joined
  server-side when composing the read DTO. Plain strings, not i18n maps, not free-form branding.
- **Body/labels**: i18n maps, `en` required, other languages optional with fallback per existing
  label order; plain text, line breaks honored; sections render as titled paragraphs.
- **Deep-link `link`**: opened through Bridge/NavigationCoordinator (`navigateFromModule`); absent
  → plain card.
- **Response routing**: `portal.task.response.<moduleKey>.<completionEvent>`; if no
  `completionEvent`: `portal.task.response.<moduleKey>.approved|denied` (approval) / `.submit`
  (collect). Published **after** the response row commits (audit row first; publish last).

**Caps (enforced at publish + ingest; 422 at publish, DLQ path at ingest)**
Fields ≤ 20; enum options ≤ 50; per-field schema ≤ 8 KB; submitted string values ≤ 2,000 chars;
`pattern` ≤ 128 chars (ReDoS guard); `users[]` ≤ 200; `roles[]` ≤ 20; `body.sections` ≤ 5;
`task.sections` ≤ 5.

## 4. Validation stack — one schema, two engines

| Layer | Library | Notes |
|---|---|---|
| JVM | `com.networknt:json-schema-validator` **3.x** | dedicated Jackson 3 (`tools.jackson`) / Java 17+ release line (3.0.0+); publish, ingest, submit |
| Browser | `ajv` v8 + `ajv-formats` | draft 2020-12, `allErrors: true` → per-field messages in the DS form |

Server synthesizes each `fields[]` into one object schema
`{type:"object", required:[…], properties:{…}}` (per-field `required` is envelope sugar) and
compiles it once per ingested task, cached via Caffeine keyed by `event_id`.

**Keyword allowlist (strictly enforced — anything else rejected at publish with 422):**
`type, enum, format, pattern, minLength, maxLength, minimum, maximum, multiline, label`;
envelope-level `required`. `format` ⊆ `{date, email, uri}`. Rejected keywords include
`$ref`, `allOf/anyOf/oneOf`, `if/then`, arrays/`items`, nested objects/`properties`,
`default`, `required` inside the per-field `schema` fragment (it lives on the field wrapper).

**Field type matrix (v1)**
Short text (`string` + `format`/`pattern`), long text (`string` + `multiline`), number/integer
(`number|integer` + `minimum/maximum`), boolean, enum dropdown (`enum` ≤ 50 with label maps),
date (`string` + `format: "date"`).

**Four checkpoints, same schema**

1. Publish (HTTP path): compile + validate the envelope; task owner feedback at send time.
2. Ingest: re-validate; invalid → DLQ (the broker cannot 422).
3. Submit — the only authority: validate `data` against the stored schema; valid data *and only
   then* response row + completion-event publish; nothing unvalidated is ever recorded or published.
4. Browser pre-submit (Ajv): instant field errors before POST.

Contract tested in both suites: browser-pass ⇒ server-pass (schema-equivalence spot checks).

## 5. DDL — `V30__message_center.sql`

Flyway owns all DDL; new migration `V30__message_center.sql` after `V29__agent_tool_calls.sql`.

```sql
CREATE TABLE mc_messages (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id      UUID NOT NULL,
  msg_type      TEXT NOT NULL,                 -- notification | message | task
  module_key    TEXT NOT NULL,
  nats_subject  TEXT NOT NULL,
  nats_seq      BIGINT,                        -- stream sequence, replay/debug
  occurred_at   TIMESTAMPTZ NOT NULL,
  audience_json JSONB NOT NULL,                -- {users[], roles[], allUsers} as declared
  sender_name   TEXT,
  sender_color  TEXT,
  title_json    JSONB NOT NULL,
  body_json     JSONB NOT NULL,
  severity      TEXT,
  thread_id     TEXT,
  link_json     JSONB,                         -- {moduleKey, path}
  task_json     JSONB,                         -- kind/completion/completionEvent/expiresAt/fields/sections
  status        TEXT NOT NULL DEFAULT 'open',  -- tasks: open | done ('each' flips on final response)
  UNIQUE (event_id)
);
CREATE INDEX idx_mc_msgs_audience ON mc_messages USING GIN (audience_json);
CREATE INDEX idx_mc_msgs_type     ON mc_messages (msg_type, status, occurred_at DESC);
CREATE INDEX idx_mc_msgs_module   ON mc_messages (module_key, occurred_at DESC);

CREATE TABLE mc_task_responses (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  message_id   BIGINT NOT NULL REFERENCES mc_messages(id),
  user_sub     TEXT NOT NULL,
  outcome      TEXT NOT NULL,                  -- approve | deny | submit | skip
  data_json    JSONB,                          -- schema-validated collect payload
  note         TEXT,                           -- optional approval/skip note
  responded_at TIMESTAMPTZ NOT NULL,
  UNIQUE (message_id, user_sub)                -- resubmit = update in place (optimistic, 409 on race)
);
CREATE INDEX idx_mc_resp_msg ON mc_task_responses (message_id);

CREATE TABLE mc_message_reads (
  message_id BIGINT NOT NULL REFERENCES mc_messages(id),
  user_sub   TEXT NOT NULL,
  read_at    TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (message_id, user_sub)
);
```

Semantics: insert-only arrivals (event_id conflict = ingest no-op); responses insert-on-conflict
update scoped to `user_sub`; `completion=any` CAS `open → done` inside the submit transaction;
`completion=each` completes in the transaction inserting the final missing response;
`expired` is computed at read time via `expiresAt` (`task_json`) — no scheduler.

## 6. Backend (`modules/msgcenter/` + cross-cutting plumbing)

Cross-cutting (placement per AGENTS.md):

- `config/NatsConnectionConfig` — shared `Connection` + `JetStream` management context; feature off
  when `portal.nats-url` blank; JNats I/O **never inside `@Transactional`** (Hikari pool = 5 rule).
- `common/events/EnvelopeValidator` — envelope shape, caps, audience baseline, `completionEvent`
  token; extends `common/Keys` with `COMPLETION_EVENT_RE` (no re-copied regexes).
- `common/events/EventPublisher` — typed publish helper over the shared connection; no-op + warn
  when NATS disabled (CI-safe).

Module vertical slices (ai-hub-style feature packaging, entity/repo/service/controller per slice):

- `ingest/` — `IngestionHandler`: durable pull-consumer loop on virtual threads, starts after boot;
  validate → TX insert (or event_id-conflict no-op) → commit → **ack after commit**;
  final-failure → DLQ publish + ack. `MsgCenterIngestionService` owns the transactional insert.
- `domain/` — `McMessageEntity/Repository`, `McTaskResponseEntity/Repository`,
  `McMessageReadEntity/Repository` + `MsgCenterQueryService` (GIN audience filter
  `users ∋ me OR roles ∩ myRoles OR allUsers`, unread count via `mc_message_reads` join,
  cursor pagination by `occurred_at` desc, sender/link joined from registry)
  + `MsgCenterTaskService` (schema synthesis, `data` validation via networknt, CAS
  status transitions / unique-user insert, 409 on raced/pre-empted/expired — V26 pattern).
- `web/` — `MsgCenterInboxController` (all routes authenticated-user level): `GET
  /api/msgcenter/messages`, `GET /api/msgcenter/unread`, `POST /api/msgcenter/messages/{id}/read`,
  `POST /api/msgcenter/tasks/{id}/respond` (`outcome`, `data`, `note`).
- `MsgCenterResponsePublisher` — response-event publish, after commit.
- **Third-party publish endpoint** — `POST /api/msgcenter/publish`: secret-header auth modeled on
  `AgentCallAuthorizer` / `X-Portal-Agent` (`RemoteToolInvoker`), envelope validation +
  `moduleKey`-exists check (422), Caffeine rate limit keyed by caller identity. Builtin modules
  publish directly via `EventPublisher` (no HTTP hop).

## 7. Subscription contract (publisher-facing)

| Subject | Direction |
|---|---|
| `portal.msg.<moduleKey>.<event>`, `portal.task.<moduleKey>.<event>` | publishers choose event names; declare in manifest `events.published[]` (documentation-grade; registry UI renders it) |
| `portal.task.response.<moduleKey>.<completionEvent \| approved \| denied \| submit>` | originating module subscribes — always its own `moduleKey` namespace, no cross-module trust |
| `portal.dlq.msgcenter` | ops watch; future admin surface |

The response payload: `{v, type:"task-response", moduleKey:"msgcenter", id, threadId, eventRef,
responder, outcome, data, note, respondedAt}` — `data` is the validated payload, `eventRef` the
original message `event_id`.

## 8. Builtin registration & tenant ownership

- `bootstrap/EmbeddedCatalog.java`: new `Module("msgcenter", "Message Center", "inbox", "1.0",
  ["portal-msgcenter-edit"], [
  `Entry("main", "applications", "Message Center", "msg-center", color, -2, false, [])`,
  `Entry("admin", "settings", "Message Center Admin", "msg-center-admin", color, 5, false,
  ["portal-msgcenter-edit"])])`.
- Reconciler upsert + `builtinRealmRoles()` union pick the new role up automatically; `active`
  stays tenant-config-owned (`modules.builtin` semantics — registry PATCH → 409, UI toggle hidden;
  zero extra code).
- Inbox content: open to **all authenticated users** (audience filtering is the gate). Only the
  admin content/endpoint is `portal-msgcenter-edit`-gated.

## 9. UI (Angular 22, zoneless, signals, ds-tokens only)

- `portal/core/workarea/embedded-modules.ts`: add lazy `msg-center` and `msg-center-admin`
  imports; `EMBEDDED_LOAD_PATHS` auto-derives; backend `EmbeddedCatalog` must stay in sync
  (already pinned by `embedded-modules.spec.ts`).
- `core/msg-center/msg-center.store.ts`: signals store (`items`, `filter`, `unread`), `apiFetch`
  with explicit throw policy, 15s badge polling (no SSE in v1).
- Inbox component: tabs Inbox / Notifications / Tasks / History (history = `status ≠ open` or done
  tasks); cards with sender-color accent strip, severity chip, i18n title/body + `body.sections`,
  deep-link button through Bridge (`navigateFromModule`).
- Task detail: `kind: "approval"` → approve/deny + optional note; `kind: "collect"` → generic form
  renderer built from `task_json`: Ajv client-side validation, per-field attrs mirrored onto
  `ds-*` controls, `task.sections` render as titled/described groupings; completed tasks render
  read-only with responses (audit view).
- Unread badge in `app-sidebar` fed from the store signal.
- Admin surface (`msg-center-admin`): read-only audit view — history, DLQ listing, per-module counts.
- All strings via i18n catalog keys (4 languages), `i18n.t()` calls (no TranslatePipe per AGENTS.md).

## 10. Staged rollout

- **Phase 0 — NATS foundation**
  Compose `-js` + `NATS_CLIENT_PORT` + `NATS_URL` env; jnats dependency; `NatsConnectionConfig`;
  `portal.nats-url` in `PortalProperties`/`application.yml`/`application-test.yml`;
  `EnvelopeValidator` + `EventPublisher`; `Keys` extension.
  Files: `docker-compose.yml`, `portal/server/pom.xml`, `config/NatsConnectionConfig.java`,
  `PortalProperties.java`, both application ymls, `common/events/*.java` + unit tests.
- **Phase 1 — ingest & inbox**
  `V30__message_center.sql`; ingest package; domain package + query/read controllers;
  `embedded-modules.ts` entries; store; inbox UI; sidebar badge; i18n catalog keys (4 languages).
  IT: `MsgCenterIngestionIT`.
- **Phase 2 — tasks & responses**
  Submit loop (networknt schema validate, CAS/409, uniqueness), `mc_task_responses` writes,
  response publisher, collect-form renderer + approval UI.
- **Phase 3 — admin & third-party publish**
  `/api/msgcenter/publish` endpoint (rate limit, secret header), admin audit view, README design
  notes entry.
- **Phase 4 — sample sender app (§12)**
  Compose service + app; can start in parallel with Phase 2 (needs Phase 0 + envelope contract;
  the task demos validate Phase 2).

Phase gate: `mvn spotless:apply` then `mvn verify` (checkstyle runs at validate) and
`npm test` / `npm run build` green before moving on; validate `V30` against a fresh `PGSCHEMA`
boot (Flyway applies DDL; Reconciler stays idempotent).

## 11. Test plan

- **Unit**: envelope validate/reject matrix (every cap, every reject keyword, audience baseline,
  `completionEvent` token rules); audience match predicate (`users/roles/allUsers`, intersections,
  unknown-key dead recipients); CAS transitions incl. `each` final-response race (409);
  read model pagination + unread; response subject composition; publish-payload shape.
- **IT `MsgCenterIngestionIT`** (Testcontainers `nats:2.12` started with `-js` + the existing PG
  container): publish while consumer offline → portal boots → arrives exactly once (event_id
  dedupe); redelivery double-ack safety; malformed envelope/schema → `portal.dlq.msgcenter`;
  `completion:"each"` across a user set; approval + collect flows end to end; HTTP publish path
  (missing secret → 401; unknown `moduleKey` → 422).
- **UI specs**: store spec (filters, unread, errors, res.ok discipline), form-renderer spec
  (per-type attrs, Ajv error rendering), i18n fallback helper.
- **Sample sender app**: no CI tests (dev-only demo); manual smoke checklist in its README.

## 12. Sample sender app — `modules/sample-sender/` (iframe demo)

A minimal standalone third-party app in the compose stack, registered in the portal as an
**iframe module content**. It is the reference implementation of the sender contract and doubles as
a live test tool for the whole message-center pipeline.

**Stack (deliberately minimal):** plain Node 20, zero framework — `server.mjs` (~200 lines) +
static `public/` (single-page vanilla JS). Only runtime dependency: `nats` (nats.js) for publish +
subscription. No DB — in-memory state.

**Compose service:**

```yaml
sample-sender:
  build: { context: ./modules/sample-sender }
  environment:
    PORT: 8092
    NATS_URL: nats://nats:4222
    SESSION_SECRET: ${SESSION_SECRET:-change-me-session-secret-32-chars-min}
  ports: ["${SAMPLE_SENDER_HOST_PORT:-28092}:8092"]
  depends_on: { nats: { condition: service_healthy } }
  networks: [tenant]
```

**Server surface:**
`GET /healthz` (NATS connection status → compose healthcheck); `GET /` (demo UI);
`POST /send` (body = envelope; same light checks as the portal apply at publish time; publishes to
`portal.msg.sample-sender.*` / `portal.task.sample-sender.*` with app-generated uuid `id`);
`GET /events` (SSE of everything the app publishes + receives, incl. DLQ echoes for malformed sends).

**Demo UI (3 screens, vanilla):**

1. **Composer** — free-form envelope builder: type dropdown (notification/message/task), audience
   editor (users/roles/allUsers), title/body i18n-map inputs, sections editor, optional task kind +
   field editor with live form preview. Transport toggle: **NATS direct** (default) vs **HTTP**
   (`POST /api/msgcenter/publish` with the secret header) — exercises both sender paths.
2. **Send log** — chronological sent envelopes with ack/off status.
3. **Responses** — subscription on `portal.task.response.sample-sender.>` rendered as a timeline
   (responder, outcome, data, note) — proves the completion loop back to the sender.

**3 implemented demo tasks (one-click presets):**

| # | Task | kind | completion | fields | mapping |
|---|---|---|---|---|---|
| 1 | Expense approval (note + context text in body) | approval | any | — (note only) | default `approved`/`denied` subjects |
| 2 | Daily hours report | collect | each | `hours` number 0–24 required; `comment` multiline | `hours.submitted` |
| 3 | Off-site RSVP | collect | each | `attending` boolean; `meal` enum (≤ 6 labeled) | `rsvp.submitted` |

Task #3 groups its fields under one `task.sections[]` entry (title + description) to demo form sections.

**Portal registration (iframe path):** the app serves its manifest at
`/.well-known/portal-module.json` (key `sample-sender`, content type `iframe`,
`url: http://localhost:28092`, declares `events.published[]` + `events.consumed[]`). Install via
the Module Registry UI in dev (SSRF guard already allows private URLs in compose via
`SSRF_ALLOW_PRIVATE=true`) or pin it in `dev/tenant.json` `modules.external[]` for a deterministic
boot install. Portal-side iframe handling needs **zero code changes** — `AppOutlet`'s existing
iframe path (`sandbox`, `?lang=`, self-frame blocklist, deep-link pending path) applies as-is.

**Guarantees / limits:** dev-only demo (no DB; in-memory state lost on restart; no authentication on
its own endpoints — consistent with dev insecure-by-design, mirrored by solutions/staffing sharing
`SESSION_SECRET`). Must never be installed on a real tenant. Trust: it sits on the tenant network
and can publish anything to the stream — same trust level as the dogfood modules; documented risk,
`portal.dlq.msgcenter` + registry exist-check keep it observable.

## 13. Risks

- Consumer redelivery correctness → `event_id` idempotency + boot-replay IT case.
- Stream growth → 8-day retention + DLQ policy; retention job deferred.
- Bundle budget → Ajv rides a lazy chunk; fallback `@cfworker/json-schema` if `npm run build`
  budget fails.
- Single durable-consumer throughput → fine at expected volumes; parallel sharding deferred.
- Sample app trust level → dev-only by design; documented; never registered on a real tenant.

## 14. Deferred (backlog)

Named recipient lists; Keycloak groups; retention/archiving job; markdown rendering; attachments;
arrays/multi-select fields; conditional/cross-field rules; `default` field values; SSE live inbox
updates (v1 = 15s polling); per-message note toggles; msg-center admin UI for publisher onboarding;
sample-sender multi-tenant support.
