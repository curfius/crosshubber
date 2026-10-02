package com.crosshubber.solutions.agent;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.crosshubber.solutions.settings.ModuleSettingsService;

/**
 * Module-owned LLM access (OpenAI-compatible chat completions over the shared RestClient) —
 * credentials resolve from module settings (DB, encrypted) with compose-env fallback; never from
 * the portal. Unconfigured = the caller gets a fail-closed error, no portal fallback.
 */
@Service
public class LlmClient {

  public record ChatMessage(String role, String content) {}

  public record ChatRequest(String model, List<ChatMessage> messages, Integer max_tokens) {}

  public record LlmResult(String content) {}

  private final RestClient.Builder restClientBuilder;
  private final ModuleSettingsService settings;

  public LlmClient(RestClient.Builder restClientBuilder, ModuleSettingsService settings) {
    this.restClientBuilder = restClientBuilder;
    this.settings = settings;
  }

  public boolean isConfigured() {
    return settings.effectiveLlm().isConfigured();
  }

  /**
   * Runs a minimal chat completion.
   *
   * @throws IllegalStateException when credentials are not configured
   * @throws Exception on transport/HTTP failure
   */
  @SuppressWarnings("unchecked")
  public String complete(String system, String user) throws Exception {
    ModuleSettingsService.EffectiveLlm llm = settings.effectiveLlm();
    if (!llm.isConfigured()) {
      throw new IllegalStateException(
          "llm is not configured (module settings or SOLUTIONS_LLM_* env)");
    }
    String url = llm.baseUrl().replaceAll("/+$", "") + "/chat/completions";
    Map<String, Object> response =
        restClientBuilder
            .build()
            .post()
            .uri(url)
            .header("Authorization", "Bearer " + llm.apiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                new ChatRequest(
                    llm.model(),
                    List.of(new ChatMessage("system", system), new ChatMessage("user", user)),
                    1024))
            .retrieve()
            .body(Map.class);
    if (response == null) {
      throw new IllegalStateException("empty llm response");
    }
    List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
    if (choices == null || choices.isEmpty()) {
      throw new IllegalStateException("llm response has no choices");
    }
    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
    Object content = message == null ? null : message.get("content");
    return content == null ? "" : content.toString();
  }
}
