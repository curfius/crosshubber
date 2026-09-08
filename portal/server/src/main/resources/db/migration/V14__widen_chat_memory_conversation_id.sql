-- Widen conversation_id to accommodate conv_ prefixed IDs (40 chars).
-- Spring AI's reference schema uses VARCHAR(36) for bare UUIDs, but our
-- AiHubConversationsService generates IDs as "conv_" + UUID (40 chars).
ALTER TABLE SPRING_AI_CHAT_MEMORY ALTER COLUMN conversation_id TYPE VARCHAR(64);
