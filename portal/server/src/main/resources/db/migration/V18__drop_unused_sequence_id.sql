-- Spring AI 1.0.0 does not use sequence_id. The column was added by mistake and is unused.
ALTER TABLE ai_hub_chat_memory DROP COLUMN IF EXISTS sequence_id;
