package com.crosshubber.portal.modules.aihub.chat;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.aihub.dto.ChatStreamRequest;
import com.crosshubber.portal.security.PortalUser;

import reactor.core.publisher.Flux;

@RestController
public class AiHubChatController {

  private final AiHubChatService chatService;

  public AiHubChatController(AiHubChatService chatService) {
    this.chatService = chatService;
  }

  @PostMapping(value = "/api/ai-hub/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<Flux<String>> chat(
      @AuthenticationPrincipal PortalUser user, @RequestBody ChatStreamRequest body) {
    String message = body.message() == null ? "" : body.message().trim();
    if (message.isEmpty()) {
      return ResponseEntity.badRequest().build();
    }
    AiHubChatService.ChatStream stream =
        chatService.streamChat(user.sub(), body.conversationId(), message);
    return ResponseEntity.ok()
        .header("X-Conversation-Id", stream.conversationId())
        .body(stream.frames());
  }
}
