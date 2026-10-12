-- Connector outbox log (P4.1): emulated messaging/crm/hris writes become inspectable rows.
CREATE TABLE connector_outbox (
  id      UUID PRIMARY KEY,
  kind    TEXT        NOT NULL,
  subject TEXT        NOT NULL,
  body    JSONB       NOT NULL DEFAULT '{}'::jsonb,
  at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_at ON connector_outbox (at DESC);
