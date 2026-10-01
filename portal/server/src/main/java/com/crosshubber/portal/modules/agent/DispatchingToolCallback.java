package com.crosshubber.portal.modules.agent;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spring AI {@link ToolCallback} adapter over {@link ToolDispatcher} (AI plan B3): the model's
 * native tool-calling loop lands here. Emits {@code tool_call}/{@code tool_result}/{@code
 * confirmation_required} SSE frames through the request-scoped emitter (plus one {@code citation}
 * frame for successful results carrying a snippets/citations array — AI plan G3) and enforces the
 * per-turn iteration budget — over budget, the model receives an error result telling it to stop
 * calling tools (fail-closed, no silent runaway loops).
 */
class DispatchingToolCallback implements ToolCallback {

  private static final Logger log = LoggerFactory.getLogger(DispatchingToolCallback.class);

  private final AgentTool tool;
  private final ToolDispatcher dispatcher;
  private final ObjectMapper objectMapper;
  private final PortalUser user;
  private final String conversationId;
  private final Consumer<String> frameEmitter;
  private final int maxIterations;
  private final AtomicInteger budget;

  DispatchingToolCallback(
      AgentTool tool,
      ToolDispatcher dispatcher,
      ObjectMapper objectMapper,
      PortalUser user,
      String conversationId,
      Consumer<String> frameEmitter,
      int maxIterations,
      AtomicInteger budget) {
    this.tool = tool;
    this.dispatcher = dispatcher;
    this.objectMapper = objectMapper;
    this.user = user;
    this.conversationId = conversationId;
    this.frameEmitter = frameEmitter;
    this.maxIterations = maxIterations;
    this.budget = budget;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return ToolDefinition.builder()
        .name(tool.modelName())
        .description(tool.description())
        .inputSchema(tool.arguments() != null ? tool.arguments().toString() : "{}")
        .build();
  }

  @Override
  public String call(String toolInput) {
    if (budget.incrementAndGet() > maxIterations) {
      ToolDispatcher.ToolResult capped =
          new ToolDispatcher.ToolResult(
              "cap_reached",
              errorPayload("tool iteration budget exhausted — answer with what you have"),
              null);
      emit("tool_result", resultFrame(capped));
      return objectMapper.writeValueAsString(capped.payload());
    }
    emit(
        "tool_call",
        frame("tool_call")
            .put("tool", tool.modelName())
            .put("module", tool.moduleKey())
            .put("mutates", tool.mutates()));

    JsonNode args = parseArgs(toolInput);
    ToolDispatcher.ToolResult result = dispatcher.dispatch(tool, user, conversationId, args, false);

    ObjectNode resultFrame = resultFrame(result);
    emit("tool_result", resultFrame);
    if ("ok".equals(result.status())) {
      emitCitations(result.payload());
    }
    if ("needs_confirmation".equals(result.status())) {
      ObjectNode confirmFrame = frame("confirmation_required");
      confirmFrame.put("tool", tool.modelName());
      confirmFrame.put("callId", result.callId());
      emit("confirmation_required", confirmFrame);
    }
    log.debug("[agent] dispatched {} -> {}", tool.modelName(), result.status());
    return objectMapper.writeValueAsString(result.payload());
  }

  private JsonNode parseArgs(String toolInput) {
    if (toolInput == null || toolInput.isBlank()) {
      return objectMapper.createObjectNode();
    }
    try {
      JsonNode parsed = objectMapper.readTree(toolInput);
      return parsed.isObject() ? parsed : objectMapper.createObjectNode();
    } catch (Exception e) {
      log.warn("[agent] malformed tool input for {}: {}", tool.modelName(), e.getMessage());
      ObjectNode error = objectMapper.createObjectNode();
      error.put("error", "arguments must be a JSON object");
      return error;
    }
  }

  private ObjectNode resultFrame(ToolDispatcher.ToolResult result) {
    ObjectNode frame = frame("tool_result");
    frame.put("tool", tool.modelName());
    frame.put("status", result.status());
    if (result.callId() != null) {
      frame.put("callId", result.callId());
    }
    return frame;
  }

  private ObjectNode frame(String type) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("type", type);
    return node;
  }

  /** G3: a successful payload with a citations/snippets array emits one citation frame. */
  private void emitCitations(JsonNode payload) {
    List<CitationExtractor.Citation> citations = CitationExtractor.fromPayload(payload);
    if (citations.isEmpty()) {
      return;
    }
    ObjectNode frame = frame("citation");
    frame.put("tool", tool.modelName());
    ArrayNode items = frame.putArray("citations");
    for (CitationExtractor.Citation citation : citations) {
      ObjectNode item = items.addObject();
      item.put("title", citation.title());
      if (citation.ref() != null) {
        item.put("ref", citation.ref());
      }
      if (citation.snippet() != null) {
        item.put("snippet", citation.snippet());
      }
    }
    emit("citation", frame);
  }

  private ObjectNode errorPayload(String message) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("error", message);
    return node;
  }

  private void emit(String type, ObjectNode frame) {
    if (frameEmitter == null) {
      return;
    }
    try {
      frameEmitter.accept(objectMapper.writeValueAsString(frame));
    } catch (Exception e) {
      log.warn("[agent] frame emit failed ({}): {}", type, e.getMessage());
    }
  }
}
