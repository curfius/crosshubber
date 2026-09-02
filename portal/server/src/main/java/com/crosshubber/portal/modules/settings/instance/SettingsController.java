package com.crosshubber.portal.modules.settings.instance;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Instance settings routes — mirrors {@code portal/src/modules/settings/settings.routes.ts}.
 *
 * <p>Only {@code homeApp} is written here; the feature switches are owned by the navigation module
 * (D11).
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

  /** Mirrors REF_RE in navigation.service.ts. */
  private static final String REF_RE = "^[a-z0-9][a-z0-9-]{0,63}:[a-z0-9][a-z0-9-]{0,63}$";

  private final InstanceSettingsService settingsService;

  public SettingsController(InstanceSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> get() {
    return ResponseEntity.ok(settingsService.get());
  }

  @PutMapping
  @PreAuthorize("hasRole('portal-settings-edit')")
  public ResponseEntity<?> update(@RequestBody Map<String, Object> body) {
    if (body == null || body instanceof java.util.Collection) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid request body"));
    }
    Map<String, Object> allowed = new java.util.LinkedHashMap<>();
    if (body.containsKey("homeApp")) {
      Object homeApp = body.get("homeApp");
      if (!(homeApp instanceof String s) || !s.matches(REF_RE)) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "homeApp must be a ref of the form \"moduleKey:entryKey\""));
      }
      allowed.put("homeApp", s);
    }
    return ResponseEntity.ok(settingsService.update(allowed));
  }
}
