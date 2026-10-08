# MESSAGE_CENTER_PLAN — Message Center module (inbox, notifications, tasks)

Status: **approved plan** (2026-09-29; amended 2026-10-06 — claim/draft lifecycle, groups, task
templates, agent tools, Phase 5–7 resequence). Builtin embedded module `msgcenter` that renders
notifications, messages and tasks derived from NATS to the right audience, acts as the audit trail,
and pushes task completion events back onto the broker. No business logic inside the portal — task
ownership stays with the publishers (other modules or third-party apps).

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

Decisions locked during amendment (2026-10-06):

10. **Claim mode** (`task.claim{enabled, mode:"single"}`, `completion:"any"` only): the whole
    audience sees the task; one user claims it (CAS, one winner — 409 losers), works on it across
    sessions (saved drafts), may release (keeping drafts as takeover context) or submit; others see
    "in progress by `<name>`" (display-name snapshot at claim time — zero extra lookup dependency).
    Claim is **not** a completion event.
11. **Drafts** (collect tasks only): explicit save (no autosave), shape-checked only — never
    schema-validated (partial data by design); the submit endpoint remains the sole schema authority.
    Personal drafts for `each`/plain tasks; a single working draft rides the claim in claim mode.
12. **Done is terminal** — no reopen, ever. Re-collection = the publisher sends a new task (new
    event id). Release/reset by the current claimer; **`portal-msgcenter-edit` admin override**
    (force-release / reset) from the admin surface. Reset clears claim + drafts but never the audit.
13. **Groups = membership-driven subscriptions**: users cannot unsubscribe items addressed to them
    personally; they opt out by leaving membership groups. Audience gains a 4th read-time channel
    `groups ∩ myGroups`. Groups: `visibility open|closed` — open = self-join/leave freely;
    closed = owners add/remove members, **self-leave always allowed**. Owners are notified of
    membership churn via the message center itself (dogfooding). Participation pins visibility
    (claimed/drafted/responded items stay visible until done even after leaving).
14. **Group governance**: creation = new dedicated role `portal-msgcenter-groups` (Reconciler
    upsert auto-syncs it); owners manage members; membership rows carry the per-group email flag
    (Phase 7 channel).
15. **Task templates** (`msg-center-templates` entry, dedicated `portal-msgcenter-templates` role):
    shape-only (no approval-chain semantics in the portal — chains stay publisher/tools concern;
    Workflower automates later). Versions immutable, edits = new version; envelope references
    `task.template{key, version}` ⊕ inline `fields/sections` are mutually exclusive (422);
    overridable at publish: `completion`, `completionEvent`, `expiresAt` only. **Resolved at
    publish** (expand → validate → store); strict refs: unknown key/version → 422, retired versions
    still resolve (new picks blocked); **delete only when the template has zero published
    versions**, otherwise retire-only. Ingest and read paths never re-resolve.
16. **Portal agent tools** (registry `Kind.BUILTIN`): `msgcenter_list`, `msgcenter_get` (read, no
    confirmation), `msgcenter_claim_task`, `msgcenter_respond_task`, `msgcenter_send` (all
    `needsConfirmation`); same validators/caps/CAS paths as the HTTP endpoints; template-aware send.
17. **Email channel = Phase 7** (deferred, not v1): SMTP config card + per-group membership email
    flags + one global per-user fallback switch for pinned/roles/allUsers deliveries; toggles are
    not rendered until the channel exists.

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

**Non-goals (v1, as amended 2026-10-06)**

- No markdown/HTML (plain text only, line breaks honored); no attachments; no multi-select/array
  fields; no nested objects; no conditional-required/cross-field rules; no default values.
- No Keycloak groups (portal groups live in `mc_groups` — Keycloak group sync stays out of scope).
- Named recipient lists: **superseded in scope by mc groups** (decision 13) rather than deferred.
- No retention/archiving job; no per-user content overrides.
- No scheduler — expiry computed at read time.
- No execution of task logic inside the portal — responses are events; ownership stays with
  publishers; approval chains are composed by publishers/tools (Workflower later), not by templates.
- No email sending until Phase 7; no autosave of drafts; no per-message muting.

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
    "groups":   ["back-office"],               // ≤ 20; portal groups, resolved at read time
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
    "claim": { "enabled": true, "mode": "single" },        // optional (2026-10-06); completion:"any" only
    "template": { "key": "expense-approval", "version": 3 },
                                                           // ⊕ fields/sections — mutually exclusive (422)
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
  set. Role- and group-addressed messages read at time of read (read-time resolution:
  `users ∋ me OR roles ∩ myRoles OR groups ∩ myGroups OR allUsers`); `users[]` pins the audience at
  send time. Recipients are format-validated but **not existence-checked** — no Keycloak I/O and no
  group-table I/O in the ingest path; unknown values simply never match (harmless dead recipients).
    Group membership churn is therefore retroactive for group-addressed items; the **participation
    pin** (decision 13) keeps items visible to users who have state on them: audience match adds an OR
    branch `OR EXISTS my claim/draft/response row` — leaving a group never hides work you already
    touched until the task is done.
    Named-recipient-lists backlog item: superseded by mc groups (decision 13).
  `completion: "each"` requires a concrete `users[]` (validated at publish; role/allUsers/groups +
  each is a 422). `claim.enabled = true` requires `completion: "any"` (combined with `each` is a 422).
