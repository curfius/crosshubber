package com.crosshubber.portal.modules.agent;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Calls a module-owned sub-agent endpoint over HTTP with the F1 task envelope (AI plan F1/F3,
 * AI_MODULES_PLAN P7): {@code POST {baseUrl}{endpoint}} with body {@code {task, context,
 * timeoutMs}} and the portal-signed agent-call token identifying the caller ({@link
 * AgentCallAuthorizer}). The module answers {@code {status, output, artifacts[], auditRef}}.
 *
 * <p>Delegation depth is structurally 1: the sub-agent runs module-side as a single model turn and
 * has no path back into the portal's tool loop, so it cannot spawn further sub-agents (F3 depth
 * cap). Identity forwarding is the same-user {@code X-Portal-Agent} token — the sub-agent sees the
 * delegating user's roles, never an elevated principal.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional} — blocking HTTP must never hold a
 * Hikari connection (AGENTS.md TX hygiene invariant). The caller ({@link ToolDispatcher}) is
 * fail-soft: transport failures and module-side {@code status:"failed"} answers surface as
 * tool-result errors the model can narrate, never as portal 500s.
 */
@Service
public class SubAgentInvoker {

  /** Envelope timeout hint — kept inside the shared 30s read timeout (HttpClientConfig). */
  private static final long TIMEOUT_MS = 25_000;

  private final RestClient.Builder restClientBuilder;
  private final AgentCallAuthorizer authorizer;
  private final ObjectMapper objectMapper;

  public SubAgentInvoker(
      RestClient.Builder restClientBuilder,
      AgentCallAuthorizer authorizer,
      ObjectMapper objectMapper) {
    this.restClientBuilder = restClientBuilder;
    this.authorizer = authorizer;
    this.objectMapper = objectMapper;
  }

  /**
   * Invokes a sub-agent with the F1 envelope.
   *
   * @param tool an AGENT-kind entry with a resolved {@code baseUrl} and manifest {@code endpoint}
   * @param user the caller on whose behalf the delegation runs
   * @param conversationId current conversation, folded into the envelope {@code context}
   * @param args tool arguments — {@code task} (required), optional {@code expectedOutput}
   * @return the parsed JSON {@code TaskResult} from the module (empty object on empty body)
   * @throws Exception on invalid arguments or transport/HTTP failure — the dispatcher converts
   *     these to an error result
   */
  public JsonNode invoke(AgentTool tool, PortalUser user, String conversationId, JsonNode args)
      throws Exception {
    String task = args == null ? null : args.path("task").asString(null);
    if (task == null || task.isBlank()) {
      throw new IllegalArgumentException(
          "task is required to delegate to sub-agent " + tool.modelName());
    }
    ObjectNode body = objectMapper.createObjectNode();
    body.put("task", task);
    if (args.hasNonNull("expectedOutput")) {
      body.put("expectedOutput", args.path("expectedOutput").asString());
    }
    ObjectNode context = body.putObject("context");
    if (conversationId != null) {
      context.put("conversationId", conversationId);
    }
    body.put("timeoutMs", TIMEOUT_MS);
    String url = tool.baseUrl().replaceAll("/+$", "") + tool.path();
    String raw =
        restClientBuilder
            .build()
            .post()
            .uri(url)
            .header("X-Portal-Agent", authorizer.issue(user))
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(body))
            .retrieve()
            .body(String.class);
    return raw == null || raw.isBlank()
        ? objectMapper.createObjectNode()
        : objectMapper.readTree(raw);
  }
}
