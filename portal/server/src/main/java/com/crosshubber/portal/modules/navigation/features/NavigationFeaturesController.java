package com.crosshubber.portal.modules.navigation.features;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.settings.instance.InstanceSettingsService;
import com.crosshubber.portal.security.PortalUser;

/**
 * Navigation feature switches (backed by instance settings) — mirrors the features endpoints in
 * navigation.routes.ts (D11).
 */
@RestController
public class NavigationFeaturesController {

  private final InstanceSettingsService settingsService;

  public NavigationFeaturesController(InstanceSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @GetMapping("/api/navigation/features")
  public Map<String, Object> get(@AuthenticationPrincipal PortalUser user) {
    return featurePayload(settingsService.get());
  }

  @PutMapping("/api/navigation/features")
  @PreAuthorize("hasRole('portal-navigation-edit')")
  public ResponseEntity<?> put(
      @AuthenticationPrincipal PortalUser user, @RequestBody Map<String, Object> body) {
    if (body == null || body instanceof java.util.Collection) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid request body"));
    }
    Map<String, Object> allowed = new java.util.LinkedHashMap<>();
    if (body.containsKey("pinnedAppsEnabled")) {
      if (!(body.get("pinnedAppsEnabled") instanceof Boolean b)) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "pinnedAppsEnabled must be a boolean"));
      }
      allowed.put("pinnedAppsEnabled", b);
    }
    if (body.containsKey("workspacesEnabled")) {
      if (!(body.get("workspacesEnabled") instanceof Boolean b)) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "workspacesEnabled must be a boolean"));
      }
      allowed.put("workspacesEnabled", b);
    }
    return ResponseEntity.ok(featurePayload(settingsService.update(allowed)));
  }

  private static Map<String, Object> featurePayload(Map<String, Object> settings) {
    // LinkedHashMap: Map.of iteration order is unspecified — key order must match Node.
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("pinnedAppsEnabled", Boolean.TRUE.equals(settings.get("pinnedAppsEnabled")));
    out.put("workspacesEnabled", Boolean.TRUE.equals(settings.get("workspacesEnabled")));
    return out;
  }
}
