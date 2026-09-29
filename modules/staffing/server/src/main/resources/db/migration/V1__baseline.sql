-- Staffing module baseline (AI_MODULES_PLAN H3): RFP/RFQ intake + CV matching.
-- Tables live in the module schema (PGSCHEMA); Flyway owns all DDL.

CREATE TABLE rfp (
  id             UUID PRIMARY KEY,
  client         TEXT        NOT NULL,
  title          TEXT        NOT NULL,
  kind           TEXT        NOT NULL DEFAULT 'rfp',
  status         TEXT        NOT NULL DEFAULT 'new',
  deadline       TIMESTAMPTZ NOT NULL,
  requirements   JSONB       NOT NULL DEFAULT '{}'::jsonb,
  spec_doc_ref   TEXT,
  created_by     TEXT        NOT NULL,
  version        INTEGER     NOT NULL DEFAULT 0,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rfp_status ON rfp (status);

CREATE TABLE candidate_profile (
  id             UUID PRIMARY KEY,
  source_ref     TEXT        NOT NULL,
  name           TEXT        NOT NULL,
  headline       TEXT,
  skills         JSONB       NOT NULL DEFAULT '[]'::jsonb,
  seniority      TEXT,
  languages      JSONB       NOT NULL DEFAULT '[]'::jsonb,
  availability   TEXT,
  status         TEXT        NOT NULL DEFAULT 'parsed',
  parsed_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_candidate_status ON candidate_profile (status);
CREATE INDEX idx_candidate_source_ref ON candidate_profile (source_ref);

CREATE TABLE match_run (
  id             UUID PRIMARY KEY,
  rfp_id         UUID        NOT NULL,
  params         JSONB       NOT NULL DEFAULT '{}'::jsonb,
  status         TEXT        NOT NULL DEFAULT 'running',
  results        JSONB       NOT NULL DEFAULT '[]'::jsonb,
  created_by     TEXT        NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_match_run_rfp ON match_run (rfp_id, created_at DESC);

CREATE TABLE shortlist (
  id             UUID PRIMARY KEY,
  rfp_id         UUID        NOT NULL,
  candidate_ids  JSONB       NOT NULL DEFAULT '[]'::jsonb,
  created_by     TEXT        NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_shortlist_rfp ON shortlist (rfp_id, created_at DESC);
