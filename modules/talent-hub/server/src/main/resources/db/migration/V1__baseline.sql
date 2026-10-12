-- Talent Hub baseline (STAFFING_IMPLEMENTATION_PLAN §1, fresh v2 schema — D2: old staffing
-- data discarded, no migration code). Tables live in the module schema (PGSCHEMA);
-- Flyway owns all DDL.

CREATE TABLE client (
  id            UUID PRIMARY KEY,
  name          TEXT        NOT NULL,
  industry      TEXT,
  account_owner TEXT,
  contacts      JSONB       NOT NULL DEFAULT '[]'::jsonb,
  notes         TEXT,
  external_refs JSONB       NOT NULL DEFAULT '{}'::jsonb,
  version       INTEGER     NOT NULL DEFAULT 0,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE rfp (
  id            UUID PRIMARY KEY,
  client_id     UUID        NOT NULL REFERENCES client (id),
  title         TEXT        NOT NULL,
  kind          TEXT        NOT NULL DEFAULT 'rfp',
  phase         TEXT        NOT NULL DEFAULT 'draft',
  service_type  TEXT        NOT NULL DEFAULT 'tm',
  source        TEXT        NOT NULL DEFAULT 'manual',
  deadline      TIMESTAMPTZ,
  description   TEXT,
  requirements  JSONB       NOT NULL DEFAULT '{}'::jsonb,
  analysis      JSONB,
  document_refs JSONB       NOT NULL DEFAULT '[]'::jsonb,
  created_by    TEXT        NOT NULL,
  version       INTEGER     NOT NULL DEFAULT 0,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rfp_phase ON rfp (phase);
CREATE INDEX idx_rfp_client ON rfp (client_id);

CREATE TABLE position (
  id           UUID PRIMARY KEY,
  rfp_id       UUID        NOT NULL REFERENCES rfp (id),
  title        TEXT        NOT NULL,
  headcount    INTEGER     NOT NULL DEFAULT 1,
  service_type TEXT,
  requirements JSONB       NOT NULL DEFAULT '{}'::jsonb,
  status       TEXT        NOT NULL DEFAULT 'open',
  order_index  INTEGER     NOT NULL DEFAULT 0,
  version      INTEGER     NOT NULL DEFAULT 0,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_position_rfp ON position (rfp_id);

CREATE TABLE candidate_profile (
  id               UUID PRIMARY KEY,
  source_ref       TEXT        NOT NULL,
  name             TEXT        NOT NULL,
  headline         TEXT,
  skills           JSONB       NOT NULL DEFAULT '[]'::jsonb,
  seniority        TEXT,
  languages        JSONB       NOT NULL DEFAULT '[]'::jsonb,
  availability     TEXT,
  status           TEXT        NOT NULL DEFAULT 'parsed',
  parsed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  kind             TEXT        NOT NULL DEFAULT 'external',
  contact          JSONB       NOT NULL DEFAULT '{}'::jsonb,
  assignment       JSONB,
  expectations     JSONB,
  consent          JSONB,
  last_enriched_at TIMESTAMPTZ,
  notes            TEXT,
  version          INTEGER     NOT NULL DEFAULT 0,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_candidate_status ON candidate_profile (status);
CREATE INDEX idx_candidate_source_ref ON candidate_profile (source_ref);

CREATE TABLE candidate_process (
  id            UUID PRIMARY KEY,
  rfp_id        UUID        NOT NULL REFERENCES rfp (id),
  position_id   UUID        NOT NULL REFERENCES position (id),
  candidate_id  UUID        NOT NULL REFERENCES candidate_profile (id),
  stage         TEXT        NOT NULL DEFAULT 'pooled',
  stage_history JSONB       NOT NULL DEFAULT '[]'::jsonb,
  fit           JSONB,
  notes         TEXT,
  created_by    TEXT        NOT NULL,
  version       INTEGER     NOT NULL DEFAULT 0,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_process_position ON candidate_process (position_id);
CREATE INDEX idx_process_candidate ON candidate_process (candidate_id);
CREATE INDEX idx_process_rfp ON candidate_process (rfp_id);

CREATE TABLE submission (
  id           UUID PRIMARY KEY,
  rfp_id       UUID        NOT NULL REFERENCES rfp (id),
  process_ids  JSONB       NOT NULL DEFAULT '[]'::jsonb,
  note         TEXT,
  response     JSONB,
  sent_by      TEXT        NOT NULL,
  sent_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  responded_at TIMESTAMPTZ,
  version      INTEGER     NOT NULL DEFAULT 0,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_submission_rfp ON submission (rfp_id);

CREATE TABLE rfp_phase_history (
  id         UUID PRIMARY KEY,
  rfp_id     UUID        NOT NULL REFERENCES rfp (id),
  from_phase TEXT,
  to_phase   TEXT        NOT NULL,
  "trigger"  TEXT        NOT NULL,
  actor      TEXT        NOT NULL,
  note       TEXT,
  at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rfp_phase_history_rfp ON rfp_phase_history (rfp_id);

CREATE TABLE match_run (
  id         UUID PRIMARY KEY,
  rfp_id     UUID        NOT NULL,
  params     JSONB       NOT NULL DEFAULT '{}'::jsonb,
  status     TEXT        NOT NULL DEFAULT 'running',
  results    JSONB       NOT NULL DEFAULT '[]'::jsonb,
  engine     TEXT        NOT NULL DEFAULT 'rules',
  created_by TEXT        NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_match_run_rfp ON match_run (rfp_id, created_at DESC);
