package com.crosshubber.portal.modules.navigation.settings;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.common.JsonUtils;

/**
 * Navigation settings (singleton row id=1): defaults merged under the stored JSONB, partial updates
 * merge on top.
 */
@Service
public class NavigationSettingsService {

  private static final Logger log = LoggerFactory.getLogger(NavigationSettingsService.class);

  /** Mirrors DEFAULT_SETTINGS in settings.repository.ts. */
  static final Map<String, Object> DEFAULT_SETTINGS;

  static {
    DEFAULT_SETTINGS = new LinkedHashMap<>();
    DEFAULT_SETTINGS.put("homeApp", "portal-navigation:portal");
    DEFAULT_SETTINGS.put("pinnedAppsEnabled", true);
    DEFAULT_SETTINGS.put("workspacesEnabled", true);
  }

  private final NavigationSettingsRepository repo;
  private final JsonUtils jsonUtils;

  public NavigationSettingsService(NavigationSettingsRepository repo, JsonUtils jsonUtils) {
    this.repo = repo;
    this.jsonUtils = jsonUtils;
  }

  /** Effective settings: stored JSONB merged over defaults. */
  @Transactional
  public Map<String, Object> get() {
    NavigationSettingsEntity entity = repo.findById(1).orElse(null);
    if (entity == null) {
      NavigationSettingsEntity created = new NavigationSettingsEntity();
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
    NavigationSettingsEntity entity =
        repo.findById(1)
            .orElseGet(
                () -> {
                  NavigationSettingsEntity created = new NavigationSettingsEntity();
                  created.setId(1);
                  return created;
                });
    entity.setSettings(writeJson(merged));
    repo.save(entity);
    log.info("[settings] updated navigation settings: {}", merged);
    return merged;
  }

  private Map<String, Object> parseJson(String raw) {
    return jsonUtils.parseMap(raw);
  }

  private String writeJson(Map<String, Object> value) {
    return jsonUtils.write(value);
  }
}
