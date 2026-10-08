package com.crosshubber.portal.modules.navigation.agent;

import java.util.List;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.modules.aihub.agent.AgentTool;
import com.crosshubber.portal.modules.aihub.agent.BuiltinToolProvider;
import com.crosshubber.portal.modules.navigation.settings.NavigationSettingsService;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Navigation agent tool (AI plan B1 seed, D5 provider split): declared against the {@code
 * navigation} embedded module — deactivating it removes the tool from the agent catalogue.
 */
@Service
public class NavigationAgentTools implements BuiltinToolProvider {

  static final String MODULE_KEY = "navigation";

  private final NavigationSettingsService navigationSettingsService;
  private final ObjectMapper objectMapper;

  public NavigationAgentTools(
      NavigationSettingsService navigationSettingsService, ObjectMapper objectMapper) {
    this.navigationSettingsService = navigationSettingsService;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<AgentTool> tools() {
    return List.of(
        AgentTool.builtin(
            MODULE_KEY,
            "getInstanceSettings",
            "Returns instance-level navigation settings (home app, theme policy, feature flags).",
            null,
            false,
            List.of()));
  }

  @Override
  public JsonNode handle(AgentTool tool, PortalUser user, JsonNode args) {
    return objectMapper.valueToTree(navigationSettingsService.get());
  }
}
