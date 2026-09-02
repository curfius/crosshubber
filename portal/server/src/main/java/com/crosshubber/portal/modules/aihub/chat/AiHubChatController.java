package com.crosshubber.portal.modules.aihub.chat;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.aihub.providers.AiHubProvidersService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * POST /chat — SSE-streamed LLM completion. Mirrors {@code
 * portal/src/modules/ai-hub/chat.routes.ts}: 60 s hard cap, upstream pre-flight errors become 502
 * JSON, {@code data: [DONE]} terminates.
 */
@RestController
public class AiHubChatController {

  private final ChatCompletionService chatService;
  private final ObjectMapper objectMapper;

  public AiHubChatController(ChatCompletionService chatService, ObjectMapper objectMapper) {
    this.chatService = chatService;
    this.objectMapper = objectMapper;
  }

  @PostMapping({"/api/ai-hub/chat", "/api/llm/chat"})
  public void chat(
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body,
      HttpServletRequest request,
      HttpServletResponse response)
      throws Exception {
    if (body == null) {
      writeJson(response, 400, Map.of("error", "invalid request body"));
      return;
    }
    String providerId = string(body.get("providerId"));
    String model = string(body.get("model"));
    if (providerId == null || model == null || !(body.get("messages") instanceof List<?>)) {
      writeJson(response, 400, Map.of("error", "providerId, model, and messages are required"));
      return;
    }
    String tokenId = string(body.get("tokenId"));
    AiHubProvidersService.ResolvedKey resolved = chatService.resolveApiKey(providerId, tokenId);
    if (resolved == null) {
      writeJson(response, 400, Map.of("error", "no API key found for this provider/token"));
      return;
    }
    List<ChatCompletionService.ChatMessage> messages = new ArrayList<>();
    for (Object item : (List<?>) body.get("messages")) {
      if (item instanceof Map<?, ?> m) {
        messages.add(
            new ChatCompletionService.ChatMessage(
                String.valueOf(m.get("role")), String.valueOf(m.get("content"))));
      }
    }
    ChatCompletionService.CompletionRequest completionRequest =
        new ChatCompletionService.CompletionRequest(
            providerId,
            model,
            resolved.baseURL(),
            resolved.apiKey(),
            messages,
            string(body.get("systemPrompt")),
            body.get("temperature") instanceof Number n ? n.doubleValue() : null,
            body.get("maxTokens") instanceof Number n ? n.intValue() : null);

    HttpResponse<InputStream> upstream;
    try {
      upstream = chatService.openStream(completionRequest);
    } catch (Exception e) {
      writeJson(response, 502, Map.of("error", "failed to reach provider: " + e.getMessage()));
      return;
    }
    if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
      String errText = new String(upstream.body().readAllBytes(), StandardCharsets.UTF_8);
      writeJson(
          response,
          502,
          Map.of("error", "provider API returned " + upstream.statusCode() + ": " + errText));
      return;
    }

    response.setStatus(200);
    response.setContentType("text/event-stream");
    response.setHeader("Cache-Control", "no-cache");
    response.setHeader("Connection", "keep-alive");
    response.setHeader("X-Accel-Buffering", "no");
    OutputStream out = response.getOutputStream();
    chatService.pumpStream(
        providerId,
        upstream,
        content -> {
          String frame =
              "data: " + objectMapper.writeValueAsString(Map.of("content", content)) + "\n\n";
          out.write(frame.getBytes(StandardCharsets.UTF_8));
          out.flush();
        });
    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private void writeJson(HttpServletResponse response, int status, Map<String, ?> body)
      throws Exception {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(objectMapper.writeValueAsString(body));
  }

  private static String string(Object value) {
    return value instanceof String s && !s.isBlank() ? s : null;
  }
}
