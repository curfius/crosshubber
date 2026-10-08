package com.crosshubber.portal.workspaces;

import java.util.List;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.modules.aihub.agent.AgentTool;
import com.crosshubber.portal.modules.aihub.agent.BuiltinToolProvider;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Workspace agent tool (AI plan B1 seed, D5 provider split): caller-scoped read owned by the
 * workspaces package — platform-global (not attributed to an embedded module, never gated).
 */
@Service
public class WorkspacesAgentTools implements BuiltinToolProvider {

  private static final int MAX_WORKSPACES = 50;

  private final WorkspaceRepository workspaceRepository;
  private final ObjectMapper objectMapper;

  public WorkspacesAgentTools(WorkspaceRepository workspaceRepository, ObjectMapper objectMapper) {
    this.workspaceRepository = workspaceRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<AgentTool> tools() {
    return List.of(
        AgentTool.builtin(
            "listWorkspaces",
            "Lists the caller's saved workspaces (name, description, status).",
            null,
            false,
            List.of()));
  }

  @Override
  public JsonNode handle(AgentTool tool, PortalUser user, JsonNode args) {
    ArrayNode out = objectMapper.createArrayNode();
    for (WorkspaceEntity w : workspaceRepository.findByUserIdOrderBySavedAtDesc(user.sub())) {
      if (out.size() >= MAX_WORKSPACES) {
        break;
      }
      ObjectNode row = objectMapper.createObjectNode();
      row.put("name", w.getName());
      row.put("description", w.getDescription());
      row.put("status", w.getStatus());
      row.put("savedAt", w.getSavedAt() == null ? null : w.getSavedAt().toString());
      out.add(row);
    }
    return out;
  }
}
