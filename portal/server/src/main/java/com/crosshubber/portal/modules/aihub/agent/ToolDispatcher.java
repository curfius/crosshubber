package com.crosshubber.portal.modules.aihub.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Dispatches model tool calls with RBAC, confirmation gating and audit (AI plan B4–B6).
 *
 * <p>Authorization: a tool declaring {@code roles[]} requires the caller to hold at least one of
 * them; undeclared roles = any authenticated user. Mutating tools never execute on first call —
 * they park in {@link PendingToolCallStore} and return {@code needs_confirmation}; only a
 * caller-owned confirmation executes them. Remote execution runs outside any transaction (TX
 * hygiene invariant); every attempt lands in the audit table with its outcome and duration.
 *
 * <p>Sub-agent delegations (AI plan F3) run through the same gates: {@code AGENT}-kind entries pass
 * their manifest {@code roles[]} check here (modules do not enforce roles themselves), never park
 * for confirmation (agents are delegation, not direct mutation), and their F1 result folds back as
 * a tool result — {@code status:"done"} → {@code ok}, anything else → {@code error}.
 */
@Service
public class ToolDispatcher {

  private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);

  private static final int MAX_ARGS_SUMMARY_LENGTH = 512;

  private final BuiltinToolHandlers builtinHandlers;
  private final RemoteToolInvoker remoteInvoker;
  private final SubAgentInvoker subAgentInvoker;
  private final PendingToolCallStore pendingStore;
  private final AgentToolCallRepository auditRepository;
  private final ObjectMapper objectMapper;

  public ToolDispatcher(
      BuiltinToolHandlers builtinHandlers,
      RemoteToolInvoker remoteInvoker,
      SubAgentInvoker subAgentInvoker,
      PendingToolCallStore pendingStore,
      AgentToolCallRepository auditRepository,
      ObjectMapper objectMapper) {
    this.builtinHandlers = builtinHandlers;
    this.remoteInvoker = remoteInvoker;
    this.subAgentInvoker = subAgentInvoker;
    this.pendingStore = pendingStore;
    this.auditRepository = auditRepository;
    this.objectMapper = objectMapper;
  }

  /** Dispatch outcome. */
  public record ToolResult(String status, JsonNode payload, String callId) {

    public static ToolResult of(String status, JsonNode payload) {
      return new ToolResult(status, payload, null);
    }
  }

  /**
   * Dispatches a tool call on behalf of {@code user}.
   *
   * @param confirmed true when this dispatch executes a previously confirmed pending call
   * @return the result — status {@code ok|denied|error|needs_confirmation}
   */
  public ToolResult dispatch(
      AgentTool tool, PortalUser user, String conversationId, JsonNode args, boolean confirmed) {
    long start = System.nanoTime();
    ToolResult result = doDispatch(tool, user, conversationId, args, confirmed);
    audit(tool, user, conversationId, args, result, start);
    return result;
  }

  /**
   * Executes a pending mutating call after UI confirmation (AI plan B6). Only the triggering user
   * can confirm; expired/unknown ids fail closed.
   */
  public ToolResult executePending(String callId, PortalUser user) {
    PendingToolCallStore.PendingCall call = pendingStore.take(callId, user.sub());
    if (call == null) {
      return ToolResult.of("error", errorPayload("confirmation expired or unknown call id"));
    }
    return dispatch(call.tool(), user, call.conversationId(), call.args(), true);
  }

  private ToolResult doDispatch(
      AgentTool tool, PortalUser user, String conversationId, JsonNode args, boolean confirmed) {
    if (!authorized(tool, user)) {
      return ToolResult.of(
          "denied", errorPayload("you do not have the required roles for " + tool.modelName()));
    }
    if (tool.mutates() && !confirmed) {
      String callId = pendingStore.create(tool, user, conversationId, args);
      if (callId == null) {
        return ToolResult.of("error", errorPayload("too many pending confirmations"));
      }
      ObjectNode payload = objectMapper.createObjectNode();
      payload.put("status", "needs_confirmation");
      payload.put("callId", callId);
      payload.put(
          "message",
          "Tool "
              + tool.modelName()
              + " mutates state and needs explicit user confirmation. "
              + "Ask the user to confirm; once confirmed the call executes.");
      return new ToolResult("needs_confirmation", payload, callId);
    }
    try {
      return switch (tool.kind()) {
        case BUILTIN -> ToolResult.of("ok", builtinHandlers.handle(tool, user, args));
        case REMOTE -> ToolResult.of("ok", remoteInvoker.invoke(tool, user, args));
        case AGENT -> dispatchSubAgent(tool, user, conversationId, args);
      };
    } catch (Exception e) {
      log.warn("[agent] tool {} failed: {}", tool.modelName(), e.getMessage());
      return ToolResult.of("error", errorPayload(safeMessage(e)));
    }
  }

  /**
   * Delegates to a module-owned sub-agent (AI plan F3): the F1 envelope round-trip result folds
   * back as a tool result — {@code status:"done"} maps to {@code ok}, anything else to {@code
   * error} so the model narrates the failure instead of treating it as an answer.
   */
  private ToolResult dispatchSubAgent(
      AgentTool tool, PortalUser user, String conversationId, JsonNode args) throws Exception {
    JsonNode payload = subAgentInvoker.invoke(tool, user, conversationId, args);
    String subStatus = payload.path("status").asString("");
    return ToolResult.of("done".equals(subStatus) ? "ok" : "error", payload);
  }

  private boolean authorized(AgentTool tool, PortalUser user) {
    return tool.authorizedFor(user.roles());
  }

  private void audit(
      AgentTool tool,
      PortalUser user,
      String conversationId,
      JsonNode args,
      ToolResult result,
      long startNanos) {
    try {
      String outcome =
          switch (result.status()) {
            case "ok" -> confirmedAuditOutcome(tool, result);
            case "denied", "error", "needs_confirmation" -> result.status();
            default -> result.status();
          };
      auditRepository.save(
          AgentToolCallEntity.of(
              user.sub(),
              conversationId,
              tool,
              argsSummary(args),
              outcome,
              (int) ((System.nanoTime() - startNanos) / 1_000_000)));
    } catch (Exception e) {
      log.warn("[agent] audit write failed: {}", e.getMessage());
    }
  }

  private String confirmedAuditOutcome(AgentTool tool, ToolResult result) {
    // a confirmed mutating call that lands ok is recorded as "confirmed"; plain reads stay "ok"
    return tool.mutates() ? "confirmed" : "ok";
  }

  private String argsSummary(JsonNode args) {
    if (args == null) {
      return null;
    }
    String json = args.toString();
    return json.length() > MAX_ARGS_SUMMARY_LENGTH
        ? json.substring(0, MAX_ARGS_SUMMARY_LENGTH)
        : json;
  }

  private ObjectNode errorPayload(String message) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("error", message);
    return node;
  }

  private static String safeMessage(Exception e) {
    return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
  }
}
