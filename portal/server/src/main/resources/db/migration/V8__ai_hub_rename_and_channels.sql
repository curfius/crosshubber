DO $ai_hub_rename$
BEGIN
  IF to_regclass('llm_providers') IS NOT NULL THEN
    ALTER TABLE llm_providers RENAME TO ai_hub_providers;
  END IF;
  IF to_regclass('llm_tokens') IS NOT NULL THEN
    ALTER TABLE llm_tokens RENAME TO ai_hub_tokens;
  END IF;
  IF to_regclass('chat_conversations') IS NOT NULL THEN
    ALTER TABLE chat_conversations RENAME TO ai_hub_conversations;
  END IF;
  IF to_regclass('chat_messages') IS NOT NULL THEN
    ALTER TABLE chat_messages RENAME TO ai_hub_messages;
  END IF;
END
$ai_hub_rename$;

DO $ai_hub_index$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_chat_messages_conv') THEN
    ALTER INDEX idx_chat_messages_conv RENAME TO idx_ai_hub_messages_conv;
  END IF;
END
$ai_hub_index$;

CREATE TABLE IF NOT EXISTS ai_hub_channels (
  id                    TEXT PRIMARY KEY,
  type                  TEXT NOT NULL CONSTRAINT ai_hub_channels_type_check CHECK (type IN ('telegram','whatsapp')),
  name                  TEXT NOT NULL,
  enabled               BOOLEAN NOT NULL DEFAULT false,
  credentials_encrypted TEXT,
  credentials_meta      JSONB NOT NULL DEFAULT '{}',
  config                JSONB NOT NULL DEFAULT '{}',
  status                JSONB NOT NULL DEFAULT '{}',
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE ai_hub_conversations ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE ai_hub_conversations
  ADD COLUMN IF NOT EXISTS channel_id TEXT REFERENCES ai_hub_channels(id) ON DELETE SET NULL;
ALTER TABLE ai_hub_conversations
  ADD COLUMN IF NOT EXISTS external_chat_id TEXT;
ALTER TABLE ai_hub_conversations
  ADD COLUMN IF NOT EXISTS origin TEXT NOT NULL DEFAULT 'portal';

CREATE UNIQUE INDEX IF NOT EXISTS idx_ai_hub_conv_channel_chat
  ON ai_hub_conversations (channel_id, external_chat_id)
  WHERE channel_id IS NOT NULL AND external_chat_id IS NOT NULL;

ALTER TABLE ai_hub_messages
  ADD COLUMN IF NOT EXISTS provider_id TEXT;
ALTER TABLE ai_hub_messages
  ADD COLUMN IF NOT EXISTS model TEXT;

CREATE TABLE IF NOT EXISTS ai_hub_channel_inbound (
  channel_id          TEXT NOT NULL REFERENCES ai_hub_channels(id) ON DELETE CASCADE,
  external_message_id TEXT NOT NULL,
  received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (channel_id, external_message_id)
);

-- Module settings blob moves with the module (runs before the reconciler
-- creates the ai-hub row — see server.ts bootstrap ordering).
UPDATE module_settings SET module_key = 'ai-hub'
 WHERE module_key = 'ai-assistant'
   AND NOT EXISTS (SELECT 1 FROM module_settings WHERE module_key = 'ai-hub');

-- Entry-point reference rewrite (retired builtins -> ai-hub). Style follows
-- migration 2: guarded text replaces over stored epId strings.
UPDATE navigation_pinned_apps SET ref = 'ai-hub:main'      WHERE ref = 'llm-providers:main';
UPDATE navigation_pinned_apps SET ref = 'ai-hub:main'      WHERE ref = 'ai-assistant:main';
UPDATE navigation_pinned_apps SET ref = 'ai-hub:settings'  WHERE ref = 'ai-assistant:settings';
UPDATE navigation_pinned_apps SET ref = 'ai-hub:quick-chat' WHERE ref = 'ai-assistant:quick-chat';

DO $ai_hub_favorites$
BEGIN
  IF to_regclass('favorites') IS NOT NULL THEN
    UPDATE favorites SET item_key = 'ai-hub'
     WHERE item_type = 'app' AND item_key IN ('llm-providers', 'ai-assistant');
  END IF;
END
$ai_hub_favorites$;

UPDATE workspaces
   SET groups = replace(replace(replace(replace(groups::text,
         '"llm-providers:main"', '"ai-hub:main"'),
         '"ai-assistant:main"', '"ai-hub:main"'),
         '"ai-assistant:settings"', '"ai-hub:settings"'),
         '"ai-assistant:quick-chat"', '"ai-hub:quick-chat"')::jsonb
 WHERE groups::text LIKE '%"llm-providers:%"'
    OR groups::text LIKE '%"ai-assistant:%"';

UPDATE navigation_user_settings
   SET settings = replace(replace(replace(replace(settings::text,
         '"llm-providers:main"', '"ai-hub:main"'),
         '"ai-assistant:main"', '"ai-hub:main"'),
         '"ai-assistant:settings"', '"ai-hub:settings"'),
         '"ai-assistant:quick-chat"', '"ai-hub:quick-chat"')::jsonb
 WHERE settings::text LIKE '%"llm-providers:%"'
    OR settings::text LIKE '%"ai-assistant:%"';

UPDATE navigation_layout
   SET settings = replace(replace(replace(replace(settings::text,
         '"llm-providers:main"', '"ai-hub:main"'),
         '"ai-assistant:main"', '"ai-hub:main"'),
         '"ai-assistant:settings"', '"ai-hub:settings"'),
         '"ai-assistant:quick-chat"', '"ai-hub:quick-chat"')::jsonb
 WHERE settings::text LIKE '%"llm-providers:%"'
    OR settings::text LIKE '%"ai-assistant:%"';
