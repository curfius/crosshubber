package com.crosshubber.portal.config;

import org.springframework.ai.chat.memory.repository.jdbc.PostgresChatMemoryRepositoryDialect;

/**
 * Custom PostgreSQL dialect for Spring AI chat memory that uses the {@code ai_hub_chat_memory}
 * table instead of the default {@code SPRING_AI_CHAT_MEMORY}.
 *
 * <p>Spring AI's built-in dialect hardcodes the table name in SQL strings with no configuration
 * property to override it. This dialect extends {@link PostgresChatMemoryRepositoryDialect} and
 * rewrites all 4 SQL methods to reference our preferred table name.
 *
 * <p>Used by {@link ChatMemoryConfig} when building the {@link
 * org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository}.
 */
public class AiHubChatMemoryDialect extends PostgresChatMemoryRepositoryDialect {

  private static final String TABLE = "ai_hub_chat_memory";

  @Override
  public String getSelectMessagesSql() {
    return "SELECT content, type, \"timestamp\" FROM "
        + TABLE
        + " WHERE conversation_id = ? ORDER BY \"timestamp\"";
  }

  @Override
  public String getInsertMessageSql() {
    return "INSERT INTO "
        + TABLE
        + " (conversation_id, content, type, \"timestamp\") VALUES (?, ?, ?, ?)";
  }

  @Override
  public String getSelectConversationIdsSql() {
    return "SELECT DISTINCT conversation_id FROM " + TABLE;
  }

  @Override
  public String getDeleteMessagesSql() {
    return "DELETE FROM " + TABLE + " WHERE conversation_id = ?";
  }
}
