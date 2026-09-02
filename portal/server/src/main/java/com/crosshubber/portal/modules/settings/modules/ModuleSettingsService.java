package com.crosshubber.portal.modules.settings.modules;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Per-module settings store — mirrors {@code
 * portal/src/modules/module-settings/module-settings.repository.ts}.
 */
@Service
public class ModuleSettingsService {

  private static final Logger log = LoggerFactory.getLogger(ModuleSettingsService.class);

  private final ModuleSettingsRepository repo;
  private final ObjectMapper objectMapper;

  public ModuleSettingsService(ModuleSettingsRepository repo, ObjectMapper objectMapper) {
    this.repo = repo;
    this.objectMapper = objectMapper;
  }

  /** Stored settings for a module (empty map when absent). */
  @Transactional(readOnly = true)
  public Map<String, Object> get(String moduleKey) {
    return repo.findById(moduleKey)
        .map(e -> parseJson(e.getSettings()))
        .orElseGet(LinkedHashMap::new);
  }

  /** Read-merge-write update. */
  @Transactional
  public Map<String, Object> update(String moduleKey, Map<String, Object> partial) {
    Map<String, Object> merged = get(moduleKey);
    merged.putAll(partial);
    ModuleSettingsEntity entity =
        repo.findById(moduleKey)
            .orElseGet(
                () -> {
                  ModuleSettingsEntity created = new ModuleSettingsEntity();
                  created.setModuleKey(moduleKey);
                  return created;
                });
    entity.setSettings(writeJson(merged));
    repo.save(entity);
    log.info("[module-settings] updated {} ({} keys)", moduleKey, merged.size());
    return merged;
  }

  private Map<String, Object> parseJson(String raw) {
    try {
      if (raw == null || raw.isBlank()) {
        return new LinkedHashMap<>();
      }
      return objectMapper.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception e) {
      log.warn(
          "[module-settings] unparseable settings for {}: {}", moduleKeyOf(raw), e.getMessage());
      return new LinkedHashMap<>();
    }
  }

  private String moduleKeyOf(String raw) {
    return raw != null && raw.length() > 24 ? raw.substring(0, 24) + "…" : String.valueOf(raw);
  }

  private String writeJson(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("module settings serialization failed", e);
    }
  }
}
