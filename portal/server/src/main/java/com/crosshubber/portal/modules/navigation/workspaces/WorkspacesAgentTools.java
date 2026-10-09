package com.crosshubber.portal.modules.navigation.workspaces;

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
 * navigation module's workspaces sub-package — attributed to {@code navigation} so deactivating the
 * module gates the tool and removes it from the synthesized manifest.
 */
@Service
public class WorkspacesAgentTools implements BuiltinToolProvider {

  static final String MODULE_KEY = "navigation";

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
            MODULE_KEY,
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
