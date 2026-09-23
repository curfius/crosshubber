package com.crosshubber.portal.modules.settings.instance;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * Instance settings routes.
 *
 * <p>Only {@code homeApp} is written here; the feature switches are owned by the navigation module
 * (D11).
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

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
  public ResponseEntity<?> update(@Valid @RequestBody HomeAppRequest body) {
    Map<String, Object> allowed = new java.util.LinkedHashMap<>();
    if (body.homeApp() != null) {
      allowed.put("homeApp", body.homeApp());
    }
    return ResponseEntity.ok(settingsService.update(allowed));
  }
}
