-- Spring AI 2.0's JdbcChatMemoryRepository orders messages per conversation by a
-- sequence_id column instead of "timestamp" (TIMESTAMP precision is not sub-second on
-- all databases). The column existed briefly under Spring AI 1.0.0 (V17) and was
-- dropped as unused (V18); 2.0 requires it again. Backfill preserves the existing
-- per-conversation order, matching the 2.0 upgrade-notes recipe.
ALTER TABLE ai_hub_chat_memory ADD COLUMN IF NOT EXISTS sequence_id BIGINT NOT NULL DEFAULT 0;

WITH ordered AS (
  SELECT ctid, ROW_NUMBER() OVER (PARTITION BY conversation_id ORDER BY "timestamp") - 1 AS seq
  FROM ai_hub_chat_memory
)
UPDATE ai_hub_chat_memory t
SET sequence_id = o.seq
FROM ordered o
WHERE t.ctid = o.ctid;

CREATE INDEX IF NOT EXISTS ai_hub_chat_memory_conversation_id_sequence_id_idx
  ON ai_hub_chat_memory (conversation_id, sequence_id);
