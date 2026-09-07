package com.crosshubber.portal.modules.aihub.conversations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.common.NodeDates;
import com.crosshubber.portal.modules.aihub.ChatMessage;
import com.crosshubber.portal.modules.aihub.dto.ConversationDto;
import com.crosshubber.portal.modules.aihub.dto.FullConversationDto;
import com.crosshubber.portal.modules.aihub.dto.MessageDto;

@Service
public class AiHubConversationsService {

  private final AiHubConversationRepository conversationRepo;
  private final AiHubMessageRepository messageRepo;

  public AiHubConversationsService(
      AiHubConversationRepository conversationRepo, AiHubMessageRepository messageRepo) {
    this.conversationRepo = conversationRepo;
    this.messageRepo = messageRepo;
  }

  @Transactional(readOnly = true)
  public List<ConversationDto> listConversations(String userId) {
    return conversationRepo.findByUserIdAndOriginOrderByUpdatedAtDesc(userId, "portal").stream()
        .map(this::toConversationDto)
        .toList();
  }

  @Transactional
  public FullConversationDto createConversation(String userId, String title) {
    AiHubConversationEntity conversation = new AiHubConversationEntity();
    conversation.setId("conv_" + UUID.randomUUID());
    conversation.setUserId(userId);
    conversation.setOrigin("portal");
    conversation.setTitle(title);
    conversation = conversationRepo.saveAndFlush(conversation);
    return toFullConversationDto(conversation);
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
  public List<MessageDto> getMessages(String conversationId) {
    return messageRepo.findByConversationIdOrderByIdAsc(conversationId).stream()
        .map(this::toMessageDto)
        .toList();
  }

  @Transactional
  public MessageDto addMessage(String conversationId, String role, String content) {
    return addMessage(conversationId, role, content, null, null);
  }

  @Transactional
  public MessageDto addMessage(
      String conversationId, String role, String content, String providerId, String model) {
    AiHubMessageEntity message = new AiHubMessageEntity();
    message.setConversationId(conversationId);
    message.setRole(role);
    message.setContent(content);
    message.setProviderId(providerId);
    message.setModel(model);
    message = messageRepo.saveAndFlush(message);
    conversationRepo
        .findById(conversationId)
        .ifPresent(
            conversation -> {
              conversation.setUpdatedAt(Instant.now());
              conversationRepo.save(conversation);
            });
    return toMessageDto(message);
  }

  @Transactional(readOnly = true)
  public List<ChatMessage> getRecentMessages(String conversationId, int limit) {
    List<AiHubMessageEntity> latest =
        messageRepo.findByConversationIdOrderByIdDesc(conversationId, PageRequest.of(0, limit));
    return latest.reversed().stream()
        .map(m -> new ChatMessage(m.getRole(), m.getContent()))
        .toList();
  }

  private ConversationDto toConversationDto(AiHubConversationEntity c) {
    return new ConversationDto(
        c.getId(),
        c.getUserId(),
        c.getTitle(),
        NodeDates.format(c.getCreatedAt()),
        NodeDates.format(c.getUpdatedAt()));
  }

  private FullConversationDto toFullConversationDto(AiHubConversationEntity c) {
    return new FullConversationDto(
        c.getId(),
        c.getUserId(),
        c.getOrigin(),
        c.getTitle(),
        NodeDates.format(c.getCreatedAt()),
        NodeDates.format(c.getUpdatedAt()));
  }

  private MessageDto toMessageDto(AiHubMessageEntity m) {
    return new MessageDto(
        m.getId(),
        m.getConversationId(),
        m.getRole(),
        m.getContent(),
        m.getProviderId(),
        m.getModel(),
        NodeDates.format(m.getCreatedAt()));
  }
}
