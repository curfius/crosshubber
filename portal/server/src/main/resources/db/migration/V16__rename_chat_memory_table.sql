-- Rename the Spring AI chat memory table to match our project naming convention.
-- The custom AiHubChatMemoryDialect references this table name in all SQL queries.
ALTER TABLE IF EXISTS SPRING_AI_CHAT_MEMORY RENAME TO ai_hub_chat_memory;
ALTER INDEX IF EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
  RENAME TO ai_hub_chat_memory_conversation_id_timestamp_idx;
