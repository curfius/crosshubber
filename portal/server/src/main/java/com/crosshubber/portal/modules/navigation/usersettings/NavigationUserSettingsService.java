package com.crosshubber.portal.modules.navigation.usersettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.common.Keys;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-user navigation settings (sidebar) — read/write the user-settings document with merge-on-save
 * semantics.
 */
@Service
public class NavigationUserSettingsService {

  private final NavigationUserSettingsRepository repo;
  private final ModuleContentRepository contentRepo;
  private final ObjectMapper objectMapper;
  private final JsonUtils jsonUtils;

  public NavigationUserSettingsService(
      NavigationUserSettingsRepository repo,
      ModuleContentRepository contentRepo,
      ObjectMapper objectMapper,
      JsonUtils jsonUtils) {
    this.repo = repo;
    this.contentRepo = contentRepo;
    this.objectMapper = objectMapper;
    this.jsonUtils = jsonUtils;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getUserSettingsRaw(String userId) {
    return repo.findById(userId).map(e -> parseJson(e.getSettings())).orElse(null);
  }

  /** Defaults computed from module content (D12), merged with stored settings. */
  @Transactional(readOnly = true)
  public Map<String, Object> mergedSettings(String userId) {
    List<ModuleContentEntity> moduleContents = contentRepo.findAll();
    return mergeUserSettings(moduleContents, getUserSettingsRaw(userId));
  }

  public Map<String, Object> mergeUserSettings(
      List<ModuleContentEntity> moduleContents, Map<String, Object> stored) {
    Map<String, Object> defaults = defaultUserSettings();
    if (stored == null) {
      return defaults;
    }
    Map<String, Object> defaultsSidebar = castMap(defaults.get("sidebar"));
    Map<String, Object> storedSidebar =
        stored.get("sidebar") instanceof Map<?, ?> m ? castMap(m) : new LinkedHashMap<>();

    Map<String, Object> sidebar = new LinkedHashMap<>(defaultsSidebar);
    // Explicit nulls in the stored doc fall back to the default instead of persisting.
    storedSidebar.entrySet().removeIf(e -> e.getValue() == null);
    sidebar.putAll(storedSidebar);
    if (!(sidebar.get("showRecent") instanceof Boolean)) {
      sidebar.put("showRecent", defaultsSidebar.get("showRecent"));
    }
    if (!(sidebar.get("recentLimit") instanceof Integer i) || i < 1 || i > 50) {
      sidebar.put("recentLimit", defaultsSidebar.get("recentLimit"));
    }
    if (!(sidebar.get("recentApps") instanceof List<?>)) {
      sidebar.put("recentApps", defaultsSidebar.get("recentApps"));
    }
    Object expanded = stored.get("sidebarExpanded");
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("sidebar", sidebar);
    out.put(
        "sidebarExpanded",
        expanded instanceof List<?> ? expanded : defaults.get("sidebarExpanded"));
    return out;
  }

  @Transactional
  public void updateUserSettings(String userId, Map<String, Object> settings) {
    NavigationUserSettingsEntity entity =
        repo.findById(userId)
            .orElseGet(
                () -> {
                  NavigationUserSettingsEntity created = new NavigationUserSettingsEntity();
                  created.setUserId(userId);
                  return created;
                });
    entity.setSettings(writeJson(settings));
    repo.save(entity);
  }

  /** Validates the partial PUT body — returns error message or null. */
  public String validatePartial(JsonNode body) {
    if (body == null || !body.isObject()) {
      return "body must be an object";
    }
    // Explicit null for a known key is rejected, not silently ignored.
    JsonNode sidebar = body.get("sidebar");
    if (sidebar != null && sidebar.isNull()) {
      return "sidebar: Invalid input: expected object, received null";
    }
    if (sidebar != null) {
      if (!sidebar.isObject()) {
        return "sidebar: must be an object";
      }
      JsonNode showPinned = sidebar.get("showPinned");
      if (showPinned != null && showPinned.isNull()) {
        return "sidebar.showPinned: Invalid input: expected boolean, received null";
      }
      if (showPinned != null && !showPinned.isBoolean()) {
        return "sidebar.showPinned: must be a boolean";
      }
      JsonNode showWorkspaces = sidebar.get("showWorkspaces");
      if (showWorkspaces != null && showWorkspaces.isNull()) {
        return "sidebar.showWorkspaces: Invalid input: expected boolean, received null";
      }
      if (showWorkspaces != null && !showWorkspaces.isBoolean()) {
        return "sidebar.showWorkspaces: must be a boolean";
      }
      JsonNode showRecent = sidebar.get("showRecent");
      if (showRecent != null && showRecent.isNull()) {
        return "sidebar.showRecent: Invalid input: expected boolean, received null";
      }
      if (showRecent != null && !showRecent.isBoolean()) {
        return "sidebar.showRecent: must be a boolean";
      }
      JsonNode recentLimit = sidebar.get("recentLimit");
      if (recentLimit != null && recentLimit.isNull()) {
        return "sidebar.recentLimit: Invalid input: expected integer, received null";
      }
      if (recentLimit != null
          && (!recentLimit.isNumber() || recentLimit.asInt(0) < 1 || recentLimit.asInt(0) > 50)) {
        return "sidebar.recentLimit: must be an integer between 1 and 50";
      }
      JsonNode recentApps = sidebar.get("recentApps");
      if (recentApps != null && recentApps.isNull()) {
        return "sidebar.recentApps: Invalid input: expected array, received null";
      }
      if (recentApps != null) {
        if (!recentApps.isArray() || recentApps.size() > 100) {
          return "sidebar.recentApps: must be an array of at most 100 refs";
        }
        for (JsonNode ref : recentApps) {
          if (!ref.isString()
              || ref.asString().length() > 255
              || !ref.asString().matches(Keys.REF_RE)) {
            return "sidebar.recentApps: must be an array of \"moduleKey:contentKey\" strings";
          }
        }
      }
    }
    JsonNode expanded = body.get("sidebarExpanded");
    if (expanded != null && expanded.isNull()) {
      return "sidebarExpanded: Invalid input: expected array, received null";
    }
    if (expanded != null) {
      if (!expanded.isArray() || expanded.size() > 500) {
        return "sidebarExpanded: must be an array of at most 500 strings";
      }
      for (JsonNode item : expanded) {
        if (!item.isString() || item.asString().length() > 255) {
          return "sidebarExpanded: must be an array of at most 500 strings";
        }
      }
    }
    return null;
  }

  /** Merges the validated partial over the current effective settings. */
  @Transactional
  public Map<String, Object> applyPartial(String userId, JsonNode partial) {
    List<ModuleContentEntity> moduleContents = contentRepo.findAll();
    Map<String, Object> current = mergeUserSettings(moduleContents, getUserSettingsRaw(userId));
    Map<String, Object> currentSidebar = castMap(current.get("sidebar"));

    Map<String, Object> next = new LinkedHashMap<>();
    Map<String, Object> nextSidebar = new LinkedHashMap<>(currentSidebar);
    JsonNode sidebar = partial.get("sidebar");
    if (sidebar != null && !sidebar.isNull()) {
      if (sidebar.hasNonNull("showPinned")) {
        nextSidebar.put("showPinned", sidebar.get("showPinned").asBoolean());
      }
      if (sidebar.hasNonNull("showWorkspaces")) {
        nextSidebar.put("showWorkspaces", sidebar.get("showWorkspaces").asBoolean());
      }
      if (sidebar.hasNonNull("showRecent")) {
        nextSidebar.put("showRecent", sidebar.get("showRecent").asBoolean());
      }
      if (sidebar.hasNonNull("recentLimit")) {
        nextSidebar.put("recentLimit", sidebar.get("recentLimit").asInt(10));
      }
      if (sidebar.hasNonNull("recentApps")) {
        List<String> recentApps = new ArrayList<>();
        sidebar.get("recentApps").forEach(a -> recentApps.add(a.asString()));
        nextSidebar.put("recentApps", recentApps);
      }
    }
    next.put("sidebar", nextSidebar);
    JsonNode expanded = partial.get("sidebarExpanded");
    next.put(
        "sidebarExpanded",
        expanded != null && !expanded.isNull()
            ? objectMapper.convertValue(expanded, List.class)
            : current.get("sidebarExpanded"));
    updateUserSettings(userId, next);
    return next;
  }

  /** Default sidebar settings: all blocks on, last-10 recently-used tracking. */
  public Map<String, Object> defaultUserSettings() {
    Map<String, Object> sidebar = new LinkedHashMap<>();
    sidebar.put("showPinned", true);
    sidebar.put("showWorkspaces", true);
    sidebar.put("showRecent", true);
    sidebar.put("recentLimit", 10);
    sidebar.put("recentApps", new ArrayList<String>());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("sidebar", sidebar);
    out.put("sidebarExpanded", new ArrayList<String>());
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object value) {
    return value instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
  }

  private Map<String, Object> parseJson(String raw) {
    return jsonUtils.parseMap(raw);
  }

  private String writeJson(Map<String, Object> value) {
    return jsonUtils.write(value);
  }
}
