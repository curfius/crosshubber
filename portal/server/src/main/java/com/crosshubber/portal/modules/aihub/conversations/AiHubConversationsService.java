package com.crosshubber.portal.modules.aihub.conversations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Chat conversations (portal + channel origins) — mirrors {@code
 * portal/src/modules/ai-hub/conversations.repository.ts}.
 */
@Service
public class AiHubConversationsService {

  private final AiHubConversationRepository conversationRepo;
  private final AiHubMessageRepository messageRepo;

  public AiHubConversationsService(
      AiHubConversationRepository conversationRepo, AiHubMessageRepository messageRepo) {
    this.conversationRepo = conversationRepo;
    this.messageRepo = messageRepo;
  }

  // ── DTOs (snake_case keys, mirroring the Node row shapes) ────────────

  public static Map<String, Object> conversationDto(AiHubConversationEntity c) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", c.getId());
    out.put("user_id", c.getUserId());
    out.put("title", c.getTitle());
    out.put("created_at", c.getCreatedAt().toString());
    out.put("updated_at", c.getUpdatedAt().toString());
    return out;
  }

  public static Map<String, Object> messageDto(AiHubMessageEntity m) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", m.getId());
    out.put("conversation_id", m.getConversationId());
    out.put("role", m.getRole());
    out.put("content", m.getContent());
    out.put("provider_id", m.getProviderId());
    out.put("model", m.getModel());
    out.put("created_at", m.getCreatedAt().toString());
    return out;
  }

  // ── Portal conversations ─────────────────────────────────────────────

  @Transactional(readOnly = true)
  public List<Map<String, Object>> listConversations(String userId) {
    return conversationRepo.findByUserIdAndOriginOrderByUpdatedAtDesc(userId, "portal").stream()
        .map(AiHubConversationsService::conversationDto)
        .toList();
  }

  @Transactional
  public Map<String, Object> createConversation(String userId, String title) {
    AiHubConversationEntity conversation = new AiHubConversationEntity();
    conversation.setId(newConversationId());
    conversation.setUserId(userId);
    conversation.setOrigin("portal");
    conversation.setTitle(title);
    conversation = conversationRepo.saveAndFlush(conversation);
    return conversationDto(conversation);
  }

  @Transactional
  public boolean deleteConversation(String id, String userId) {
    AiHubConversationEntity conversation =
        conversationRepo.findByIdAndUserId(id, userId).orElse(null);
    if (conversation == null) {
      return false;
    }
    messageRepo.deleteByConversationId(id);
    conversationRepo.delete(conversation);
    return true;
  }

  @Transactional(readOnly = true)
  public AiHubConversationEntity getConversation(String id, String userId) {
    return conversationRepo.findByIdAndUserId(id, userId).orElse(null);
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> getMessages(String conversationId) {
    return messageRepo.findByConversationIdOrderByIdAsc(conversationId).stream()
        .map(AiHubConversationsService::messageDto)
        .toList();
  }

  @Transactional
  public Map<String, Object> addMessage(String conversationId, String role, String content) {
    return addMessage(conversationId, role, content, null, null);
  }

  @Transactional
  public Map<String, Object> addMessage(
      String conversationId, String role, String content, String providerId, String model) {
    AiHubMessageEntity message = new AiHubMessageEntity();
    message.setConversationId(conversationId);
    message.setRole(role);
    message.setContent(content);
    message.setProviderId(providerId);
    message.setModel(model);
    message = messageRepo.saveAndFlush(message);
    conversationRepo.findById(conversationId).ifPresent(conversationRepo::save);
    return messageDto(message);
  }

  // ── Channel conversations (origin 'channel') ─────────────────────────

  /** Finds or creates the conversation bound to a (channel, external chat) pair. */
  @Transactional
  public String findOrCreateChannelConversation(
      String channelId, String externalChatId, String title) {
    AiHubConversationEntity existing =
        conversationRepo.findByChannelIdAndExternalChatId(channelId, externalChatId).orElse(null);
    if (existing != null) {
      return existing.getId();
    }
    AiHubConversationEntity conversation = new AiHubConversationEntity();
    conversation.setId(newConversationId());
    conversation.setUserId(null);
    conversation.setChannelId(channelId);
    conversation.setExternalChatId(externalChatId);
    conversation.setOrigin("channel");
    conversation.setTitle(title);
    conversationRepo.saveAndFlush(conversation);
    return conversation.getId();
  }

  /** Last {@code limit} messages of a conversation, oldest first. */
  @Transactional(readOnly = true)
  public List<ChatMessage> getRecentMessages(String conversationId, int limit) {
    List<AiHubMessageEntity> latest =
        messageRepo.findByConversationIdOrderByIdDesc(conversationId, PageRequest.of(0, limit));
    return latest.reversed().stream()
        .map(m -> new ChatMessage(m.getRole(), m.getContent()))
        .toList();
  }

  public record ChatMessage(String role, String content) {}

  private static String newConversationId() {
    return "conv_"
        + System.currentTimeMillis()
        + "_"
        + Long.toString(Double.doubleToLongBits(Math.random()), 36).substring(0, 6);
  }

  /** 404 helper for controllers. */
  public static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
  }
}
