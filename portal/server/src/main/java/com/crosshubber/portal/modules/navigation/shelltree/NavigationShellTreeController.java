package com.crosshubber.portal.modules.navigation.shelltree;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.security.PortalUser;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Shell nav tree routes (Settings / User Settings editors) — mirrors the shell-tree endpoints in
 * navigation.routes.ts. Admin-only ({@code portal-navigation-edit}).
 */
@RestController
public class NavigationShellTreeController {

  private final ShellTreeService shellTreeService;

  public NavigationShellTreeController(ShellTreeService shellTreeService) {
    this.shellTreeService = shellTreeService;
  }

  @GetMapping("/api/navigation/shell-tree")
  @PreAuthorize("hasRole('portal-navigation-edit')")
  public ResponseEntity<?> get(
      @AuthenticationPrincipal PortalUser user, @RequestParam(required = false) String category) {
    if (!isShellCategory(category)) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "category must be settings|user-settings"));
    }
    return ResponseEntity.ok(shellTreeService.shellTreePayload(category));
  }

  @PutMapping("/api/navigation/shell-tree/{category}")
  @PreAuthorize("hasRole('portal-navigation-edit')")
  public ResponseEntity<?> put(
      @AuthenticationPrincipal PortalUser user,
      @PathVariable String category,
      @RequestBody JsonNode body) {
    if (!isShellCategory(category)) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "category must be settings|user-settings"));
    }
    if (body == null || !body.has("groups") || !body.get("groups").isArray()) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "groups: Invalid input: expected array, received undefined"));
    }
    if (!body.has("items") || !body.get("items").isArray()) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "items: Invalid input: expected array, received undefined"));
    }
    try {
      return ResponseEntity.ok(shellTreeService.saveShellTree(category, body));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  private static boolean isShellCategory(String category) {
    return "settings".equals(category) || "user-settings".equals(category);
  }
}
