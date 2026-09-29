package com.crosshubber.portal.modules.settings.modules;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.common.SecurityUtils;
import com.crosshubber.portal.modules.registry.dto.SecurityRoleDto;
import com.crosshubber.portal.modules.registry.modules.ModuleRepository;

/**
 * Module settings routes: writes require the platform fast-path role or one of the module's
 * declared {@code securityRoles} manager keys.
 */
@RestController
@RequestMapping("/api/module-settings")
public class ModuleSettingsController {

  private final ModuleSettingsService settingsService;
  private final ModuleRepository moduleRepo;
  private final JsonUtils jsonUtils;

  public ModuleSettingsController(
      ModuleSettingsService settingsService, ModuleRepository moduleRepo, JsonUtils jsonUtils) {
    this.settingsService = settingsService;
    this.moduleRepo = moduleRepo;
    this.jsonUtils = jsonUtils;
  }

  @GetMapping("/{moduleKey}")
  public ResponseEntity<Map<String, Object>> get(@PathVariable String moduleKey) {
    return ResponseEntity.ok(Map.of("settings", settingsService.get(moduleKey)));
  }

  @PutMapping("/{moduleKey}")
  public ResponseEntity<?> update(
      @PathVariable String moduleKey, @RequestBody Map<String, Object> body) {
    List<String> userRoles = SecurityUtils.currentUserRoles();
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
    return jsonUtils.parseList(securityRolesJson, SecurityRoleDto.class).stream()
        .map(r -> r.key())
        .filter(Objects::nonNull)
        .toList();
  }
}
