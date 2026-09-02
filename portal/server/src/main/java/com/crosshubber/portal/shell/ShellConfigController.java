package com.crosshubber.portal.shell;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.security.PortalUser;

/** Shell config endpoint — mirrors {@code GET /api/config} in portal.routes.ts. */
@RestController
public class ShellConfigController {

  private final ShellConfigService configService;

  public ShellConfigController(ShellConfigService configService) {
    this.configService = configService;
  }

  @GetMapping("/api/config")
  public ResponseEntity<Map<String, Object>> config(Authentication auth) {
    if (auth == null || !(auth.getPrincipal() instanceof PortalUser user)) {
      return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
    }
    return ResponseEntity.ok(configService.buildConfig(user));
  }
}
