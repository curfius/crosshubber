package com.crosshubber.portal.modules.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.ObjectMapper;

/**
 * Builds the request-scoped {@link ToolCallback} set wired to the {@link ToolDispatcher} (AI plan
 * B3): one callback per catalogue tool, sharing a per-turn iteration budget and SSE frame emitter.
 * A fresh set is built per chat request — no shared mutable state.
 */
@Service
public class AgentToolCallbacks {

  /** Per-turn tool iteration budget (cost/abuse control; see AI plan cross-cutting backlog). */
  private static final int MAX_ITERATIONS = 8;

  private final ToolRegistry registry;
  private final ToolDispatcher dispatcher;
  private final ObjectMapper objectMapper;

  public AgentToolCallbacks(
      ToolRegistry registry, ToolDispatcher dispatcher, ObjectMapper objectMapper) {
    this.registry = registry;
    this.dispatcher = dispatcher;
    this.objectMapper = objectMapper;
  }

  /**
   * Builds the callback set for one chat turn.
   *
   * @param user the authenticated caller (identity propagated to every dispatch)
   * @param conversationId the conversation the turn belongs to (for audit correlation)
   * @param frameEmitter receives tool SSE frames ({@code tool_call}/{@code tool_result}/ {@code
   *     confirmation_required}); may be null when frames are not streamed
   * @return one callback per catalogue tool, sharing a single iteration budget
   */
  public List<ToolCallback> forRequest(
      PortalUser user, String conversationId, Consumer<String> frameEmitter) {
    List<ToolCallback> callbacks = new ArrayList<>();
    AtomicInteger budget = new AtomicInteger();
    for (AgentTool tool : registry.catalogue()) {
      callbacks.add(
          new DispatchingToolCallback(
              tool,
              dispatcher,
              objectMapper,
              user,
              conversationId,
              frameEmitter,
              MAX_ITERATIONS,
              budget));
    }
    return callbacks;
  }
}
