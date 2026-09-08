package com.crosshubber.portal.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Configures Spring AI's conversation memory backed by PostgreSQL via JDBC.
 *
 * <p>The memory system has two layers:
 *
 * <ul>
 *   <li>{@link ChatMemoryRepository} — storage layer. {@link JdbcChatMemoryRepository} persists
 *       messages to the {@code ai_hub_chat_memory} table (created by Flyway migration V13, renamed
 *       by V16). The dialect is {@link AiHubChatMemoryDialect} (custom PostgreSQL dialect).
 *   <li>{@link ChatMemory} — strategy layer. {@link MessageWindowChatMemory} keeps a sliding window
 *       of the last {@value #MAX_MESSAGES} messages per conversation, evicting older ones. System
 *       messages are always preserved.
 * </ul>
 *
 * <p>These beans are consumed by:
 *
 * <ul>
 *   <li>{@code MessageChatMemoryAdvisor} in {@code AiHubChatService} — reads/writes memory around
 *       each LLM call automatically.
 *   <li>{@code AiHubConversationsService} — calls {@code chatMemory.clear(id)} when a conversation
 *       is deleted.
 * </ul>
 *
 * <p>Auto-configuration for Spring AI models is excluded in {@code application.yml} because the AI
 * Hub supports multiple providers/tokens chosen at runtime. This config class provides only the
 * memory beans — model construction happens in {@code AiHubChatService}.
 */
@Configuration
public class ChatMemoryConfig {

  /** Maximum number of messages retained per conversation in the sliding window. */
  private static final int MAX_MESSAGES = 20;

  /**
   * JDBC-backed repository for chat message persistence. Uses Spring's {@link JdbcTemplate} and a
   * custom {@link AiHubChatMemoryDialect} that targets the {@code ai_hub_chat_memory} table.
   */
  @Bean
  public ChatMemoryRepository chatMemoryRepository(JdbcTemplate jdbcTemplate) {
    return JdbcChatMemoryRepository.builder()
        .jdbcTemplate(jdbcTemplate)
        .dialect(new AiHubChatMemoryDialect())
        .build();
  }

  /**
   * Windowed chat memory that retains the last {@value #MAX_MESSAGES} messages per conversation.
   * The advisor chain in {@code AiHubChatService} calls {@code chatMemory.get(conversationId)}
   * before each LLM call (to inject history) and {@code chatMemory.add(conversationId, messages)}
   * after each call (to persist the exchange).
   */
  @Bean
  public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
    return MessageWindowChatMemory.builder()
        .chatMemoryRepository(chatMemoryRepository)
        .maxMessages(MAX_MESSAGES)
        .build();
  }
}
