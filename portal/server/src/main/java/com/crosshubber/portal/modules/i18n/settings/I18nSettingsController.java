package com.crosshubber.portal.modules.i18n.settings;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.i18n.I18nService;

/**
 * PUT /api/i18n/settings — default/fallback language + locale overrides; mirrors the settings route
 * in i18n.routes.ts.
 */
@RestController
public class I18nSettingsController {

  // (?i) mirrors Node's case-insensitive LANG_CODE_RE (i18n.routes.ts:9).
  private static final String LANG_CODE_RE = "(?i)^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$";

  private final I18nService i18nService;

  public I18nSettingsController(I18nService i18nService) {
    this.i18nService = i18nService;
  }

  @PutMapping("/api/i18n/settings")
  @PreAuthorize("hasRole('portal-i18n-edit')")
  public ResponseEntity<?> update(@RequestBody Map<String, Object> body) {
    if (body == null || body instanceof java.util.Collection) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid request body"));
    }
    String defaultLanguage = null;
    String fallbackLanguage = null;
    Map<String, Object> overrides = null;

    if (body.containsKey("defaultLanguage")) {
      if (!(body.get("defaultLanguage") instanceof String s) || !s.matches(LANG_CODE_RE)) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "defaultLanguage must be a valid language code"));
      }
      var language = i18nService.findLanguage(s);
      if (language == null || !Boolean.TRUE.equals(language.getEnabled())) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "defaultLanguage must be an enabled language"));
      }
      defaultLanguage = s;
    }
    if (body.containsKey("fallbackLanguage")) {
      if (!(body.get("fallbackLanguage") instanceof String s) || !s.matches(LANG_CODE_RE)) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "fallbackLanguage must be a valid language code"));
      }
      var language = i18nService.findLanguage(s);
      if (language == null || !Boolean.TRUE.equals(language.getEnabled())) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "fallbackLanguage must be an enabled language"));
      }
      fallbackLanguage = s;
    }
    if (body.containsKey("overrides")) {
      Object raw = body.get("overrides");
      if (!(raw instanceof Map<?, ?> rawMap)) {
        return ResponseEntity.badRequest().body(Map.of("error", "overrides must be an object"));
      }
      overrides = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
        String key = String.valueOf(entry.getKey());
        Object value = entry.getValue();
        if ("timezone".equals(key)) {
          if (value != null && !"".equals(value)) {
            if (!(value instanceof String tz)) {
              return ResponseEntity.badRequest()
                  .body(Map.of("error", "overrides.timezone must be a string"));
            }
            overrides.put("timezone", tz);
          }
        } else if ("firstDayOfWeek".equals(key)) {
          if (value != null) {
            if (!(value instanceof Number n)
                || n.intValue() != n.doubleValue()
                || n.intValue() < 0
                || n.intValue() > 6) {
              return ResponseEntity.badRequest()
                  .body(
                      Map.of(
                          "error", "overrides.firstDayOfWeek must be an integer between 0 and 6"));
            }
            overrides.put("firstDayOfWeek", n.intValue());
          }
        }
        // Unknown override keys are ignored (mirrors the Node allow-list)
      }
    }

    return ResponseEntity.ok(
        i18nService.updateSettings(defaultLanguage, fallbackLanguage, overrides));
  }
}
