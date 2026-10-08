package com.crosshubber.portal.modules.aihub.agent;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Calls a remote module's tool endpoint over HTTP (AI plan B2/P3): {@code POST
 * {baseUrl}{path|/agent/tools/{name}}} with the tool arguments and a portal-signed agent-call token
 * identifying the caller ({@link AgentCallAuthorizer}).
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional} — blocking HTTP must never hold a
 * Hikari connection (AGENTS.md TX hygiene invariant). The caller ({@link ToolDispatcher}) is
 * fail-soft: transport failures surface as tool-result errors the model can narrate, never as
 * portal 500s. Base URLs are admin-configured at install time (like the MFE proxy), so no {@code
 * SsrfGuard} round-trip is needed here.
 */
@Service
public class RemoteToolInvoker {

  /** Remote tool request envelope — shaped like the F1 task envelope for tool calls. */
  public record RemoteToolRequest(String tool, JsonNode arguments) {}

  private final RestClient.Builder restClientBuilder;
  private final AgentCallAuthorizer authorizer;
  private final ObjectMapper objectMapper;

  public RemoteToolInvoker(
      RestClient.Builder restClientBuilder,
      AgentCallAuthorizer authorizer,
      ObjectMapper objectMapper) {
    this.restClientBuilder = restClientBuilder;
    this.authorizer = authorizer;
    this.objectMapper = objectMapper;
  }

  /**
   * Invokes a remote tool.
   *
   * @param tool a REMOTE-kind tool with a resolved {@code baseUrl}
   * @param user the caller on whose behalf the call runs
   * @param args tool arguments
   * @return the parsed JSON response from the module (empty object on empty body)
   * @throws Exception on transport/HTTP failure — the dispatcher converts this to an error result
   */
  public JsonNode invoke(AgentTool tool, PortalUser user, JsonNode args) throws Exception {
    String path = tool.path() != null ? tool.path() : "/agent/tools/" + tool.name();
    String url = tool.baseUrl().replaceAll("/+$", "") + path;
    String body = objectMapper.writeValueAsString(new RemoteToolRequest(tool.name(), args));
    String raw =
        restClientBuilder
            .build()
            .post()
            .uri(url)
            .header("X-Portal-Agent", authorizer.issue(user))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(String.class);
    return raw == null || raw.isBlank()
        ? objectMapper.createObjectNode()
        : objectMapper.readTree(raw);
  }
}
