DROP INDEX IF EXISTS idx_ai_hub_conv_channel_chat;
CREATE UNIQUE INDEX IF NOT EXISTS idx_ai_hub_conv_channel_chat
  ON ai_hub_conversations (channel_id, external_chat_id);
