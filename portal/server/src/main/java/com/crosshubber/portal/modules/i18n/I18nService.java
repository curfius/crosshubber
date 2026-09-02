package com.crosshubber.portal.modules.i18n;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.bootstrap.I18nCatalog;
import com.crosshubber.portal.modules.i18n.labels.I18nLabelEntity;
import com.crosshubber.portal.modules.i18n.labels.I18nLabelId;
import com.crosshubber.portal.modules.i18n.labels.I18nLabelRepository;
import com.crosshubber.portal.modules.i18n.languages.I18nLanguageEntity;
import com.crosshubber.portal.modules.i18n.languages.I18nLanguageRepository;
import com.crosshubber.portal.modules.i18n.settings.I18nSettingsEntity;
import com.crosshubber.portal.modules.i18n.settings.I18nSettingsRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * i18n domain service — mirrors {@code portal/src/modules/i18n/i18n.repository.ts}. Every write
 * bumps {@code content_version} so clients invalidate cached bundles.
 */
@Service
public class I18nService {

  private static final Logger log = LoggerFactory.getLogger(I18nService.class);

  private final I18nLanguageRepository languageRepo;
  private final I18nLabelRepository labelRepo;
  private final I18nSettingsRepository settingsRepo;
  private final ObjectMapper objectMapper;

  public I18nService(
      I18nLanguageRepository languageRepo,
      I18nLabelRepository labelRepo,
      I18nSettingsRepository settingsRepo,
      ObjectMapper objectMapper) {
    this.languageRepo = languageRepo;
    this.labelRepo = labelRepo;
    this.settingsRepo = settingsRepo;
    this.objectMapper = objectMapper;
  }

  // ── Reads ────────────────────────────────────────────────────────────

  @Transactional(readOnly = true)
  public I18nSettingsEntity getSettingsRow() {
    return settingsRepo
        .findById(1)
        .orElseGet(
            () -> {
              // Defensive: reconciler seeds at boot, but never fail reads.
              I18nSettingsEntity created = new I18nSettingsEntity();
              created.setId(1);
              created.setDefaultLanguage(I18nCatalog.DEFAULT_LANGUAGE);
              created.setFallbackLanguage(I18nCatalog.DEFAULT_LANGUAGE);
              created.setOverrides("{}");
              created.setContentVersion(1);
              return settingsRepo.save(created);
            });
  }

  @Transactional(readOnly = true)
  public List<I18nLanguageEntity> listLanguages() {
    return languageRepo.findAll(Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("code")));
  }

  @Transactional(readOnly = true)
  public I18nLanguageEntity findLanguage(String code) {
    return languageRepo.findById(code).orElse(null);
  }

  /** Mirrors i18nRepo.getConfig(). */
  @Transactional(readOnly = true)
  public Map<String, Object> getConfig() {
    I18nSettingsEntity settings = getSettingsRow();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("languages", listLanguages().stream().map(I18nService::languageDto).toList());
    out.put("defaultLanguage", settings.getDefaultLanguage());
    out.put("fallbackLanguage", settings.getFallbackLanguage());
    out.put("overrides", parseJson(settings.getOverrides()));
    out.put("contentVersion", settings.getContentVersion());
    return out;
  }

  /** Label bundle for a language; null when the language is unknown. */
  @Transactional(readOnly = true)
  public Map<String, String> getLabels(String code) {
    if (findLanguage(code) == null) {
      return null;
    }
    Map<String, String> labels = new LinkedHashMap<>();
    for (I18nLabelEntity label : labelRepo.findByLanguageCode(code)) {
      labels.put(label.getKey(), label.getValue());
    }
    return labels;
  }

  // ── Writes (all bump content_version) ────────────────────────────────

  @Transactional
  public Map<String, Object> updateSettings(
      String defaultLanguage, String fallbackLanguage, Map<String, Object> overrides) {
    I18nSettingsEntity settings = getSettingsRow();
    if (defaultLanguage != null) {
      settings.setDefaultLanguage(defaultLanguage);
    }
    if (fallbackLanguage != null) {
      settings.setFallbackLanguage(fallbackLanguage);
    }
    if (overrides != null) {
      settings.setOverrides(writeJson(overrides));
    }
    settingsRepo.save(settings);
    bumpContentVersion();
    return getConfig();
  }

  @Transactional
  public I18nLanguageEntity updateLanguage(
      String code, Boolean enabled, String name, String nativeName, Integer sortOrder) {
    I18nLanguageEntity language = languageRepo.findById(code).orElseThrow();
    if (enabled != null) {
      language.setEnabled(enabled);
    }
    if (name != null) {
      language.setName(name);
    }
    if (nativeName != null) {
      language.setNativeName(nativeName);
    }
    if (sortOrder != null) {
      language.setSortOrder(sortOrder);
    }
    languageRepo.save(language);
    bumpContentVersion();
    return language;
  }

  @Transactional
  public void upsertLabels(String code, List<Map.Entry<String, String>> entries, String updatedBy) {
    for (Map.Entry<String, String> entry : entries) {
      I18nLabelId id = new I18nLabelId(code, entry.getKey());
      I18nLabelEntity label =
          labelRepo
              .findById(id)
              .orElseGet(
                  () -> {
                    I18nLabelEntity created = new I18nLabelEntity();
                    created.setLanguageCode(code);
                    created.setKey(entry.getKey());
                    return created;
                  });
      label.setValue(entry.getValue());
      label.setUpdatedBy(updatedBy);
      labelRepo.save(label);
    }
    bumpContentVersion();
  }

  private void bumpContentVersion() {
    I18nSettingsEntity settings = getSettingsRow();
    settings.setContentVersion(settings.getContentVersion() + 1);
    settingsRepo.save(settings);
    log.info("[i18n] content_version bumped to {}", settings.getContentVersion());
  }

  // ── Helpers ──────────────────────────────────────────────────────────

  /** Language DTO — mirrors rowToLanguageConfig. */
  public static Map<String, Object> languageDto(I18nLanguageEntity language) {
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("code", language.getCode());
    dto.put("name", language.getName());
    dto.put("nativeName", language.getNativeName());
    dto.put("enabled", language.getEnabled());
    dto.put("seeded", language.getSeeded());
    dto.put("sortOrder", language.getSortOrder());
    return dto;
  }

  private Map<String, Object> parseJson(String raw) {
    try {
      if (raw == null || raw.isBlank()) {
        return new LinkedHashMap<>();
      }
      return objectMapper.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception e) {
      return new LinkedHashMap<>();
    }
  }

  private String writeJson(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("i18n overrides serialization failed", e);
    }
  }
}
