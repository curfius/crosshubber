package com.crosshubber.portal.modules.aihub.conversations;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.aihub.common.EntityNotFoundException;
import com.crosshubber.portal.modules.aihub.dto.CreateConversationRequest;
import com.crosshubber.portal.modules.aihub.dto.FullConversationDto;
import com.crosshubber.portal.security.PortalUser;

/**
 * REST endpoints for AI Hub conversation management. These handle metadata only — message history
 * is managed by Spring AI's {@code ChatMemory} system and streamed via {@code AiHubChatController}.
 */
@RestController
public class AiHubConversationsController {

  private final AiHubConversationsService conversationsService;

  public AiHubConversationsController(AiHubConversationsService conversationsService) {
    this.conversationsService = conversationsService;
  }

  /** Lists all portal conversations for the authenticated user, most recently updated first. */
  @GetMapping("/api/ai-hub/conversations")
  public Map<String, Object> list(@AuthenticationPrincipal PortalUser user) {
    return Map.of("conversations", conversationsService.listConversations(user.sub()));
  }

  /** Creates a new conversation. Title defaults to "New Chat" if not provided. */
  @PostMapping("/api/ai-hub/conversations")
  public FullConversationDto create(
      @AuthenticationPrincipal PortalUser user,
      @RequestBody(required = false) CreateConversationRequest body) {
    String title =
        body != null && body.title() != null && !body.title().isBlank() ? body.title() : "New Chat";
    return conversationsService.createConversation(user.sub(), title);
  }

  /**
   * Deletes a conversation and its message history. Clears the SPRING_AI_CHAT_MEMORY entry via
   * {@code ChatMemory.clear()} before removing the metadata.
   */
  @DeleteMapping("/api/ai-hub/conversations/{id}")
  public ResponseEntity<?> delete(
      @AuthenticationPrincipal PortalUser user, @PathVariable String id) {
    if (!conversationsService.deleteConversation(id, user.sub())) {
      return ResponseEntity.status(404).body(Map.of("error", "not found"));
    }
    return ResponseEntity.ok(Map.of("ok", true));
  }

  /** Returns a single conversation's metadata. */
  @GetMapping("/api/ai-hub/conversations/{id}")
  public FullConversationDto get(
      @AuthenticationPrincipal PortalUser user, @PathVariable String id) {
    var conversation = conversationsService.getConversation(id, user.sub());
    if (conversation == null) {
      throw new EntityNotFoundException("conversation not found");
    }
    return conversationsService.toFullConversationDto(conversation);
  }
}
