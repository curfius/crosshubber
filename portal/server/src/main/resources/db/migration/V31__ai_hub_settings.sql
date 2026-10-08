-- AI Hub owns its settings storage now (worked item B5): a single-row JSONB
-- document instead of an entry in the generic module_settings table, which is
-- still used by other modules (e.g. msgcenter SMTP config). The existing ai-hub
-- payload is migrated one-shot.

CREATE TABLE ai_hub_settings (
  id         TEXT PRIMARY KEY,
  settings   JSONB       NOT NULL DEFAULT '{}',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version    INT         NOT NULL DEFAULT 0
);

INSERT INTO ai_hub_settings (id, settings, updated_at, version)
SELECT 'default', settings, updated_at, COALESCE(version, 0)
FROM module_settings
WHERE module_key = 'ai-hub'
ON CONFLICT (id) DO NOTHING;

DELETE FROM module_settings WHERE module_key = 'ai-hub';
