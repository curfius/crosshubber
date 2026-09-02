package com.crosshubber.portal.modules.usersettings.scopes;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.security.PortalUser;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * User settings routes — mirrors {@code portal/src/modules/user-settings/user-settings.routes.ts}:
 * 16 KB payload cap, {@code general} scope restricted to {@code theme}/{@code language}.
 */
@RestController
@RequestMapping("/api/user-settings")
public class UserSettingsController {

  private static final int MAX_BODY_BYTES = 16 * 1024;
  private static final String KEY_RE = "^[a-z0-9][a-z0-9-]{0,63}$";

  /** Portal-owned 'general' scope allow-list: key -> max string length. */
  private static final Map<String, Integer> GENERAL_ALLOWED_KEYS =
      Map.of("theme", 64, "language", 16);

  private final UserSettingsService settingsService;
  private final ObjectMapper objectMapper;

  public UserSettingsController(UserSettingsService settingsService, ObjectMapper objectMapper) {
    this.settingsService = settingsService;
    this.objectMapper = objectMapper;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> getAll(Authentication auth) {
    PortalUser user = user(auth);
    if (user == null) {
      return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
    }
    return ResponseEntity.ok(Map.of("settings", settingsService.getAll(user.sub())));
  }

  @GetMapping("/{scope}")
  public ResponseEntity<?> get(@PathVariable String scope, Authentication auth) {
    PortalUser user = user(auth);
    if (user == null) {
      return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
    }
    if (!scope.matches(KEY_RE)) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "scope must match [a-z0-9][a-z0-9-]{0,63}"));
    }
    return ResponseEntity.ok(Map.of("settings", settingsService.get(user.sub(), scope)));
  }

  @PutMapping("/{scope}")
  public ResponseEntity<?> put(
      @PathVariable String scope, @RequestBody Map<String, Object> body, Authentication auth) {
    PortalUser user = user(auth);
    if (user == null) {
      return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
    }
    if (!scope.matches(KEY_RE)) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "scope must match [a-z0-9][a-z0-9-]{0,63}"));
    }
    if (body == null || body instanceof java.util.Collection) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid request body"));
    }
    if (jsonLength(body) > MAX_BODY_BYTES) {
      return ResponseEntity.status(413).body(Map.of("error", "settings payload too large"));
    }
    if ("general".equals(scope)) {
      String error = validateGeneralBody(body);
      if (error != null) {
        return ResponseEntity.badRequest().body(Map.of("error", error));
      }
    }
    return ResponseEntity.ok(Map.of("settings", settingsService.update(user.sub(), scope, body)));
  }

  private String validateGeneralBody(Map<String, Object> body) {
    for (Map.Entry<String, Object> entry : body.entrySet()) {
      Integer max = GENERAL_ALLOWED_KEYS.get(entry.getKey());
      if (max == null) {
        return "unknown key \""
            + entry.getKey()
            + "\" for scope \"general\""
            + " (allowed: theme, language)";
      }
      if (!(entry.getValue() instanceof String s) || s.length() > max) {
        return "\"" + entry.getKey() + "\" must be a string of at most " + max + " characters";
      }
    }
    return null;
  }

  private int jsonLength(Object value) {
    try {
      return objectMapper.writeValueAsString(value).length();
    } catch (Exception e) {
      return Integer.MAX_VALUE;
    }
  }

  private static PortalUser user(Authentication auth) {
    if (auth != null && auth.getPrincipal() instanceof PortalUser user) {
      return user;
    }
    return null;
  }
}
