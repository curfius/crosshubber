package com.crosshubber.staffing.settings;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.staffing.security.AgentPrincipal;
import com.crosshubber.staffing.security.AgentPrincipals;

/**
 * Module settings REST (consumed by the module settings UI via its agent-call token). Both routes
 * require the module admin role — checked here explicitly because these calls bypass the portal
 * dispatcher (which enforces manifest roles only for agent tools).
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

  private final ModuleSettingsService settingsService;

  public SettingsController(ModuleSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @GetMapping
  public ModuleSettingsService.SettingsView get() {
    requireAdmin();
    return settingsService.view();
  }

  @PutMapping
  public ResponseEntity<?> put(@RequestBody ModuleSettingsService.SettingsUpdateRequest body) {
    requireAdmin();
    String error = settingsService.validate(body);
    if (error != null) {
      return ResponseEntity.badRequest().body(Map.of("error", error));
    }
    return ResponseEntity.ok(settingsService.update(body));
  }

  private void requireAdmin() {
    AgentPrincipal user = AgentPrincipals.current();
    if (user == null || !user.roles().contains("staffing-admin")) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "staffing-admin role required");
    }
  }
}
