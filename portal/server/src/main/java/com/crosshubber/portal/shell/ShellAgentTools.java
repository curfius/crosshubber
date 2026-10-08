package com.crosshubber.portal.shell;

import java.util.List;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.modules.aihub.agent.AgentTool;
import com.crosshubber.portal.modules.aihub.agent.BuiltinToolProvider;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shell platform agent tool (AI plan B1 seed, D5 provider split): platform-global reads owned by
 * the shell package — not attributed to any embedded module, so it is never gated.
 */
@Service
public class ShellAgentTools implements BuiltinToolProvider {

  private final ShellConfigService shellConfigService;
  private final ObjectMapper objectMapper;

  public ShellAgentTools(ShellConfigService shellConfigService, ObjectMapper objectMapper) {
    this.shellConfigService = shellConfigService;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<AgentTool> tools() {
    return List.of(
        AgentTool.builtin(
            "getShellConfig",
            "Returns the caller's shell configuration: user identity, visible module contents, "
                + "navigation groups and platform services.",
            null,
            false,
            List.of()));
  }

  @Override
  public JsonNode handle(AgentTool tool, PortalUser user, JsonNode args) {
    return objectMapper.valueToTree(shellConfigService.buildConfig(user));
  }
}
