package com.crosshubber.portal.modules.navigation.pinnedapps;

import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.navigation.NavigationValidationService;
import com.fasterxml.jackson.databind.JsonNode;

/** Pinned apps routes — mirrors the pinned-apps section of navigation.routes.ts. */
@RestController
public class PinnedAppsController {

  private final PinnedAppsService pinnedAppsService;

  public PinnedAppsController(PinnedAppsService pinnedAppsService) {
    this.pinnedAppsService = pinnedAppsService;
  }

  @GetMapping("/api/navigation/pinned-apps")
  public Map<String, Object> get(
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.crosshubber.portal.security.PortalUser user) {
    return Map.of("tree", pinnedAppsService.getPinnedTree(user.sub()));
  }

  @PutMapping("/api/navigation/pinned-apps")
  public ResponseEntity<?> save(
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.crosshubber.portal.security.PortalUser user,
      @RequestBody JsonNode body) {
    if (body == null || !body.isArray()) {
      return ResponseEntity.badRequest().body(Map.of("error", "expected an array of nodes"));
    }
    NavigationValidationService.Validation validation =
        NavigationValidationService.validatePinnedTree(body, pinnedAppsService.knownAppRefs());
    if (!validation.success()) {
      return ResponseEntity.badRequest().body(Map.of("error", validation.error()));
    }
    pinnedAppsService.savePinnedTree(user.sub(), body);
    return ResponseEntity.ok(Map.of("tree", pinnedAppsService.getPinnedTree(user.sub())));
  }

  @PostMapping("/api/navigation/pinned-apps")
  public ResponseEntity<?> pin(
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.crosshubber.portal.security.PortalUser user,
      @RequestBody Map<String, Object> body) {
    Object ref = body == null ? null : body.get("ref");
    if (!(ref instanceof String r) || !r.matches(NavigationValidationService.REF_RE)) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "ref must be of the form \"moduleKey:entryKey\""));
    }
    Set<String> known = pinnedAppsService.knownAppRefs();
    if (!known.contains(r)) {
      return ResponseEntity.badRequest().body(Map.of("error", "unknown app ref \"" + r + "\""));
    }
    pinnedAppsService.pinRef(user.sub(), r);
    return ResponseEntity.ok(Map.of("ok", true));
  }

  @DeleteMapping("/api/navigation/pinned-apps/items/{ref}")
  public ResponseEntity<?> unpin(
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.crosshubber.portal.security.PortalUser user,
      @PathVariable String ref) {
    String decoded = java.net.URLDecoder.decode(ref, java.nio.charset.StandardCharsets.UTF_8);
    if (!decoded.matches(NavigationValidationService.REF_RE)) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "ref must be of the form \"moduleKey:entryKey\""));
    }
    pinnedAppsService.unpinRef(user.sub(), decoded);
    return ResponseEntity.ok(Map.of("ok", true));
  }
}
