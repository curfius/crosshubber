-- Solutions module baseline (AI_MODULES_PLAN H2): project delivery tracking.
-- Tables live in the module schema (PGSCHEMA); Flyway owns all DDL.

CREATE TABLE client (
  id             UUID PRIMARY KEY,
  name           TEXT        NOT NULL,
  industry       TEXT,
  account_owner  TEXT,
  contacts       TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE project (
  id             UUID PRIMARY KEY,
  client_id      UUID        NOT NULL REFERENCES client (id),
  name           TEXT        NOT NULL,
  stage          TEXT        NOT NULL DEFAULT 'lead',
  owner          TEXT,
  budget         NUMERIC,
  health         TEXT,
  stage_data     JSONB       NOT NULL DEFAULT '{}'::jsonb,
  version        INTEGER     NOT NULL DEFAULT 0,
  started_at     TIMESTAMPTZ,
  closed_at      TIMESTAMPTZ,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_project_stage ON project (stage);
CREATE INDEX idx_project_client ON project (client_id);

-- Immutable transition trail: no updates, no deletes.
CREATE TABLE stage_event (
  id             BIGSERIAL PRIMARY KEY,
  project_id     UUID        NOT NULL REFERENCES project (id),
  from_stage     TEXT        NOT NULL,
  to_stage       TEXT        NOT NULL,
  actor          TEXT        NOT NULL,
  note           TEXT,
  occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_stage_event_project ON stage_event (project_id, occurred_at);

-- Links to documents in the module's configured OneDrive root; per-doc RAG opt-in.
CREATE TABLE document_link (
  id             BIGSERIAL PRIMARY KEY,
  project_id     UUID        NOT NULL REFERENCES project (id),
  document_ref   TEXT        NOT NULL,
  title          TEXT        NOT NULL,
  rag_enabled    BOOLEAN     NOT NULL DEFAULT false,
  added_by       TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, document_ref)
);

CREATE TABLE note (
  id             BIGSERIAL PRIMARY KEY,
  project_id     UUID        NOT NULL REFERENCES project (id),
  author         TEXT        NOT NULL,
  body           TEXT        NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_note_project ON note (project_id);
