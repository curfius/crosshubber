-- Add the sequence_id column that Spring AI 1.0.0 JdbcChatMemoryRepository requires
-- for ordering and inserting messages. Our V13 migration was based on an earlier schema
-- version that omitted this column.
ALTER TABLE ai_hub_chat_memory ADD COLUMN IF NOT EXISTS sequence_id BIGINT NOT NULL DEFAULT 0;