- **Claim (decision 10)**: claim-mode tasks surface a **Take task** action to the whole audience;
  claim = CAS `open → claimed` (`mc_messages.claimed_by_sub/name/at`, display-name snapshot from the
  session principal — `PortalUser.name()`, same resolution as `OidcSuccessHandler`; no lookup
  dependency); losing claimers get 409. Only the claimer sees the respond form; others see
  "in progress by `<name>`" + a read-only card, but the task stays visible to everyone for context.
  Release (`claimed → open`) keeps drafts as takeover context; the next claimer sees "draft available
  from `<name>`" and may **adopt** it or start fresh. Claim publishes **no** completion event.
- **Drafts (decision 11)**: `mc_task_drafts` — one working draft in claim mode (attached to the
  claim, released with it), personal drafts otherwise (`UNIQUE (message_id, user_sub)`). Saved only
  via explicit "Save draft" (no autosave in v1). Draft writes are shape-checked only (flat object,
  known field names, string ≤ 2,000, fields ≤ 20) — **never schema-validated**; the submit endpoint
  remains the sole schema authority. Started-over via reset; `done` tasks keep their final data only.
- **Template reference (decision 15)**: `task.template{key, version}` xor inline `fields/sections`
  (422 when both or neither shape source is present… inline always allowed; the 422 is template +
  inline simultaneously). Publish-time resolution: merge template shape with the inline overrides
  (`completion`, `completionEvent`, `expiresAt` only — envelope fields/sections alongside template
  is a 422), validate the merged envelope against the standard allowlist/caps, **store the expanded
  form** in `mc_messages.task_json` + `template:{key, version}` markers. Ingest and read never
  re-resolve, so later template edits never mutate in-flight tasks. Unknown key/version → 422 at
  publish; retired versions still resolve.
- **Sender display**: `sender.name/color` if present, else `registry_modules.name` + icon, joined
  server-side when composing the read DTO. Plain strings, not i18n maps, not free-form branding.
- **Body/labels**: i18n maps, `en` required, other languages optional with fallback per existing
  label order; plain text, line breaks honored; sections render as titled paragraphs.
- **Deep-link `link`**: opened through Bridge/NavigationCoordinator (`navigateFromModule`); absent
  → plain card.
- **Response routing**: `portal.task.response.<moduleKey>.<completionEvent>`; if no
  `completionEvent`: `portal.task.response.<moduleKey>.approved|denied` (approval) / `.submit`
  (collect). Published **after** the response row commits (audit row first; publish last). Claim /
  release / reset publish no events.

**Caps (enforced at publish + ingest; 422 at publish, DLQ path at ingest)**
Fields ≤ 20; enum options ≤ 50; per-field schema ≤ 8 KB; submitted string values ≤ 2,000 chars;
`pattern` ≤ 128 chars (ReDoS guard); `users[]` ≤ 200; `roles[]` ≤ 20; `groups[]` ≤ 20;
`body.sections` ≤ 5; `task.sections` ≤ 5; template `key` = KEY_RE, `version` ≥ 1;
group `key` = KEY_RE; group name ≤ 100 chars.

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

Contract tested in both suites: browser-pass <=> server-pass - SHIPPED 2026-10-07 as a shared fixture (portal/server/src/test/resources/msgcenter/schema-contract.json, 27 cases) read by both SubmitDataValidatorContractTest (JUnit) and task-form-model.contract.spec.ts (vitest); the TS task-form-model also gained enum/minLength/format/strict-type checks for parity.

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
  audience_json JSONB NOT NULL,                -- {users[], roles[], groups[], allUsers} as declared
  sender_name   TEXT,
  sender_color  TEXT,
  title_json    JSONB NOT NULL,
  body_json     JSONB NOT NULL,
  severity      TEXT,
  thread_id     TEXT,
  link_json     JSONB,                         -- {moduleKey, path}
  task_json     JSONB,                         -- kind/completion/completionEvent/expiresAt/claim/fields/sections
                                               -- + template:{key,version} markers (resolved/expanded shape)
  status        TEXT NOT NULL DEFAULT 'open',  -- tasks: open | claimed | done
                                               -- ('any'+claim CAS open→claimed→done; 'any' plain open→done;
                                               --  'each' flips on final response)
  claimed_by_sub  TEXT,                        -- claim-mode: winner of the CAS (snapshot, not audience)
  claimed_by_name TEXT,                        -- display-name snapshot at claim time
  claimed_at      TIMESTAMPTZ,
  UNIQUE (event_id)
);
CREATE INDEX idx_mc_msgs_audience ON mc_messages USING GIN (audience_json);
CREATE INDEX idx_mc_msgs_type     ON mc_messages (msg_type, status, occurred_at DESC);
CREATE INDEX idx_mc_msgs_module   ON mc_messages (module_key, occurred_at DESC);
CREATE INDEX idx_mc_msgs_claim    ON mc_messages (claimed_by_sub) WHERE claimed_by_sub IS NOT NULL;

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

