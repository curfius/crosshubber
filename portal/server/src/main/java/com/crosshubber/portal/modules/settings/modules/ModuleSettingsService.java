package com.crosshubber.portal.modules.settings.modules;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.common.JsonUtils;

/** Per-module settings store. */
@Service
public class ModuleSettingsService {

  private static final Logger log = LoggerFactory.getLogger(ModuleSettingsService.class);

  private final ModuleSettingsRepository repo;
  private final JsonUtils jsonUtils;

  public ModuleSettingsService(ModuleSettingsRepository repo, JsonUtils jsonUtils) {
    this.repo = repo;
    this.jsonUtils = jsonUtils;
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
    return jsonUtils.parseMap(raw);
  }

  private String writeJson(Map<String, Object> value) {
    return jsonUtils.write(value);
  }
}
