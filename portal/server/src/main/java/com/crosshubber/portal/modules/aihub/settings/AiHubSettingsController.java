package com.crosshubber.portal.modules.aihub.settings;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.common.SecurityUtils;

/**
 * AI Hub settings routes. Reads are open to authenticated users (quick chat and the assistant page
 * load UX defaults from it); writes require the AI Hub manage role or a platform role.
 */
@RestController
@RequestMapping("/api/ai-hub/settings")
public class AiHubSettingsController {

  private final AiHubSettingsService settingsService;

  public AiHubSettingsController(AiHubSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> get() {
    return ResponseEntity.ok(Map.of("settings", settingsService.get()));
  }

  @PutMapping
  public ResponseEntity<?> update(@RequestBody Map<String, Object> body) {
    List<String> userRoles = SecurityUtils.currentUserRoles();
    if (!userRoles.contains("portal-ai-hub-edit")
        && !userRoles.contains("portal-settings-edit")
        && !userRoles.contains("portal-admin")) {
      return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
    }
    if (body == null) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid request body"));
    }
    return ResponseEntity.ok(Map.of("settings", settingsService.update(body)));
  }
}
