package com.crosshubber.portal.modules.usersettings.scopes;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Per-user settings scopes — mirrors {@code
 * portal/src/modules/user-settings/user-settings.repository.ts}. Updates use an atomic JSONB merge
 * upsert so concurrent PUTs never lose keys.
 */
@Service
public class UserSettingsService {

  private static final Logger log = LoggerFactory.getLogger(UserSettingsService.class);

  private final UserSettingsRepository repo;
  private final ObjectMapper objectMapper;

  @PersistenceContext private EntityManager em;

  public UserSettingsService(UserSettingsRepository repo, ObjectMapper objectMapper) {
    this.repo = repo;
    this.objectMapper = objectMapper;
  }

  /** All scopes for the user: {@code {scope: settings}}. */
  @Transactional(readOnly = true)
  public Map<String, Map<String, Object>> getAll(String userId) {
    Map<String, Map<String, Object>> out = new LinkedHashMap<>();
    for (UserSettingsEntity entity : repo.findByUserId(userId)) {
      out.put(entity.getScope(), parseJson(entity.getSettings()));
    }
    return out;
  }

  /** Settings for one scope (empty map when absent). */
  @Transactional(readOnly = true)
  public Map<String, Object> get(String userId, String scope) {
    return repo.findByUserIdAndScope(userId, scope)
        .map(e -> parseJson(e.getSettings()))
        .orElseGet(LinkedHashMap::new);
  }

  /**
   * Atomic JSONB merge upsert ({@code settings || $json}) — mirrors the Node {@code ON CONFLICT DO
   * UPDATE SET settings = user_settings.settings || $3}.
   */
  @Transactional
  public Map<String, Object> update(String userId, String scope, Map<String, Object> partial) {
    String json = writeJson(partial);
    Object result =
        em.createNativeQuery(
                "INSERT INTO {h-schema}user_settings (user_id, scope, settings, updated_at) "
                    + "VALUES (:userId, :scope, CAST(:json AS jsonb), now()) "
                    + "ON CONFLICT (user_id, scope) "
                    + "DO UPDATE SET settings = {h-schema}user_settings.settings"
                    + " || CAST(:json AS jsonb), updated_at = now() "
                    + "RETURNING settings::text")
            .setParameter("userId", userId)
            .setParameter("scope", scope)
            .setParameter("json", json)
            .getSingleResult();
    String merged =
        result instanceof Object[] array ? String.valueOf(array[0]) : String.valueOf(result);
    log.info("[user-settings] updated scope={} for user={}", scope, userId);
    return parseJson(merged);
  }

  private Map<String, Object> parseJson(String raw) {
    try {
      if (raw == null || raw.isBlank()) {
        return new LinkedHashMap<>();
      }
      return objectMapper.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception e) {
      log.warn("[user-settings] unparseable stored settings: {}", e.getMessage());
      return new LinkedHashMap<>();
    }
  }

  private String writeJson(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("user settings serialization failed", e);
    }
  }
}
