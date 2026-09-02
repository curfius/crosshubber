package com.crosshubber.portal.modules.navigation.usersettings;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.security.PortalUser;
import com.fasterxml.jackson.databind.JsonNode;

/** Navigation user-settings routes — mirrors navigation.routes.ts user-settings. */
@RestController
public class NavigationUserSettingsController {

  private final NavigationUserSettingsService settingsService;

  public NavigationUserSettingsController(NavigationUserSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @GetMapping("/api/navigation/user-settings")
  public Map<String, Object> get(@AuthenticationPrincipal PortalUser user) {
    return Map.of("settings", settingsService.mergedSettings(user.sub()));
  }

  @PutMapping("/api/navigation/user-settings")
  public ResponseEntity<?> put(
      @AuthenticationPrincipal PortalUser user, @RequestBody JsonNode body) {
    String error = settingsService.validatePartial(body);
    if (error != null) {
      return ResponseEntity.badRequest().body(Map.of("error", error));
    }
    return ResponseEntity.ok(Map.of("settings", settingsService.applyPartial(user.sub(), body)));
  }
}
