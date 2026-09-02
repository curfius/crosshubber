package com.crosshubber.portal.modules.i18n.labels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.i18n.I18nService;

/**
 * Label bundle reads/writes — mirrors the labels routes in i18n.routes.ts. Reads are public; writes
 * require {@code portal-i18n-edit} and bump content_version.
 */
@RestController
public class I18nLabelsController {

  private static final String LANG_CODE_RE = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$";
  private static final String I18N_KEY_RE = "^[a-z0-9]+(?:[.-][a-z0-9]+)+$";

  private final I18nService i18nService;

  public I18nLabelsController(I18nService i18nService) {
    this.i18nService = i18nService;
  }

  @GetMapping("/api/i18n/labels/{lang}")
  public ResponseEntity<?> labels(@PathVariable String lang) {
    if (!lang.matches(LANG_CODE_RE)) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid language code"));
    }
    Map<String, String> labels = i18nService.getLabels(lang);
    if (labels == null) {
      return ResponseEntity.status(404).body(Map.of("error", "unknown language"));
    }
    return ResponseEntity.ok(labelPayload(lang, labels));
  }

  @PutMapping("/api/i18n/labels/{lang}")
  @PreAuthorize("hasRole('portal-i18n-edit')")
  public ResponseEntity<?> upsert(
      @PathVariable String lang, @RequestBody Map<String, Object> body) {
    if (!lang.matches(LANG_CODE_RE)) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid language code"));
    }
    if (i18nService.findLanguage(lang) == null) {
      return ResponseEntity.status(404).body(Map.of("error", "unknown language"));
    }
    if (body == null || !(body.get("entries") instanceof List<?> rawEntries)) {
      return ResponseEntity.badRequest().body(Map.of("error", "entries array is required"));
    }
    List<Map.Entry<String, String>> entries = new ArrayList<>();
    for (Object item : rawEntries) {
      if (!(item instanceof Map<?, ?> entry)
          || !(entry.get("key") instanceof String key)
          || !(entry.get("value") instanceof String value)) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "each entry must be { key: string, value: string }"));
      }
      if (!key.matches(I18N_KEY_RE)) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "invalid label key \"" + key + "\""));
      }
      entries.add(Map.entry(key, value));
    }
    i18nService.upsertLabels(lang, entries, currentUserSub());
    Map<String, String> labels = i18nService.getLabels(lang);
    return ResponseEntity.ok(labelPayload(lang, labels));
  }

  private Map<String, Object> labelPayload(String lang, Map<String, String> labels) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("language", lang);
    payload.put("version", i18nService.getSettingsRow().getContentVersion());
    payload.put("labels", labels);
    return payload;
  }

  private static String currentUserSub() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null
        && auth.getPrincipal() instanceof com.crosshubber.portal.security.PortalUser user) {
      return user.sub();
    }
    return "unknown";
  }
}
