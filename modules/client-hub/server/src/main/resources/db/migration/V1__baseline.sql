-- Client Hub baseline schema (own schema PGSCHEMA=clienthub; Flyway owns all DDL).

CREATE TABLE account (
  id          UUID PRIMARY KEY,
  key         TEXT        NOT NULL UNIQUE,
  name        TEXT        NOT NULL,
  type        TEXT        NOT NULL DEFAULT 'client',
  industry    TEXT,
  website     TEXT,
  phone       TEXT,
  email       TEXT,
  address     JSONB       NOT NULL DEFAULT '{}'::jsonb,
  owner       TEXT,
  tags        JSONB       NOT NULL DEFAULT '[]'::jsonb,
  status      TEXT        NOT NULL DEFAULT 'active',
  notes       TEXT,
  external_refs JSONB     NOT NULL DEFAULT '{}'::jsonb,
  version     INTEGER     NOT NULL DEFAULT 0,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_account_name ON account (name);
CREATE INDEX idx_account_status ON account (status);

CREATE TABLE contact (
  id           UUID PRIMARY KEY,
  account_id   UUID        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
  first_name   TEXT        NOT NULL,
  last_name    TEXT        NOT NULL,
  email        TEXT,
  phone        TEXT,
  role         TEXT,
  primary_contact BOOLEAN  NOT NULL DEFAULT false,
  notes        TEXT,
  version      INTEGER     NOT NULL DEFAULT 0,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_contact_account ON contact (account_id);
CREATE INDEX idx_contact_email ON contact (email);
