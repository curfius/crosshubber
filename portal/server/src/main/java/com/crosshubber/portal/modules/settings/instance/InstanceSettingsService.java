package com.crosshubber.portal.modules.settings.instance;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.common.JsonUtils;

/**
 * Instance settings (singleton row id=1): defaults merged under the stored JSONB, partial updates
 * merge on top.
 */
@Service
public class InstanceSettingsService {

  private static final Logger log = LoggerFactory.getLogger(InstanceSettingsService.class);

  /** Mirrors DEFAULT_SETTINGS in settings.repository.ts. */
  static final Map<String, Object> DEFAULT_SETTINGS;

  static {
    DEFAULT_SETTINGS = new LinkedHashMap<>();
    DEFAULT_SETTINGS.put("homeApp", "portal-navigation:portal");
    DEFAULT_SETTINGS.put("pinnedAppsEnabled", true);
    DEFAULT_SETTINGS.put("workspacesEnabled", true);
  }

  private final InstanceSettingsRepository repo;
  private final JsonUtils jsonUtils;

  public InstanceSettingsService(InstanceSettingsRepository repo, JsonUtils jsonUtils) {
    this.repo = repo;
    this.jsonUtils = jsonUtils;
  }

  /** Effective settings: stored JSONB merged over defaults. */
  @Transactional
  public Map<String, Object> get() {
    InstanceSettingsEntity entity = repo.findById(1).orElse(null);
    if (entity == null) {
      InstanceSettingsEntity created = new InstanceSettingsEntity();
      created.setId(1);
      created.setSettings(writeJson(DEFAULT_SETTINGS));
      repo.save(created);
      return new LinkedHashMap<>(DEFAULT_SETTINGS);
    }
    Map<String, Object> merged = new LinkedHashMap<>(DEFAULT_SETTINGS);
    merged.putAll(parseJson(entity.getSettings()));
    return merged;
  }

  /** Merges the given partial over current settings and persists. */
  @Transactional
  public Map<String, Object> update(Map<String, Object> partial) {
    Map<String, Object> merged = get();
    merged.putAll(partial);
    InstanceSettingsEntity entity =
        repo.findById(1)
            .orElseGet(
                () -> {
                  InstanceSettingsEntity created = new InstanceSettingsEntity();
                  created.setId(1);
                  return created;
                });
    entity.setSettings(writeJson(merged));
    repo.save(entity);
    log.info("[settings] updated instance settings: {}", merged);
    return merged;
  }

  private Map<String, Object> parseJson(String raw) {
    return jsonUtils.parseMap(raw);
  }

  private String writeJson(Map<String, Object> value) {
    return jsonUtils.write(value);
  }
}
