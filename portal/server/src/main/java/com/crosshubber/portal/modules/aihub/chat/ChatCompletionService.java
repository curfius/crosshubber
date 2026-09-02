package com.crosshubber.portal.modules.aihub.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.modules.aihub.providers.AiHubProvidersService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared LLM upstream access — mirrors {@code
 * portal/src/modules/ai-hub/chat-completion.service.ts}: builds the provider-specific request
 * (Anthropic Messages API vs OpenAI-compatible chat completions) and streams normalized content
 * deltas.
 */
@Service
public class ChatCompletionService {

  private static final Duration COMPLETION_TIMEOUT = Duration.ofSeconds(60);

  private final AiHubProvidersService providersService;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public ChatCompletionService(AiHubProvidersService providersService, ObjectMapper objectMapper) {
    this.providersService = providersService;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  }

  public record ChatMessage(String role, String content) {}

  public record CompletionRequest(
      String providerId,
      String model,
      String baseURL,
      String apiKey,
      List<ChatMessage> messages,
      String systemPrompt,
      Double temperature,
      Integer maxTokens) {}

  /** Thrown when the upstream fails before the stream starts. */
  public static class UpstreamError extends RuntimeException {

    private final int status;

    public UpstreamError(String message, int status) {
      super(message);
      this.status = status;
    }

    public int getStatus() {
      return status;
    }
  }

  /** Built upstream request (anthropic vs openai-compatible). */
  public record UpstreamRequest(
      String url, Map<String, String> headers, Map<String, Object> body) {}

  public UpstreamRequest buildUpstreamRequest(CompletionRequest req, boolean stream) {
    List<Map<String, String>> messages = new ArrayList<>();
    if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
      Map<String, String> system = new LinkedHashMap<>();
      system.put("role", "system");
      system.put("content", req.systemPrompt());
      messages.add(system);
    }
    for (ChatMessage m : req.messages()) {
      Map<String, String> message = new LinkedHashMap<>();
      message.put("role", m.role());
      message.put("content", m.content());
      messages.add(message);
    }

    if ("anthropic".equals(req.providerId())) {
      String systemContent = null;
      List<Map<String, String>> nonSystem = new ArrayList<>();
      for (Map<String, String> m : messages) {
        if ("system".equals(m.get("role"))) {
          systemContent = m.get("content");
        } else {
          nonSystem.add(m);
        }
      }
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("model", req.model());
      body.put("messages", nonSystem);
      body.put("stream", stream);
      body.put("max_tokens", req.maxTokens() != null ? req.maxTokens() : 4096);
      if (req.temperature() != null) {
        body.put("temperature", req.temperature());
      }
      if (systemContent != null) {
        body.put("system", systemContent);
      }
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("Content-Type", "application/json");
      headers.put("x-api-key", req.apiKey());
      headers.put("anthropic-version", "2023-06-01");
      return new UpstreamRequest(
          req.baseURL().replaceAll("/+$", "") + "/v1/messages", headers, body);
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", req.model());
    body.put("messages", messages);
    body.put("stream", stream);
    if (req.temperature() != null) {
      body.put("temperature", req.temperature());
    }
    if (req.maxTokens() != null) {
      body.put("max_tokens", req.maxTokens());
    }
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("Authorization", "Bearer " + req.apiKey());
    return new UpstreamRequest(
        req.baseURL().replaceAll("/+$", "") + "/chat/completions", headers, body);
  }

  /** Extracts the content delta from a single upstream SSE data frame. */
  public String extractDelta(String providerId, JsonNode parsed) {
    if ("anthropic".equals(providerId)) {
      if ("content_block_delta".equals(parsed.path("type").asText())) {
        return parsed.path("delta").path("text").asText("");
      }
      return "";
    }
    return parsed.path("choices").path(0).path("delta").path("content").asText("");
  }

  /**
   * Sends the upstream request and returns the response — callers check {@code
   * response.statusCode()} before streaming the body (mirrors node's pre-stream error handling).
   */
  public HttpResponse<InputStream> openStream(CompletionRequest req)
      throws IOException, InterruptedException {
    UpstreamRequest upstream = buildUpstreamRequest(req, true);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(upstream.url()))
            .timeout(COMPLETION_TIMEOUT)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(upstream.body()), StandardCharsets.UTF_8));
    upstream.headers().forEach(builder::header);
    return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
  }

  /**
   * Pumps the upstream SSE body, invoking {@code onContent} per normalized delta and finally
   * signalling end-of-stream. Never throws mid-stream — failures just end the stream (mirrors
   * streamCompletion).
   */
  public void pumpStream(
      String providerId, HttpResponse<InputStream> response, ContentConsumer onContent) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("data: ")) {
          continue;
        }
        String data = trimmed.substring(6);
        if ("[DONE]".equals(data)) {
          continue;
        }
        try {
          JsonNode parsed = objectMapper.readTree(data);
          String content = extractDelta(providerId, parsed);
          if (!content.isEmpty()) {
            onContent.accept(content);
          }
        } catch (Exception parseFailure) {
          // skip unparseable lines
        }
      }
    } catch (Exception streamFailure) {
      // mid-stream failures simply end the stream
    }
  }

  @FunctionalInterface
  public interface ContentConsumer {
    void accept(String content) throws IOException;
  }

  /** Non-streaming completion — aggregates the full reply (channel pipeline). */
  public String complete(CompletionRequest req) throws IOException, InterruptedException {
    HttpResponse<InputStream> response = openStream(req);
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String errText = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
      throw new UpstreamError(
          "provider API returned " + response.statusCode() + ": " + errText, response.statusCode());
    }
    StringBuilder full = new StringBuilder();
    pumpStream(req.providerId(), response, full::append);
    return full.toString();
  }

  /**
   * Resolves the API key for a provider: explicit tokenId, else the first enabled token. Returns
   * null when no usable key exists.
   */
  public AiHubProvidersService.ResolvedKey resolveApiKey(String providerId, String tokenId) {
    return providersService.resolveApiKey(providerId, tokenId);
  }
}
