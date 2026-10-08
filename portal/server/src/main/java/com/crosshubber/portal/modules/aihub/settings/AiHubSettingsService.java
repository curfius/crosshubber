package com.crosshubber.portal.modules.aihub.settings;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.common.JsonUtils;

/**
 * AI Hub settings store — a single-row JSONB document in {@code ai_hub_settings} (migrated out of
 * the generic {@code module_settings} table, which remains in use by other modules). Holds the chat
 * model selection, generation parameters and quick-chat UX keys.
 */
@Service
public class AiHubSettingsService {

  private static final Logger log = LoggerFactory.getLogger(AiHubSettingsService.class);

  public static final String ROW_ID = "default";

  private final AiHubSettingsRepository repo;
  private final JsonUtils jsonUtils;

  public AiHubSettingsService(AiHubSettingsRepository repo, JsonUtils jsonUtils) {
    this.repo = repo;
    this.jsonUtils = jsonUtils;
  }

  /** Stored settings for the AI Hub (empty map when absent). */
  @Transactional(readOnly = true)
  public Map<String, Object> get() {
    return repo.findById(ROW_ID)
        .map(e -> jsonUtils.parseMap(e.getSettings()))
        .orElseGet(LinkedHashMap::new);
  }

  /** Read-merge-write update; returns the merged document. */
  @Transactional
  public Map<String, Object> update(Map<String, Object> partial) {
    Map<String, Object> merged = get();
    merged.putAll(partial);
    AiHubSettingsEntity entity =
        repo.findById(ROW_ID)
            .orElseGet(
                () -> {
                  AiHubSettingsEntity created = new AiHubSettingsEntity();
                  created.setId(ROW_ID);
                  return created;
                });
    entity.setSettings(jsonUtils.write(merged));
    repo.save(entity);
    log.info("[ai-hub] settings updated ({} keys)", merged.size());
    return merged;
  }
}
