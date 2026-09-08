-- Drop the legacy message table. Message history is now managed entirely by
-- Spring AI's ChatMemory system (SPRING_AI_CHAT_MEMORY table).
DROP TABLE IF EXISTS ai_hub_messages;
DROP INDEX IF EXISTS idx_ai_hub_messages_conv;
