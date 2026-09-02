package com.crosshubber.portal.modules.i18n.languages;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.i18n.I18nService;

/**
 * PUT /api/i18n/languages/{code} — mirrors the language route in i18n.routes.ts. The
 * default/fallback language cannot be disabled.
 */
@RestController
public class I18nLanguagesController {

  // (?i) mirrors Node's case-insensitive LANG_CODE_RE (i18n.routes.ts:9) — an uppercase code
  // passes the regex and then falls through to the 404 "unknown language" path (codes are
  // stored lowercase).
  private static final String LANG_CODE_RE = "(?i)^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$";

  private final I18nService i18nService;

  public I18nLanguagesController(I18nService i18nService) {
    this.i18nService = i18nService;
  }

  @PutMapping("/api/i18n/languages/{code}")
  @PreAuthorize("hasRole('portal-i18n-edit')")
  public ResponseEntity<?> update(
      @PathVariable String code, @RequestBody Map<String, Object> body) {
    if (!code.matches(LANG_CODE_RE)) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid language code"));
    }
    I18nLanguageEntity existing = i18nService.findLanguage(code);
    if (existing == null) {
      return ResponseEntity.status(404).body(Map.of("error", "unknown language"));
    }
    if (body == null) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid request body"));
    }

    Boolean enabled = null;
    String name = null;
    String nativeName = null;
    Integer sortOrder = null;

    if (body.containsKey("enabled")) {
      if (!(body.get("enabled") instanceof Boolean b)) {
        return ResponseEntity.badRequest().body(Map.of("error", "enabled must be a boolean"));
      }
      if (!b) {
        var settings = i18nService.getSettingsRow();
        if (code.equals(settings.getDefaultLanguage())) {
          return ResponseEntity.badRequest()
              .body(Map.of("error", "the default language cannot be disabled"));
        }
        if (code.equals(settings.getFallbackLanguage())) {
          return ResponseEntity.badRequest()
              .body(Map.of("error", "the fallback language cannot be disabled"));
        }
      }
      enabled = b;
    }
    if (body.containsKey("name")) {
      if (!(body.get("name") instanceof String s) || s.isBlank()) {
        return ResponseEntity.badRequest().body(Map.of("error", "name must be a non-empty string"));
      }
      name = s.trim();
    }
    if (body.containsKey("nativeName")) {
      if (!(body.get("nativeName") instanceof String s) || s.isBlank()) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "nativeName must be a non-empty string"));
      }
      nativeName = s.trim();
    }
    if (body.containsKey("sortOrder")) {
      if (!(body.get("sortOrder") instanceof Number n) || n.intValue() != n.doubleValue()) {
        return ResponseEntity.badRequest().body(Map.of("error", "sortOrder must be an integer"));
      }
      sortOrder = n.intValue();
    }

    I18nLanguageEntity updated =
        i18nService.updateLanguage(code, enabled, name, nativeName, sortOrder);
    return ResponseEntity.ok(I18nService.languageDto(updated));
  }
}
