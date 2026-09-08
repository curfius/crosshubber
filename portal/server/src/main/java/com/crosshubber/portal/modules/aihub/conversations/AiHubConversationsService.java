package com.crosshubber.portal.modules.aihub.conversations;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.common.NodeDates;
import com.crosshubber.portal.modules.aihub.dto.ConversationDto;

/**
 * Manages AI Hub conversation metadata — creation, listing, deletion, ownership validation, and
 * message history retrieval.
 *
 * <p>Message persistence is handled by Spring AI's {@link ChatMemory} system (backed by the {@code
 * ai_hub_chat_memory} table via {@code JdbcChatMemoryRepository}). The {@code
 * MessageChatMemoryAdvisor} in {@code AiHubChatService} reads and writes messages automatically
 * around each LLM call. This service exposes a {@link #getMessages} method for the REST layer to
 * retrieve history for display in the UI.
 *
 * <p>On deletion, this service calls {@code chatMemory.clear(conversationId)} to remove the message
 * history from the ai_hub_chat_memory table before deleting the conversation metadata.
 */
@Service
public class AiHubConversationsService {

  private final AiHubConversationRepository conversationRepo;
  private final ChatMemory chatMemory;

  public AiHubConversationsService(
      AiHubConversationRepository conversationRepo, ChatMemory chatMemory) {
    this.conversationRepo = conversationRepo;
    this.chatMemory = chatMemory;
  }

  /** Returns all portal-origin conversations for the given user, most recently updated first. */
  @Transactional(readOnly = true)
  public List<ConversationDto> listConversations(String userId) {
    return conversationRepo.findByUserIdAndOriginOrderByUpdatedAtDesc(userId, "portal").stream()
        .map(this::toConversationDto)
        .toList();
  }

  /**
   * Creates a new conversation with a generated ID ({@code conv_} + UUID). The title is typically
   * the first 60 characters of the user's initial message.
   */
  @Transactional
  public ConversationDto createConversation(String userId, String title) {
    AiHubConversationEntity conversation = new AiHubConversationEntity();
    conversation.setId("conv_" + UUID.randomUUID());
    conversation.setUserId(userId);
    conversation.setOrigin("portal");
    conversation.setTitle(title);
    conversation = conversationRepo.saveAndFlush(conversation);
    return toConversationDto(conversation);
  }

  /**
   * Deletes a conversation and its message history. Clears the {@link ChatMemory} entry for this
   * conversation ID before deleting the metadata row, ensuring no orphaned messages remain in the
   * ai_hub_chat_memory table.
   *
   * @return {@code true} if the conversation was found and deleted, {@code false} if not found
   */
  @Transactional
  public boolean deleteConversation(String id, String userId) {
    AiHubConversationEntity conversation =
        conversationRepo.findByIdAndUserId(id, userId).orElse(null);
    if (conversation == null) {
      return false;
    }
    chatMemory.clear(id);
    conversationRepo.delete(conversation);
    return true;
  }

  /**
   * Validates that a conversation exists and belongs to the given user. Returns the entity for
   * further processing, or {@code null} if not found / not owned.
   */
  @Transactional(readOnly = true)
  public AiHubConversationEntity getConversation(String id, String userId) {
    return conversationRepo.findByIdAndUserId(id, userId).orElse(null);
  }

  /**
   * Returns the message history for a conversation from Spring AI's {@link ChatMemory}. Each message
   * includes its role ({@code user} / {@code assistant} / {@code system}) and text content. Used by
   * the REST layer to populate the chat UI when a user selects an existing conversation.
   */
  public List<Map<String, String>> getMessages(String conversationId) {
    return chatMemory.get(conversationId).stream()
        .map(
            m ->
                Map.of(
                    "role",
                    m.getMessageType().name().toLowerCase(),
                    "content",
                    m.getText() != null ? m.getText() : ""))
        .toList();
  }

  /** Converts an entity to a conversation DTO. */
  public ConversationDto toConversationDto(AiHubConversationEntity c) {
    return new ConversationDto(
        c.getId(),
        c.getUserId(),
        c.getOrigin(),
        c.getTitle(),
        NodeDates.format(c.getCreatedAt()),
        NodeDates.format(c.getUpdatedAt()));
  }
}
