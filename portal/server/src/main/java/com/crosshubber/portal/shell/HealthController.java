package com.crosshubber.portal.shell;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Health endpoint — public. Mirrors {@code GET /healthz} in portal.routes.ts. */
@RestController
public class HealthController {

  private final ShellHealthService healthService;

  public HealthController(ShellHealthService healthService) {
    this.healthService = healthService;
  }

  @GetMapping("/healthz")
  public ResponseEntity<Map<String, Object>> healthz() {
    boolean up = healthService.dbStatus();
    return ResponseEntity.ok(Map.of("ok", true, "app", "portal", "db", up ? "up" : "down"));
  }
}
