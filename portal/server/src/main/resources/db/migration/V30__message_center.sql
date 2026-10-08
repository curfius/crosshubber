-- Message center (MESSAGE_CENTER_PLAN, V30). DDL ships day-one for all sub-features
-- (inbox/tasks, claim/drafts/audit, groups, templates) — tables unused by earlier phases
-- simply sit empty. Insert-only ingest; per-user state in side tables; append-only audit.

CREATE TABLE mc_messages (
  id              BIGSERIAL PRIMARY KEY,
  event_id        TEXT        NOT NULL UNIQUE,
  msg_type        TEXT        NOT NULL,     -- notification | message | task
  module_key      TEXT        NOT NULL,
  nats_subject    TEXT        NOT NULL,
  nats_seq        BIGINT,                   -- JetStream stream sequence (replay/debug)
  occurred_at     TIMESTAMPTZ NOT NULL,
  audience_json   JSONB       NOT NULL,     -- {users[], roles[], groups[], allUsers} as declared
  sender_name     TEXT,
  sender_color    TEXT,
  title_json      JSONB       NOT NULL,
  body_json       JSONB       NOT NULL,
  severity        TEXT,
  thread_id       TEXT,
  link_json       JSONB,                    -- {moduleKey, path} deep-link
  task_json       JSONB,                    -- resolved task spec (template markers included)
  status          TEXT        NOT NULL DEFAULT 'open', -- tasks: open | claimed | done
  claimed_by_sub  TEXT,
  claimed_by_name TEXT,
  claimed_at      TIMESTAMPTZ,
  version         BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_mc_msgs_audience ON mc_messages USING GIN (audience_json);
CREATE INDEX idx_mc_msgs_type     ON mc_messages (msg_type, status, occurred_at DESC);
CREATE INDEX idx_mc_msgs_module   ON mc_messages (module_key, occurred_at DESC);
CREATE INDEX idx_mc_msgs_claim    ON mc_messages (claimed_by_sub) WHERE claimed_by_sub IS NOT NULL;
CREATE INDEX idx_mc_msgs_thread   ON mc_messages (thread_id) WHERE thread_id IS NOT NULL;

CREATE TABLE mc_task_responses (
  id           BIGSERIAL PRIMARY KEY,
  message_id   BIGINT      NOT NULL REFERENCES mc_messages(id) ON DELETE CASCADE,
  user_sub     TEXT        NOT NULL,
  outcome      TEXT        NOT NULL,      -- approve | deny | submit | skip
  data_json    JSONB,                     -- schema-validated collect payload
  note         TEXT,
  responded_at TIMESTAMPTZ NOT NULL,
  version      BIGINT      NOT NULL DEFAULT 0,
  UNIQUE (message_id, user_sub)           -- resubmit = update in place (409 on race)
);
CREATE INDEX idx_mc_resp_msg ON mc_task_responses (message_id);
CREATE INDEX idx_mc_resp_user ON mc_task_responses (user_sub);

CREATE TABLE mc_message_reads (
  id         BIGSERIAL PRIMARY KEY,
  message_id BIGINT      NOT NULL REFERENCES mc_messages(id) ON DELETE CASCADE,
  user_sub   TEXT        NOT NULL,
  read_at    TIMESTAMPTZ NOT NULL,
  UNIQUE (message_id, user_sub)
);

CREATE TABLE mc_task_drafts (
  id           BIGSERIAL PRIMARY KEY,
  message_id   BIGINT      NOT NULL REFERENCES mc_messages(id) ON DELETE CASCADE,
  user_sub     TEXT        NOT NULL,     -- personal draft owner; in claim mode = current claimer
  user_name    TEXT,                     -- display-name snapshot at save time (takeover UX)
  data_json    JSONB       NOT NULL,     -- shape-checked only, never schema-validated
  note         TEXT,
  updated_at   TIMESTAMPTZ NOT NULL,
  version      BIGINT      NOT NULL DEFAULT 0,
  UNIQUE (message_id, user_sub)
);
CREATE INDEX idx_mc_draft_msg ON mc_task_drafts (message_id);

CREATE TABLE mc_task_activity (
  id          BIGSERIAL PRIMARY KEY,
  message_id  BIGINT      NOT NULL REFERENCES mc_messages(id) ON DELETE CASCADE,
  actor_sub   TEXT        NOT NULL,
  actor_name  TEXT,                     -- display-name snapshot at action time
  action      TEXT        NOT NULL,     -- claim | release | adopt | draft_save | draft_discard
                                        -- | reset | respond | admin_force_release | admin_reset
  detail_json JSONB,                    -- snapshot: draft payload / outcome / note / override
  created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_mc_act_msg ON mc_task_activity (message_id, created_at DESC);

CREATE TABLE mc_groups (
  id         BIGSERIAL PRIMARY KEY,
  key        TEXT        NOT NULL UNIQUE,   -- KEY_RE
  name       TEXT        NOT NULL,
  visibility TEXT        NOT NULL DEFAULT 'open', -- open (self-join/leave) | closed (owner-managed)
  created_by TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  retired_at TIMESTAMPTZ,
  version    BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE mc_group_owners (
  id       BIGSERIAL PRIMARY KEY,
  group_id BIGINT NOT NULL REFERENCES mc_groups(id) ON DELETE CASCADE,
  user_sub TEXT   NOT NULL,
  UNIQUE (group_id, user_sub)
);

CREATE TABLE mc_group_members (
  id         BIGSERIAL   PRIMARY KEY,
  group_id   BIGINT      NOT NULL REFERENCES mc_groups(id) ON DELETE CASCADE,
  user_sub   TEXT        NOT NULL,
  email_flag BOOLEAN     NOT NULL DEFAULT FALSE,  -- Phase 7: per-group email opt-in
  added_by   TEXT        NOT NULL,                -- 'self' | owner sub | admin
  joined_at  TIMESTAMPTZ NOT NULL,
  UNIQUE (group_id, user_sub)
);
CREATE INDEX idx_mc_grp_mem_user ON mc_group_members (user_sub);

CREATE TABLE mc_task_templates (
  id         BIGSERIAL PRIMARY KEY,
  key        TEXT        NOT NULL UNIQUE,
  name       TEXT        NOT NULL,
  created_by TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  retired_at TIMESTAMPTZ,                       -- delete only while zero published versions
  version    BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE mc_task_template_versions (
  id            BIGSERIAL PRIMARY KEY,
  template_id   BIGINT      NOT NULL REFERENCES mc_task_templates(id) ON DELETE CASCADE,
  version       INT         NOT NULL,         -- immutable once saved; edits create version n+1
  kind          TEXT        NOT NULL,
  completion    TEXT        NOT NULL,
  fields_json   JSONB       NOT NULL,         -- envelope task.fields[] shape (allowlist-bounded)
  sections_json JSONB,
  status        TEXT        NOT NULL DEFAULT 'published', -- published | retired
  created_by    TEXT,
  created_at    TIMESTAMPTZ NOT NULL,
  UNIQUE (template_id, version)
);
CREATE INDEX idx_mc_tpl_ver_pub ON mc_task_template_versions (template_id, status);
