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
import com.crosshubber.portal.modules.aihub.dto.AddMessageRequest;
import com.crosshubber.portal.modules.aihub.dto.CreateConversationRequest;
import com.crosshubber.portal.modules.aihub.dto.FullConversationDto;
import com.crosshubber.portal.modules.aihub.dto.MessageDto;
import com.crosshubber.portal.security.PortalUser;

@RestController
public class AiHubConversationsController {

  private final AiHubConversationsService conversationsService;

  public AiHubConversationsController(AiHubConversationsService conversationsService) {
    this.conversationsService = conversationsService;
  }

  @GetMapping("/api/ai-hub/conversations")
  public Map<String, Object> list(@AuthenticationPrincipal PortalUser user) {
    return Map.of("conversations", conversationsService.listConversations(user.sub()));
  }

  @PostMapping("/api/ai-hub/conversations")
  public FullConversationDto create(
      @AuthenticationPrincipal PortalUser user,
      @RequestBody(required = false) CreateConversationRequest body) {
    String title =
        body != null && body.title() != null && !body.title().isBlank() ? body.title() : "New Chat";
    return conversationsService.createConversation(user.sub(), title);
  }

  @DeleteMapping("/api/ai-hub/conversations/{id}")
  public ResponseEntity<?> delete(
      @AuthenticationPrincipal PortalUser user, @PathVariable String id) {
    if (!conversationsService.deleteConversation(id, user.sub())) {
      return ResponseEntity.status(404).body(Map.of("error", "not found"));
    }
    return ResponseEntity.ok(Map.of("ok", true));
  }

  @GetMapping("/api/ai-hub/conversations/{id}/messages")
  public Map<String, Object> messages(
      @AuthenticationPrincipal PortalUser user, @PathVariable String id) {
    if (conversationsService.getConversation(id, user.sub()) == null) {
      throw new EntityNotFoundException("conversation not found");
    }
    return Map.of("messages", conversationsService.getMessages(id));
  }

  @PostMapping("/api/ai-hub/conversations/{id}/messages")
  public MessageDto addMessage(
      @AuthenticationPrincipal PortalUser user,
      @PathVariable String id,
      @RequestBody AddMessageRequest body) {
    if (conversationsService.getConversation(id, user.sub()) == null) {
      throw new EntityNotFoundException("conversation not found");
    }
    if (!"user".equals(body.role()) && !"assistant".equals(body.role())) {
      throw new IllegalArgumentException("invalid role");
    }
    if (body.content() == null || body.content().isEmpty()) {
      throw new IllegalArgumentException("content required");
    }
    return conversationsService.addMessage(id, body.role(), body.content());
  }
}