CREATE TABLE mc_task_drafts (                  -- current drafts only; history lives in activity
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  message_id   BIGINT NOT NULL REFERENCES mc_messages(id) ON DELETE CASCADE,
  user_sub     TEXT NOT NULL,                  -- personal draft owner; in claim mode = current claimer
  data_json    JSONB NOT NULL,                 -- shape-checked only, never schema-validated
  note         TEXT,
  updated_at   TIMESTAMPTZ NOT NULL,
  UNIQUE (message_id, user_sub)
);

CREATE TABLE mc_task_activity (                -- append-only audit trail, one timeline per task
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  message_id   BIGINT NOT NULL REFERENCES mc_messages(id) ON DELETE CASCADE,
  actor_sub    TEXT NOT NULL,
  actor_name   TEXT,                           -- snapshot at action time
  action       TEXT NOT NULL,                  -- claim | release | adopt | draft_save | draft_discard
                                               -- | reset | respond | admin_force_release | admin_reset
  detail_json  JSONB,                          -- snapshot: draft payload / outcome / note / override
  created_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_mc_act_msg ON mc_task_activity (message_id, created_at DESC);

-- final responses are mirrored here as action='respond' rows (full payload, capped) so the
-- timeline tells the whole story; group membership churn gets its own timeline entries below.

CREATE TABLE mc_groups (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  key         TEXT NOT NULL UNIQUE,            -- KEY_RE
  name        TEXT NOT NULL,                   -- ≤ 100 chars
  visibility  TEXT NOT NULL DEFAULT 'open',    -- open (self-join/leave) | closed (owner-managed, self-leave always)
  created_by  TEXT,
  created_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE mc_group_owners (
  group_id  BIGINT NOT NULL REFERENCES mc_groups(id) ON DELETE CASCADE,
  user_sub  TEXT NOT NULL,
  PRIMARY KEY (group_id, user_sub)
);

CREATE TABLE mc_group_members (
  group_id   BIGINT NOT NULL REFERENCES mc_groups(id) ON DELETE CASCADE,
  user_sub   TEXT NOT NULL,
  email_flag BOOLEAN NOT NULL DEFAULT false,   -- Phase 7: email channel per-group opt-in (column from day one)
  added_by   TEXT NOT NULL,                    -- 'self' | owner sub | admin
  joined_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (group_id, user_sub)
);
CREATE INDEX idx_mc_grp_mem_user ON mc_group_members (user_sub);

CREATE TABLE mc_task_templates (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  key        TEXT NOT NULL UNIQUE,             -- KEY_RE
  name       TEXT NOT NULL,
  created_by TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  retired_at TIMESTAMPTZ                       -- retire-only unless zero published versions (delete)
);

CREATE TABLE mc_task_template_versions (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  template_id  BIGINT NOT NULL REFERENCES mc_task_templates(id) ON DELETE CASCADE,
  version      INT NOT NULL,                   -- immutable once saved; edits create version n+1
  kind         TEXT NOT NULL,                  -- approval | collect
  completion   TEXT NOT NULL,
  fields_json  JSONB NOT NULL,                 -- same shape as envelope task.fields[] (allowlist-bounded)
  sections_json JSONB,
  status       TEXT NOT NULL DEFAULT 'published',  -- published | retired (retired still resolves for old refs)
  created_by   TEXT,
  created_at   TIMESTAMPTZ NOT NULL,
  UNIQUE (template_id, version)
);
```

Semantics: insert-only arrivals (event_id conflict = ingest no-op); responses insert-on-conflict
update scoped to `user_sub`; `completion=any` CAS `open → done` inside the submit transaction
(claim-mode variant: `open → claimed` on claim CAS, `claimed → done` on respond submit, claimer
guard `claimed_by_sub = me`); `completion=each` completes in the transaction inserting the final
missing response; `expired` is computed at read time via `expiresAt` (`task_json`) — no scheduler.
Reset = claim columns cleared + drafts deleted + status back to `open` **inside one transaction; the
activity rows survive** (audit is never deleted).

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
  `McMessageReadEntity/Repository`, `McTaskDraftEntity/Repository`,
  `McTaskActivityEntity/Repository` + `MsgCenterQueryService` (GIN audience filter
  `users ∋ me OR roles ∩ myRoles OR groups ∩ myGroups OR allUsers` + participation-pin OR branch,
  unread count via `mc_message_reads` join, cursor pagination by `occurred_at` desc, sender/link
  joined from registry) + `MsgCenterTaskService` (schema synthesis, `data` validation via networknt,
  CAS status transitions / unique-user insert, 409 on raced/pre-empted/expired — V26 pattern;
  claim/release/reset/draft/adopt CAS + activity-append logic; all transitions in one TX, activity
  append rides the same TX).
- `groups/` — `McGroupEntity/Repository`, `McGroupOwnerEntity/Repository`,
  `McGroupMemberEntity/Repository` + `MsgCenterGroupService` (CRUD gated
  `portal-msgcenter-groups` for create/owner-mgmt, admin edit via `portal-msgcenter-edit`;
  join/leave/add/remove with visibility rules; owner-notification publishes on membership churn
  **after commit** via `EventPublisher`, subject `portal.msg.msgcenter.group.<event>`).
- `templates/` — `McTaskTemplateEntity/Repository`, `McTaskTemplateVersionEntity/Repository` +
  `MsgCenterTemplateService` (CRUD + publish-new-version + retire + delete-if-zero-published;
  publish-time resolution: fetch key+version → merge overrides → hand merged envelope to the
  standard validator), read-only listing for senders at `GET /api/msgcenter/templates`.
- `web/` — `MsgCenterInboxController` (all routes authenticated-user level): `GET
  /api/msgcenter/messages`, `GET /api/msgcenter/unread`, `POST /api/msgcenter/messages/{id}/read`,
  `POST /api/msgcenter/tasks/{id}/respond` (`outcome`, `data`, `note`), `POST
  /api/msgcenter/tasks/{id}/claim`, `POST /api/msgcenter/tasks/{id}/release` (`discardDraft`
  flag), `POST /api/msgcenter/tasks/{id}/reset` (claimer), `PUT /api/msgcenter/tasks/{id}/draft`,
  `DELETE …/draft`, `POST …/draft/adopt` (claimer adopts predecessor draft).
- `web/` — `MsgCenterAdminController` (`portal-msgcenter-edit`): task activity timelines
  (`GET /api/msgcenter/tasks/{id}/activity`), DLQ listing, per-module counts,
  `POST /api/msgcenter/admin/tasks/{id}/release|reset` (force-release / admin override).
- `web/` — `MsgCenterGroupController` (self-service: `GET /api/msgcenter/groups` browse,
  `POST /api/msgcenter/groups/{key}/join|leave`; owner/admin management:
  `POST /api/msgcenter/groups`, member add/remove, owner grant/revoke — create gated
  `portal-msgcenter-groups`, owner-mgmt gated by ownership or `portal-msgcenter-edit`) and
  `MsgCenterTemplateController` (`portal-msgcenter-templates`: create/publish-version/retire/
  delete-unused; plus the read-only sender listing at `GET /api/msgcenter/templates`).
- `MsgCenterResponsePublisher` — response-event publish, after commit; also owner-notification
  publishes for group churn (same after-commit discipline; no NATS I/O inside TX).
- **Third-party publish endpoint** — `POST /api/msgcenter/publish`: secret-header auth modeled on
  `AgentCallAuthorizer` / `X-Portal-Agent` (`RemoteToolInvoker`), envelope validation (including
  template resolution) + `moduleKey`-exists check (422), Caffeine rate limit keyed by caller
  identity. Builtin modules publish directly via `EventPublisher` (no HTTP hop).

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
  ["portal-msgcenter-edit", "portal-msgcenter-groups", "portal-msgcenter-templates"], [
  `Entry("main", "applications", "Message Center", "msg-center", color, -2, false, [])`,
  `Entry("admin", "settings", "Message Center Admin", "msg-center-admin", color, 5, false,
  ["portal-msgcenter-edit"])`,
  `Entry("admin", "settings", "Task Template Studio", "msg-center-templates", color, 6, false,
  ["portal-msgcenter-templates"])])`.
- Reconciler upsert + `builtinRealmRoles()` union pick the three roles up automatically; `active`
  stays tenant-config-owned (`modules.builtin` semantics — registry PATCH → 409, UI toggle hidden;
  zero extra code).
- Inbox content: open to **all authenticated users** (audience filtering is the gate). Admin
  content/endpoints are `portal-msgcenter-edit`-gated; group creation/owner-mgmt additionally
  exposes `portal-msgcenter-groups`; template authoring is `portal-msgcenter-templates`-gated.

## 9. UI (Angular 22, zoneless, signals, ds-tokens only)

- `portal/core/workarea/embedded-modules.ts`: add lazy `msg-center`, `msg-center-admin` and
  `msg-center-templates` imports; `EMBEDDED_LOAD_PATHS` auto-derives; backend `EmbeddedCatalog`
  must stay in sync (already pinned by `embedded-modules.spec.ts`).
- `core/msg-center/msg-center.store.ts`: signals store (`items`, `filter`, `unread`, `groups`),
  `apiFetch` with explicit throw policy, 15s badge polling (no SSE in v1).
- Inbox component: tabs Inbox / Notifications / Tasks / History (history = `status ≠ open` or done
  tasks); cards with sender-color accent strip, severity chip, i18n title/body + `body.sections`,
  deep-link button through Bridge (`navigateFromModule`).
- Task detail claim/draft states: **Take task** (audience-wide, claim mode) → for the claimer the
  full form with **Save draft / Release / Reset** actions; for others "in progress by `<name>`"
  chip + read-only card; "draft available from `<name>` — adopt / start fresh" prompt on takeover;
  personal drafts (non-claim tasks) get Save draft / Resume on reopen.
- Task detail: `kind: "approval"` → approve/deny + optional note; `kind: "collect"` → generic form
  renderer built from `task_json`: Ajv client-side validation, per-field attrs mirrored onto
  `ds-*` controls, `task.sections` render as titled/described groupings; completed tasks render
  read-only with responses (audit view).
- Unread badge in `app-sidebar` fed from the store signal.
- Admin surface (`msg-center-admin`): audit view — history, DLQ listing, per-module counts, task
  activity timelines, **force-release / admin-reset** actions, **group management** (create /
  visibility toggle / retire, add-remove owners, add-remove members, member email flags read-only
  until Phase 7).
- Template Studio (`msg-center-templates`): template list (key, name, version history with status),
  **drag & drop form builder** — palette limited to the field-type matrix (short/long text,
  number/integer, boolean, enum ≤ 50 labeled options, date), sections grouping (≤ 5),
  required/label(i18n-map, en required)/multiline/min-max/pattern attrs, live preview against an
  inline envelope; publish (stamp immutable version) / retire / delete-if-zero-published; starter
  presets ("1-level approval", "comments/feedback request", "stage 2 of 2 approval" for
  publisher-orchestrated chains). DnD tech (custom HTML5 vs @angular/cdk) decided at
  implementation against the build budget (falls in list with §14 Ajv fallback).
- User settings — "My groups" card (`usersettings` module scope `msgcenter`): browse/search all
  groups (open + closed, closed marked "owner-managed"), join open groups, leave any group,
  members see their per-group email flag cells greyed "available with email channel" until Phase 7.
- All strings via i18n catalog keys (4 languages), `i18n.t()` calls (no TranslatePipe per AGENTS.md).

## 10. Staged rollout

- **Phase 0 — NATS foundation**
  Compose `-js` + `NATS_CLIENT_PORT` + `NATS_URL` env; jnats dependency; `NatsConnectionConfig`;
  `portal.nats-url` in `PortalProperties`/`application.yml`/`application-test.yml`;
  `EnvelopeValidator` + `EventPublisher`; `Keys` extension.
  Files: `docker-compose.yml`, `portal/server/pom.xml`, `config/NatsConnectionConfig.java`,
  `PortalProperties.java`, both application ymls, `common/events/*.java` + unit tests.
- **Phase 1 — ingest & inbox**
  `V30__message_center.sql` (all tables, day one — unused ones just sit); ingest package; domain
  package + query/read controllers; `embedded-modules.ts` entries; store; inbox UI; sidebar badge;
  i18n catalog keys (4 languages). IT: `MsgCenterIngestionIT`.
- **Phase 2 — tasks, claim lifecycle, drafts, groups audience**
  Submit loop (networknt schema validate, CAS/409, uniqueness), `mc_task_responses` writes,
  response publisher, collect-form renderer + approval UI; **claim/release/reset/draft/adopt** CAS
  machinery + card states; **groups as a 4th audience channel** (read-time resolution +
  participation pin in `MsgCenterQueryService`; group tables already in V30).
- **Phase 3 — admin & third-party publish**
  `/api/msgcenter/publish` endpoint (rate limit, secret header), admin audit view (activity
  timelines, force-release/reset), **group CRUD + owner/members management + owner-notify
  publishes**, user-settings "My groups" card, README design notes entry.
- **Phase 4 — sample sender app (§13)**
  Compose service + app; can start in parallel with Phase 2 (needs Phase 0 + envelope contract;
  the task demos validate Phase 2). Presets #4 (claim takeover) and #5 (group-addressed).
- **Phase 5 — task templates**
  Template Studio UI + template CRUD/versioning + publish-time resolution in all publish paths +
  read-only sender listing; sample-sender preset #6 (template-referenced task).
- **Phase 6 — portal agent tools (§12)**
  Registry entries, confirmation policy, task-audit integration; template-aware `msgcenter_send`.
- **Phase 7 — email channel**
  Compose `mailpit` (default-on dev catcher — UI :28025, SMTP :31025) as the anti-leak target;
  `spring-boot-starter-mail`; SMTP config stored in `module_settings` (`msgcenter`, group
  `email`, password AES-GCM encrypted via CryptoService, masked in reads), `PORTAL_SMTP_*` env
  seeds insert-if-absent at boot (admin edits win); per-group `email_flag` toggles go live in
  the My-groups card; global per-user fallback switch (`user_settings` scope `msgcenter`,
  default off) governs pinned/roles/allUsers deliveries; **immediate-mirror** delivery inside
  the ingest path (event-driven, virtual threads — **no scheduler**; digest/summary deferred to
  Workflower-era); mirror fires on arrival only; plain-text rendering via the item's i18n maps
  with the recipient language (user_settings general.language, en fallback); Caffeine noise cap
  (~50 mails/recipient/day). ITs: Mailpit container (`GenericContainer` + REST assertions).

Phase gate: `mvn spotless:apply` then `mvn verify` (checkstyle runs at validate) and
`npm test` / `npm run build` green before moving on; validate `V30` against a fresh `PGSCHEMA`
boot (Flyway applies DDL; Reconciler stays idempotent).

## 11. Test plan

- **Unit**: envelope validate/reject matrix (every cap, every reject keyword, audience baseline,
  `completionEvent` token rules); audience match predicate (`users/roles/groups/allUsers`,
  intersections, unknown-key dead recipients, participation pin); CAS transitions incl. `each`
  final-response race (409), claim race both directions (409 losers), release/reset outcomes,
  draft shape-check matrix (caps, unknown fields, non-object); group visibility rules (closed
  join = owner-only, self-leave always) + owner-notification publish payload; template resolution
  matrix (unknown key → 422, unknown version → 422, retired resolves, template ⊕ inline = 422,
  override-only-not-shape, version immutability, delete-with-published-versions → 409,
  snapshot-at-store: a later version edit never mutates a stored message);
  read model pagination + unread; response subject composition; publish-payload shape.
- **IT `MsgCenterIngestionIT`** (Testcontainers `nats:2.12` started with `-js` + the existing PG
  container): publish while consumer offline → portal boots → arrives exactly once (event_id
  dedupe); redelivery double-ack safety; malformed envelope/schema → `portal.dlq.msgcenter`;
  `completion:"each"` across a user set; approval + collect flows end to end; claim takeover E2E
  (claim → draft → release → re-claim → adopt → submit); reset-with-draft audit survival; group
  join → membership notification on `portal.msg.msgcenter.*`; HTTP publish path (missing secret →
  401; unknown `moduleKey` → 422; template-ref expands at publish).
- **UI specs**: store spec (filters, unread, errors, res.ok discipline), form-renderer spec
  (per-type attrs, Ajv error rendering), claim/draft card state machine spec, template-builder
  form-model spec (allowlist-constrained palette), i18n fallback helper.
- **Sample sender app**: no CI tests (dev-only demo); manual smoke checklist in its README.

## 12. Portal agent tools (registry `Kind.BUILTIN`, Phase 6)

Registered like the B1 builtin seeds (`getShellConfig`, `listModules`, …) so the existing dispatch,
confirm-dialog and audit machinery (`agent_tool_calls`, V29) applies unchanged:

| Tool | Kind | Confirmation | Behavior |
|---|---|---|---|
| `msgcenter_list` | read | none | list own inbox items (`type`/`status`/`unread` filters) with the same audience/predicate gate |
| `msgcenter_get` | read | none | single item: body/sections/task spec (+ own response for completed tasks) |
| `msgcenter_claim_task` | write | needsConfirmation | claim CAS as the HTTP endpoint; 409 races surface as narratable tool-result errors |
| `msgcenter_respond_task` | write | needsConfirmation | same validation + CAS path as the respond endpoint (claim-guard for claim-mode) |
| `msgcenter_send` | write | needsConfirmation | publish on the user's behalf — same envelope validators/caps/template resolution as `/publish`; no secret-header (session identity already RBAC-attested) |

Tool args are JSON-Schema declarations in the registry; `msgcenter_send` accepts both inline task
shapes and `template{key, version}` references (resolved server-side, identically to the HTTP
publish path). No draft/reset tools — drafts and resets are human-workspace operations.

## 13. Sample sender app — `modules/sample-sender/` (iframe demo)

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
   editor (users/roles/groups/allUsers), title/body i18n-map inputs, sections editor, optional task
   kind + template-ref editor + field editor with live form preview. Transport toggle: **NATS
   direct** (default) vs **HTTP** (`POST /api/msgcenter/publish` with the secret header) —
   exercises both sender paths.
2. **Send log** — chronological sent envelopes with ack/off status.
3. **Responses** — subscription on `portal.task.response.sample-sender.>` rendered as a timeline
   (responder, outcome, data, note) — proves the completion loop back to the sender.

**6 implemented demo tasks (one-click presets):**

| # | Task | kind | completion | fields | mapping |
|---|---|---|---|---|---|
| 1 | Expense approval (note + context text in body) | approval | any | — (note only) | default `approved`/`denied` subjects |
| 2 | Daily hours report | collect | each | `hours` number 0–24 required; `comment` multiline | `hours.submitted` |
| 3 | Off-site RSVP | collect | each | `attending` boolean; `meal` enum (≤ 6 labeled) | `rsvp.submitted` |
| 4 | Loaner laptop request (claim pool) | collect | any | `claim.single`; `justification` short text; `days` integer 1–30 | default `submit` |
| 5 | Group broadcast test | notification | — | audience `groups: [<visible group>]` (UI-free preset: pick any group key already created in dev) | — |
| 6 | Template-referenced task | task via `task.template{key, version}` | per template | any published template listed by `GET /api/msgcenter/templates` | per template `completionEvent` |

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

## 14. Risks

- Consumer redelivery correctness → `event_id` idempotency + boot-replay IT case.
- Stream growth → 8-day retention + DLQ policy; retention job deferred.
- Bundle budget → Ajv **and** the Template Studio DnD machinery ride lazy chunks; fallbacks
  `@cfworker/json-schema` (validation) and custom HTML5 DnD (no CDK) if `npm run build` budgets fail.
- Single durable-consumer throughput → fine at expected volumes; parallel sharding deferred.
- Sample app trust level → dev-only by design; documented; never registered on a real tenant.
- Group churn notifications → could get noisy for large groups; dedupe/digest rules deferred
  (owners may mute via leaving… owners own the group, so worst case the portal-admin retires it).
- Template drift → resolved by expand-at-publish + snapshot-at-store (decision 15); reference
  strictness keeps senders deterministic.

## 15. Deferred (backlog)

Keycloak-group backing for mc groups; retention/archiving job (incl. `mc_task_activity` growth);
markdown rendering; attachments; arrays/multi-select fields; conditional/cross-field rules;
`default` field values; SSE live inbox updates (v1 = 15s polling); per-message note toggles;
per-user email fallback switch UI polish; digest/summary emails (needs a scheduler → Workflower
era); msg-center admin UI for publisher onboarding; sample-sender multi-tenant support;
auto-claim-and-submit combined action (strict claim-first is the v1 contract; relaxing is
backward-compatible later); template picker inside the agent tool schema (send tool takes raw
refs in Phase 6).
- **Hooks for Workflower** (`plan/WORKFLOWER_PLAN.md` owns these): portal-agent wake-up on
  `portal.task.response.*` (matches pending agent-filed tasks → agent continuation), task
  timeout/expiry *events* (expiry is read-time only in v1 — a waiting agent learns a timeout only
  by polling `msgcenter_list`). The message center's contract stays: durable, correlated
  (`eventRef`/`threadId`), auditable events with at-least-once semantics.

## 16. Decision log

| Date | Decision |
|---|---|
| 2026-09-29 | Original plan approved (decisions 1–9). |
| 2026-10-06 | Claim + draft + takeover + audit + reset added (case "one of group completes"); claimed-by visible with display-name snapshot; done terminal; admin override for release/reset; activity timeline admin-only. |
| 2026-10-06 | Groups introduced (membership = subscription; 4th read-time audience channel); participation pin; creation role `portal-msgcenter-groups`; owners notified of churn via msgcenter itself; per-`user_settings` mute idea replaced by group model. |
| 2026-10-06 | Group `visibility open\|closed` — self-leave always allowed, closed join = owner-managed. |
| 2026-10-06 | Task templates added (shape-only; dedicated role; immutable versions; strict publish-time resolution; snapshot-at-store; delete only if zero published versions). |
| 2026-10-06 | Agent tools approved as full loop incl. `msgcenter_send`; email deferred to Phase 7 (per-group flags + global fallback switch); phases resequenced 0–7. |
| 2026-10-06 | **Phases 0-3 + Phase 5 implemented** (backend + UI, 226 server tests / 169 UI tests / build+verify green; live dev-stack verified end-to-end). Deviations recorded below. |
| 2026-10-07 | **Phases 4 + 6 implemented** (sample-sender + portal agent tools; 235 server tests / 20 UI spec files green; live-stack verified - HMAC publish to ingest, DLQ no-poison-pill, NATS-direct, response-tree fix confirmed on the running stack). Response subjects moved to portal.taskresponse.* (see deviation 8). Remaining: Phase 7 (email), schema-equivalence spot checks, e2e pack coverage. |
| 2026-10-07 | **Phase 7 spec locked** (Mailpit default-on catcher + REST-asserted container ITs; SMTP config in module_settings msgcenter/email with AES-GCM secret + insert-if-absent env seed; immediate-mirror arrival-only engine, virtual-thread send, plain-text i18n rendering, recipient language from user_settings, Caffeine noise cap; no scheduler, no inbound email, digest deferred to Workflower). |

**Implementation deviations (2026-10-06, Phase 0–3+5 build):**

1. **Submit validation engine**: hand-rolled rule engine per the allowlist semantics
   (`AllowlistSubmitValidator`) instead of `networknt:json-schema-validator` — deterministic,
   dependency-free; same contract (required/type/enum/format/pattern/bounds/caps). Ajv
   browser-side replaced by the TS `task-form-model` helpers (same rules; Ajv stays the approved
   upgrade path). Envelope shape unaffected.
2. **DLQ subject**: `portal.dlq.>` added to the PORTAL_MESSAGES stream subjects (the plan listed
   only portal.msg/task prefixes; the DLQ must live on the durable stream to be observable).
   NATS compose now runs `-js -m 8222` (monitoring port needed by the compose healthcheck).
3. **`mc_message_reads` / `mc_group_owners` / `mc_group_members`**: surrogate `BIGSERIAL id` +
   UNIQUE(message,user)/(group,user) instead of composite PKs (Hibernate IdClass friction); 
   `mc_task_drafts.user_name` added — display-name snapshot for takeover UX.
4. **Query predicate arrays**: roles/groups/users elements embedded as `ARRAY[...]::text[]`
   literals after regex-filtering (`[A-Za-z0-9_-]`) instead of JDBC array params (GIN-hitting,
   driver-agnostic); all identity values stay bound parameters.
5. **Template Studio DnD**: native HTML5 drag events (draggable rows) instead of `@angular/cdk`
   — reorder-only palette, inside the bundle budget; field editing is signal-driven.
6. **HTTP publish auth**: `X-MsgCenter-Key` + `X-MsgCenter-Secret` = base64url(HMAC-SHA256(session
   secret, callerKey)); envelope moduleKey must equal the caller key (anti-spoof); SecurityConfig
   permits the path (auth is the controller's HMAC gate, never session-based).
7. **2026-10-06 live bugfix (inbox 500s)**: the audience predicate originally spelled the `?|`
   JSONB operator, whose bare `?` is a bind placeholder for the Postgres JDBC driver → "No value
   specified for parameter N" 500s on `GET /messages` and `/unread`. Fixed by switching to the
   function form `jsonb_exists_any()` (no `?` characters) plus a paren-closure contract
   (predicate appended unclosed; every caller appends exactly one `)`). Regression pinned by
   `MsgCenterIngestionIT#inboxReadModelRunsTheAudiencePredicate` — the ONLY tests executing
   listOwn/unread/markRead against real SQL; auth-gated endpoints had defaulted out of IT scope.
8. **Response-subject tree moved (Phase 4 prerequisite)**: response events publish on
   `portal.taskresponse.<moduleKey>.<event>` instead of `portal.task.response.…` — the old tree
   fell INSIDE the durable consumer's filter (`portal.task.>`), so msgcenter would have ingested
   its own completion events into the inbox/DLQ on every task completion. The stream now carries
   four subject families (`portal.msg.>`, `portal.task.>`, `portal.taskresponse.>`,
   `portal.dlq.>`); `ensureStream` patches subject drift on pre-existing streams at boot.
9. **Phase 6 shipped 2026-10-07** (agent tools): `MsgCenterAgentTools`
   (`modules/msgcenter/agent/`) merged into the builtin catalogue via `BuiltinToolHandlers`
   (name-prefix routing, zero dispatcher changes — confirmation parking/RBAC/audit free). Reads
   audience-gated via the new `MsgCenterQueryService.getOwn/visible`; `msgcenter_send` reuses
   `MsgCenterPublishService` (validators, template resolution, moduleKey check) minus the
   secret header. 9 unit tests.
10. **Phase 4 shipped 2026-10-07** (sample-sender): `modules/sample-sender/` — Node 20 +
    nats.js only; three transports (NATS-direct, HTTP publish with locally-computed HMAC secret,
    light-check rejections), response + DLQ subscriptions feeding the SSE timeline, 6 presets +
    malformed demo. Registered in the dev registry (runtime-owned row via registry insert —
    NOT pinned in `dev/tenant.json`, keeping the fail-fast reconciler independent of a demo
    container). Deviation from §13: DLQ echo subscribed directly (plan's "DLQ echoes"
    implemented as a live portal.dlq.msgcenter subscription).
    **Live-verified**: HMAC publish → ingest → mc_messages row; malformed severity → DLQ copy +
    no poison-pill; NATS-direct publish → row; `portal.taskresponse.*` completion event → sender
    timeline, durable consumer does NOT re-ingest (fix #8 confirmed on the live stack).
11. **Phase 7 shipped 2026-10-07** (email channel): SmtpConfigService (module_settings
    `msgcenter`.`email`, AES-GCM secret via CryptoService, PORTAL_SMTP_* insert-if-absent seed
    runner @Order(200)), MsgCenterEmailMirror (arrival-only hook in the ingest loop —
    dedupe no-ops never mirror; virtual-thread sender with per-recipient Caffeine cap 50/24h;
    self-supplied address in user_settings msgcenter.{email,emailFallback}; recipient language
    from user_settings general.language), MsgCenterEmailController (masked admin card GET/PUT +
    send-test + self-service my-email), Mailpit in dev compose (default-on :28025/:31025).
    Deviation: recipient addresses are SELF-SUPPLIED (user_settings), NOT Keycloak lookups —
    keeps Keycloak I/O out of the mirror path (mirrors the ingest-path rule).
    **Live-verified**: compose env seed landed (host=mailpit); allUsers publish via sample-sender
    → fallback subscriber mirrored into Mailpit (UTF-8 quoted-printable, plain-text footer);
    4 delivery ITs green. Test-stability lessons recorded: Mailpit search `to:` unreliable
    through HTTP query params (client-side To filter instead), container readines gate
    (@BeforeAll), shared-config restoring after the secret-masking test (JUnit random order).
