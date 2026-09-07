-- Drop ai-hub channels module entirely (telegram/whatsapp integrations removed).

-- Drop FK constraint that references ai_hub_channels before dropping the table.
ALTER TABLE ai_hub_conversations DROP CONSTRAINT IF EXISTS ai_hub_conversations_channel_id_fkey;

DROP TABLE IF EXISTS ai_hub_channel_inbound;
DROP TABLE IF EXISTS ai_hub_channels;
DROP INDEX IF EXISTS idx_ai_hub_conv_channel_chat;

ALTER TABLE ai_hub_conversations DROP COLUMN IF EXISTS channel_id;
ALTER TABLE ai_hub_conversations DROP COLUMN IF EXISTS external_chat_id;
