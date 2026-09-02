package com.crosshubber.portal.modules.navigation.layout;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.navigation.NavigationValidationService;
import com.crosshubber.portal.security.PortalUser;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Navigation layout routes — mirrors navigation.routes.ts layout endpoints. PUT requires {@code
 * portal-navigation-edit}.
 */
@RestController
public class NavigationLayoutController {

  private final NavigationLayoutService layoutService;

  public NavigationLayoutController(NavigationLayoutService layoutService) {
    this.layoutService = layoutService;
  }

  @GetMapping("/api/navigation/layout")
  public Map<String, Object> get(@AuthenticationPrincipal PortalUser user) {
    return Map.of("layout", layoutService.getLayout());
  }

  @PutMapping("/api/navigation/layout")
  @PreAuthorize("hasRole('portal-navigation-edit')")
  public ResponseEntity<?> put(
      @AuthenticationPrincipal PortalUser user, @RequestBody JsonNode body) {
    NavigationValidationService.Validation validation =
        NavigationValidationService.validateLayout(body, layoutService.knownAppRefs());
    if (!validation.success()) {
      return ResponseEntity.badRequest().body(Map.of("error", validation.error()));
    }
    JsonNode stripped = NavigationValidationService.stripLayout(body);
    layoutService.saveLayout(stripped);
    return ResponseEntity.ok(Map.of("layout", stripped));
  }
}
