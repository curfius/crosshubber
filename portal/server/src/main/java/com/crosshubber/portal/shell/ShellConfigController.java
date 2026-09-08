package com.crosshubber.portal.shell;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Map<String, Object>> config(@AuthenticationPrincipal PortalUser user) {
    return ResponseEntity.ok(configService.buildConfig(user));
  }
}
