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

import com.crosshubber.portal.security.PortalUser;

/**
 * Conversation routes — mirrors conversations.routes.ts. Also serves the deprecated {@code
 * /api/chat/conversations...} shim (Deprecation headers).
 */
@RestController
public class AiHubConversationsController {

  private final AiHubConversationsService conversationsService;

  public AiHubConversationsController(AiHubConversationsService conversationsService) {
    this.conversationsService = conversationsService;
  }

  @GetMapping({"/api/ai-hub/conversations", "/api/chat/conversations"})
  public Map<String, Object> list(@AuthenticationPrincipal PortalUser user) {
    return Map.of("conversations", conversationsService.listConversations(user.sub()));
  }

  @PostMapping({"/api/ai-hub/conversations", "/api/chat/conversations"})
  public Map<String, Object> create(
      @AuthenticationPrincipal PortalUser user,
      @RequestBody(required = false) Map<String, Object> body) {
    String title =
        body != null && body.get("title") instanceof String s && !s.isBlank() ? s : "New Chat";
    return Map.of("conversation", conversationsService.createConversation(user.sub(), title));
  }

  @DeleteMapping({"/api/ai-hub/conversations/{id}", "/api/chat/conversations/{id}"})
  public ResponseEntity<?> delete(
      @AuthenticationPrincipal PortalUser user, @PathVariable String id) {
    if (!conversationsService.deleteConversation(id, user.sub())) {
      return ResponseEntity.status(404).body(Map.of("error", "not found"));
    }
    return ResponseEntity.ok(Map.of("ok", true));
  }

  @GetMapping({"/api/ai-hub/conversations/{id}/messages", "/api/chat/conversations/{id}/messages"})
  public ResponseEntity<?> messages(
      @AuthenticationPrincipal PortalUser user, @PathVariable String id) {
    if (conversationsService.getConversation(id, user.sub()) == null) {
      return ResponseEntity.status(404).body(Map.of("error", "not found"));
    }
    return ResponseEntity.ok(Map.of("messages", conversationsService.getMessages(id)));
  }

  @PostMapping({"/api/ai-hub/conversations/{id}/messages", "/api/chat/conversations/{id}/messages"})
  public ResponseEntity<?> addMessage(
      @AuthenticationPrincipal PortalUser user,
      @PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body) {
    if (conversationsService.getConversation(id, user.sub()) == null) {
      return ResponseEntity.status(404).body(Map.of("error", "not found"));
    }
    String role = body != null && body.get("role") instanceof String r ? r : null;
    String content = body != null && body.get("content") instanceof String c ? c : null;
    if (!"user".equals(role) && !"assistant".equals(role)) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid role"));
    }
    if (content == null || content.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("error", "content required"));
    }
    return ResponseEntity.ok(Map.of("message", conversationsService.addMessage(id, role, content)));
  }
}
