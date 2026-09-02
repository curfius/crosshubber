CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS modules (
  key        TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  icon       TEXT,
  roles      TEXT[] NOT NULL DEFAULT '{}',
  active     BOOLEAN NOT NULL DEFAULT TRUE,
  builtin    BOOLEAN NOT NULL DEFAULT FALSE,
  version         TEXT,
  manifest_digest TEXT,
  managed_by      TEXT NOT NULL DEFAULT 'manual',
  source_url      TEXT,
  security_roles  JSONB NOT NULL DEFAULT '[]',
  base_url        TEXT,
  health          TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Legacy schemas created before these columns existed:
ALTER TABLE modules ADD COLUMN IF NOT EXISTS version         TEXT;
ALTER TABLE modules ADD COLUMN IF NOT EXISTS manifest_digest TEXT;
ALTER TABLE modules ADD COLUMN IF NOT EXISTS managed_by      TEXT NOT NULL DEFAULT 'manual';
ALTER TABLE modules ADD COLUMN IF NOT EXISTS source_url      TEXT;
ALTER TABLE modules ADD COLUMN IF NOT EXISTS security_roles  JSONB NOT NULL DEFAULT '[]';
ALTER TABLE modules ADD COLUMN IF NOT EXISTS base_url        TEXT;
ALTER TABLE modules ADD COLUMN IF NOT EXISTS health          TEXT;

CREATE TABLE IF NOT EXISTS entry_points (
  id                SERIAL PRIMARY KEY,
  module_key        TEXT NOT NULL REFERENCES modules(key) ON DELETE CASCADE,
  entry_key         TEXT NOT NULL,
  category          TEXT NOT NULL CONSTRAINT entry_points_category_check
                    CHECK (category IN ('applications','settings','features','admin-settings','user-settings')),
  name              TEXT NOT NULL,
  description       TEXT,
  type              TEXT NOT NULL CONSTRAINT entry_points_type_check
                    CHECK (type IN ('iframe','embedded','mfe','link')),
  url               TEXT,
  sandbox           TEXT[],
  allow             TEXT,
  load_path         TEXT,
  entry_url         TEXT,
  element           TEXT,
  parent_entry_key  TEXT,
  group_key         TEXT,
  sort_order        INTEGER NOT NULL DEFAULT 0,
  roles             TEXT[] NOT NULL DEFAULT '{}',
  active            BOOLEAN NOT NULL DEFAULT TRUE,
  icon              TEXT,
  color             TEXT,
  multi             BOOLEAN NOT NULL DEFAULT FALSE,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(module_key, entry_key)
);
ALTER TABLE entry_points ADD COLUMN IF NOT EXISTS multi BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS entry_point_groups (
  id          SERIAL PRIMARY KEY,
  group_key   TEXT NOT NULL UNIQUE,
  category    TEXT NOT NULL CONSTRAINT entry_point_groups_category_check
              CHECK (category IN ('applications','settings','features')),
  name        TEXT NOT NULL,
  parent_key  TEXT REFERENCES entry_point_groups(group_key) ON DELETE CASCADE,
  sort_order  INTEGER NOT NULL DEFAULT 0,
  icon        TEXT,
  roles       TEXT[] NOT NULL DEFAULT '{}',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS module_versions (
  id           SERIAL PRIMARY KEY,
  module_key   TEXT NOT NULL REFERENCES modules(key) ON DELETE CASCADE,
  version      TEXT NOT NULL,
  digest       TEXT NOT NULL,
  manifest     JSONB NOT NULL,
  installed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  installed_by TEXT NOT NULL,
  status       TEXT NOT NULL DEFAULT 'active'
);

CREATE TABLE IF NOT EXISTS user_module_settings (
  user_id    TEXT NOT NULL,
  module_key TEXT NOT NULL REFERENCES modules(key) ON DELETE CASCADE,
  value      JSONB NOT NULL DEFAULT '{}',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, module_key)
);

CREATE TABLE IF NOT EXISTS module_settings (
  module_key TEXT PRIMARY KEY,
  settings   JSONB NOT NULL DEFAULT '{}',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS instance_settings (
  id         INTEGER PRIMARY KEY CHECK (id = 1),
  settings   JSONB NOT NULL DEFAULT '{}',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS llm_providers (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  enabled    BOOLEAN NOT NULL DEFAULT false,
  base_url   TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS llm_tokens (
  id            TEXT PRIMARY KEY,
  provider_id   TEXT NOT NULL REFERENCES llm_providers(id) ON DELETE CASCADE,
  name          TEXT NOT NULL,
  encrypted_key TEXT,
  enabled       BOOLEAN NOT NULL DEFAULT true,
  models        JSONB NOT NULL DEFAULT '[]',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS workspaces (
  id           UUID NOT NULL DEFAULT gen_random_uuid(),
  user_id      TEXT NOT NULL,
  name         TEXT NOT NULL,
  description  TEXT NOT NULL DEFAULT '',
  layout       JSONB,
  groups       JSONB NOT NULL DEFAULT '{}',
  focused_group_id TEXT,
  hide_single_tab_toolbar BOOLEAN NOT NULL DEFAULT FALSE,
  locked       BOOLEAN NOT NULL DEFAULT FALSE,
  color        TEXT NOT NULL DEFAULT '',
  status       TEXT NOT NULL DEFAULT '',
  saved_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, name)
);
ALTER TABLE workspaces ADD COLUMN IF NOT EXISTS locked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workspaces ADD COLUMN IF NOT EXISTS color TEXT NOT NULL DEFAULT '';
ALTER TABLE workspaces ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT '';

CREATE TABLE IF NOT EXISTS favorites (
  user_id    TEXT NOT NULL,
  item_type  TEXT NOT NULL CHECK (item_type IN ('app', 'workspace')),
  item_key   TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, item_type, item_key)
);

CREATE TABLE IF NOT EXISTS chat_conversations (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL,
  title      TEXT NOT NULL DEFAULT 'New Chat',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS chat_messages (
  id              SERIAL PRIMARY KEY,
  conversation_id TEXT NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
  role            TEXT NOT NULL CHECK (role IN ('user','assistant')),
  content         TEXT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_chat_messages_conv ON chat_messages(conversation_id, id);

-- Converge legacy category/type constraints (targeted: never drop unrelated checks).
DO $mig$
DECLARE c text;
BEGIN
  UPDATE entry_points SET category = 'applications' WHERE category = 'app';
  UPDATE entry_points SET category = 'features' WHERE category = 'feature';
  FOR c IN SELECT conname FROM pg_constraint
           WHERE conrelid = 'entry_points'::regclass AND contype = 'c'
             AND conname LIKE '%category%'
  LOOP
    EXECUTE format('ALTER TABLE entry_points DROP CONSTRAINT %I', c);
  END LOOP;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conrelid = 'entry_points'::regclass AND conname = 'entry_points_category_check') THEN
    ALTER TABLE entry_points ADD CONSTRAINT entry_points_category_check
      CHECK (category IN ('applications','settings','features','admin-settings','user-settings'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conrelid = 'entry_points'::regclass AND conname = 'entry_points_type_check') THEN
    ALTER TABLE entry_points ADD CONSTRAINT entry_points_type_check
      CHECK (type IN ('iframe','embedded','mfe','link'));
  END IF;

  FOR c IN SELECT conname FROM pg_constraint
           WHERE conrelid = 'entry_point_groups'::regclass AND contype = 'c'
             AND conname LIKE '%category%'
  LOOP
    EXECUTE format('ALTER TABLE entry_point_groups DROP CONSTRAINT %I', c);
  END LOOP;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conrelid = 'entry_point_groups'::regclass AND conname = 'entry_point_groups_category_check') THEN
    ALTER TABLE entry_point_groups ADD CONSTRAINT entry_point_groups_category_check
      CHECK (category IN ('applications','settings','features'));
  END IF;

  -- Legacy FKs without ON DELETE CASCADE (module_versions, user_module_settings)
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'module_versions_module_key_fkey'
             AND NOT confdeltype = 'c') THEN
    ALTER TABLE module_versions
      DROP CONSTRAINT module_versions_module_key_fkey,
      ADD CONSTRAINT module_versions_module_key_fkey
        FOREIGN KEY (module_key) REFERENCES modules(key) ON DELETE CASCADE;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'user_module_settings_module_key_fkey'
             AND NOT confdeltype = 'c') THEN
    ALTER TABLE user_module_settings
      DROP CONSTRAINT user_module_settings_module_key_fkey,
      ADD CONSTRAINT user_module_settings_module_key_fkey
        FOREIGN KEY (module_key) REFERENCES modules(key) ON DELETE CASCADE;
  END IF;
END
$mig$;
