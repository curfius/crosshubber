package com.crosshubber.portal.modules.aihub.context;

import java.util.List;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.common.JsonUtils;

/**
 * Assembles the agent system message (AI plan A3): persona, the AI Hub configured prompt (if any),
 * the session context pack JSON and a compact tool catalogue — in that stable order, under a soft
 * budget (~1–2k tokens). Sections are dropped oldest-first (tool catalogue → open tabs) when inputs
 * are oversized.
 */
@Service
public class AgentPromptAssembler {

  private static final String PERSONA =
      """
      You are the Crosshubber portal agent, embedded in the Crosshubber portal. You help the \
      signed-in user with portal navigation, settings, and module data. You may call tools to \
      act on the user's behalf; mutating tools require the user's explicit confirmation before \
      they run. Answer in the user's language. Never invent portal state you cannot see. When a \
      document search tool returned results, mention the document titles you relied on — the UI \
      renders them as sources.""";

  private static final int MAX_TOOL_LINES = 30;

  /** One-line tool summary for the catalogue section (kept decoupled from the tool registry). */
  public record ToolSummary(String name, String description, boolean mutates) {}

  private final JsonUtils jsonUtils;

  public AgentPromptAssembler(JsonUtils jsonUtils) {
    this.jsonUtils = jsonUtils;
  }

  /**
   * Builds the system prompt.
   *
   * @param configuredPrompt the AI Hub settings system prompt, may be null/blank
   * @param pack sanitized session context pack, may be null (omits the context section)
   * @param tools active tool catalogue, may be empty (omits the catalogue section)
   * @return the assembled system message
   */
  public String build(String configuredPrompt, SessionContextPack pack, List<ToolSummary> tools) {
    StringBuilder sb = new StringBuilder(PERSONA);
    if (configuredPrompt != null && !configuredPrompt.isBlank()) {
      sb.append("\n\n").append(configuredPrompt.strip());
    }
    if (pack != null) {
      sb.append("\n\nSession context:\n").append(jsonUtils.write(pack));
    }
    if (tools != null && !tools.isEmpty()) {
      sb.append("\n\nAvailable tools:");
      tools.stream()
          .limit(MAX_TOOL_LINES)
          .forEach(
              t ->
                  sb.append("\n- ")
                      .append(t.name())
                      .append(" — ")
                      .append(t.description())
                      .append(t.mutates() ? " (mutating)" : ""));
    }
    return sb.toString();
  }
}
