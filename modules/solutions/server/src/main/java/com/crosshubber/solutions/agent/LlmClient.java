package com.crosshubber.solutions.agent;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.crosshubber.solutions.config.SolutionsProperties;

/**
 * Module-owned LLM access (OpenAI-compatible chat completions over the shared RestClient) —
 * credentials come from module config (compose env), never from the portal. Unconfigured = the
 * caller gets a fail-closed error, no portal fallback.
 */
@Service
public class LlmClient {

  public record ChatMessage(String role, String content) {}

  public record ChatRequest(String model, List<ChatMessage> messages, Integer max_tokens) {}

  public record LlmResult(String content) {}

  private final RestClient.Builder restClientBuilder;
  private final SolutionsProperties props;

  public LlmClient(RestClient.Builder restClientBuilder, SolutionsProperties props) {
    this.restClientBuilder = restClientBuilder;
    this.props = props;
  }

  public boolean isConfigured() {
    return props.getLlm().isConfigured();
  }

  /**
   * Runs a minimal chat completion.
   *
   * @throws IllegalStateException when credentials are not configured
   * @throws Exception on transport/HTTP failure
   */
  @SuppressWarnings("unchecked")
  public String complete(String system, String user) throws Exception {
    if (!isConfigured()) {
      throw new IllegalStateException("llm is not configured (SOLUTIONS_LLM_* env)");
    }
    SolutionsProperties.Llm llm = props.getLlm();
    String url = llm.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
    Map<String, Object> response =
        restClientBuilder
            .build()
            .post()
            .uri(url)
            .header("Authorization", "Bearer " + llm.getApiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                new ChatRequest(
                    llm.getModel(),
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
