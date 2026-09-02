package com.crosshubber.portal.modules.settings.modules;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.registry.modules.ModuleRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Module settings routes — mirrors {@code
 * portal/src/modules/module-settings/module-settings.routes.ts}: writes require the platform
 * fast-path role or one of the module's declared {@code securityRoles} manager keys.
 */
@RestController
@RequestMapping("/api/module-settings")
public class ModuleSettingsController {

  private final ModuleSettingsService settingsService;
  private final ModuleRepository moduleRepo;
  private final ObjectMapper objectMapper;

  public ModuleSettingsController(
      ModuleSettingsService settingsService,
      ModuleRepository moduleRepo,
      ObjectMapper objectMapper) {
    this.settingsService = settingsService;
    this.moduleRepo = moduleRepo;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/{moduleKey}")
  public ResponseEntity<Map<String, Object>> get(@PathVariable String moduleKey) {
    return ResponseEntity.ok(Map.of("settings", settingsService.get(moduleKey)));
  }

  @PutMapping("/{moduleKey}")
  public ResponseEntity<?> update(
      @PathVariable String moduleKey, @RequestBody Map<String, Object> body) {
    List<String> userRoles = currentUserRoles();
    if (!isModuleManager(userRoles, moduleKey)) {
      return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
    }
    if (body == null || body instanceof java.util.Collection) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid request body"));
    }
    return ResponseEntity.ok(Map.of("settings", settingsService.update(moduleKey, body)));
  }

  /**
   * Fast path: platform admins can always write. Slow path: any of the module's declared {@code
   * securityRoles[].key}.
   */
  private boolean isModuleManager(List<String> userRoles, String moduleKey) {
    if (userRoles.contains("portal-settings-edit") || userRoles.contains("portal-admin")) {
      return true;
    }
    return moduleRepo
        .findById(moduleKey)
        .map(m -> declaredManagerRoles(m.getSecurityRoles()))
        .map(roles -> roles.stream().anyMatch(userRoles::contains))
        .orElse(false);
  }

  private List<String> declaredManagerRoles(String securityRolesJson) {
    try {
      if (securityRolesJson == null || securityRolesJson.isBlank()) {
        return List.of();
      }
      JsonNode node = objectMapper.readTree(securityRolesJson);
      if (!node.isArray()) {
        return List.of();
      }
      return objectMapper
          .convertValue(node, new TypeReference<List<Map<String, Object>>>() {})
          .stream()
          .map(r -> r.get("key"))
          .filter(k -> k instanceof String)
          .map(k -> (String) k)
          .toList();
    } catch (Exception e) {
      return List.of();
    }
  }

  private static List<String> currentUserRoles() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null
        && auth.getPrincipal() instanceof com.crosshubber.portal.security.PortalUser user) {
      return user.roles();
    }
    return List.of();
  }
}
