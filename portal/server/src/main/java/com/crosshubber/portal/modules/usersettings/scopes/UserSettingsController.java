package com.crosshubber.portal.modules.usersettings.scopes;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.common.Keys;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.ObjectMapper;

/**
 * User settings routes: 16 KB payload cap, {@code general} scope restricted to {@code theme}/{@code
 * language}. Authentication is enforced by {@code @PreAuthorize} + the filter chain (401 envelope
 * comes from the security entry point, not this controller).
 */
@RestController
@RequestMapping("/api/user-settings")
public class UserSettingsController {

  private static final int MAX_BODY_BYTES = 16 * 1024;

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
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Map<String, Object>> getAll(@AuthenticationPrincipal PortalUser user) {
    return ResponseEntity.ok(Map.of("settings", settingsService.getAll(user.sub())));
  }

  @GetMapping("/{scope}")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<?> get(
      @PathVariable String scope, @AuthenticationPrincipal PortalUser user) {
    if (!scope.matches(Keys.KEY_RE)) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "scope must match [a-z0-9][a-z0-9-]{0,63}"));
    }
    return ResponseEntity.ok(Map.of("settings", settingsService.get(user.sub(), scope)));
  }

  @PutMapping("/{scope}")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<?> put(
      @PathVariable String scope,
      @RequestBody Map<String, Object> body,
      @AuthenticationPrincipal PortalUser user) {
    if (!scope.matches(Keys.KEY_RE)) {
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
    } else if ("ai".equals(scope)) {
      String error = validateAiBody(body);
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

  /**
   * Portal-owned 'ai' scope allow-list (phase 5): the user's default chat model, the "about you"
   * context and the disabled tools/agents list. Unknown keys are rejected like {@code general}.
   */
  private String validateAiBody(Map<String, Object> body) {
    for (Map.Entry<String, Object> entry : body.entrySet()) {
      switch (entry.getKey()) {
        case "defaultModel" -> {
          if (!(entry.getValue() instanceof Map<?, ?> dm)) {
            return "\"defaultModel\" must be an object";
          }
          for (String field : List.of("providerId", "modelId", "tokenId")) {
            Object v = dm.get(field);
            if (v != null && (!(v instanceof String s) || s.length() > 128)) {
              return "\"defaultModel." + field + "\" must be a string of at most 128 characters";
            }
          }
        }
        case "about" -> {
          if (!(entry.getValue() instanceof String s) || s.length() > 2000) {
            return "\"about\" must be a string of at most 2000 characters";
          }
        }
        case "disabledTools" -> {
          if (!(entry.getValue() instanceof List<?> list)) {
            return "\"disabledTools\" must be an array";
          }
          if (list.size() > 200) {
            return "\"disabledTools\" must contain at most 200 entries";
          }
          for (Object v : list) {
            if (!(v instanceof String s) || !s.matches("[A-Za-z0-9_-]{1,128}")) {
              return "\"disabledTools\" entries must be tool names of at most 128 characters";
            }
          }
        }
        default -> {
          return "unknown key \""
              + entry.getKey()
              + "\" for scope \"ai\""
              + " (allowed: defaultModel, about, disabledTools)";
        }
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
}
