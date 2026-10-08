package com.crosshubber.portal.modules.aihub.agent;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModulesService;
import com.crosshubber.portal.security.PortalUser;

/**
 * Issues a short-lived portal-signed agent-call token for one installed module (AI_MODULES_PLAN P5
 * identity bridge): the portal agent and module UIs authenticate to module backends with it. Fails
 * closed for unknown/inactive modules or modules without a base URL.
 */
@RestController
public class ModuleTokenController {

  /** TTL mirrored from {@link AgentCallAuthorizer} (informational for clients). */
  private static final long TTL_SECONDS = 300;

  private final AgentCallAuthorizer authorizer;
  private final ModulesService modulesService;

  public ModuleTokenController(AgentCallAuthorizer authorizer, ModulesService modulesService) {
    this.authorizer = authorizer;
    this.modulesService = modulesService;
  }

  @GetMapping("/api/agent/module-token/{moduleKey}")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Map<String, Object>> issue(
      @AuthenticationPrincipal PortalUser user, @PathVariable String moduleKey) {
    ModuleEntity module =
        modulesService.list(false).stream()
            .filter(m -> moduleKey.equals(m.getKey()))
            .findFirst()
            .orElse(null);
    if (module == null
        || !Boolean.TRUE.equals(module.getActive())
        || module.getBaseUrl() == null
        || module.getBaseUrl().isBlank()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", "module not found, inactive, or has no base url"));
    }
    return ResponseEntity.ok(Map.of("token", authorizer.issue(user), "expiresIn", TTL_SECONDS));
  }
}
