package com.crosshubber.portal.modules.aihub.agent;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.security.PortalUser;

/** Lists the agent tools/agents the authenticated user has access to (phase 5). */
@RestController
public class AgentCatalogController {

  private final AgentCatalogService catalogService;

  public AgentCatalogController(AgentCatalogService catalogService) {
    this.catalogService = catalogService;
  }

  @GetMapping("/api/ai-hub/agent-catalog")
  public Map<String, Object> catalog(@AuthenticationPrincipal PortalUser user) {
    return Map.of("entries", catalogService.catalogFor(user));
  }
}
