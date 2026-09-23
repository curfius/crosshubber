package com.crosshubber.portal.modules.i18n.labels;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.common.Keys;
import com.crosshubber.portal.common.SecurityUtils;
import com.crosshubber.portal.modules.i18n.I18nService;
import com.crosshubber.portal.modules.i18n.dto.LabelBundleDto;
import com.crosshubber.portal.modules.i18n.dto.UpsertLabelsRequest;

import jakarta.validation.Valid;

/**
 * Label bundle reads/writes. Reads are public; writes require {@code portal-i18n-edit} and bump
 * content_version.
 */
@RestController
public class I18nLabelsController {

  private final I18nService i18nService;

  public I18nLabelsController(I18nService i18nService) {
    this.i18nService = i18nService;
  }

  @GetMapping("/api/i18n/labels/{lang}")
  public ResponseEntity<?> labels(@PathVariable String lang) {
    if (!lang.matches(Keys.LANG_CODE_RE)) {
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
      @PathVariable String lang, @Valid @RequestBody UpsertLabelsRequest body) {
    if (!lang.matches(Keys.LANG_CODE_RE)) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid language code"));
    }
    if (i18nService.findLanguage(lang) == null) {
      return ResponseEntity.status(404).body(Map.of("error", "unknown language"));
    }
    List<Map.Entry<String, String>> entries =
        body.entries().stream().map(e -> Map.entry(e.key(), e.value())).toList();
    i18nService.upsertLabels(lang, entries, SecurityUtils.currentUserSub());
    Map<String, String> labels = i18nService.getLabels(lang);
    return ResponseEntity.ok(labelPayload(lang, labels));
  }

  private LabelBundleDto labelPayload(String lang, Map<String, String> labels) {
    return new LabelBundleDto(lang, i18nService.getSettingsRow().getContentVersion(), labels);
  }
}
