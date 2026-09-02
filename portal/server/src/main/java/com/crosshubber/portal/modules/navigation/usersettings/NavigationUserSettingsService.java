package com.crosshubber.portal.modules.navigation.usersettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.modules.navigation.NavigationValidationService;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointEntity;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Per-user navigation settings (sidebar) — mirrors the user-settings part of {@code
 * portal/src/modules/navigation/navigation.repository.ts} + mergeUserSettings in
 * navigation.routes.ts.
 */
@Service
public class NavigationUserSettingsService {

  private final NavigationUserSettingsRepository repo;
  private final EntryPointRepository entryPointRepo;
  private final ObjectMapper objectMapper;

  public NavigationUserSettingsService(
      NavigationUserSettingsRepository repo,
      EntryPointRepository entryPointRepo,
      ObjectMapper objectMapper) {
    this.repo = repo;
    this.entryPointRepo = entryPointRepo;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getUserSettingsRaw(String userId) {
    return repo.findById(userId).map(e -> parseJson(e.getSettings())).orElse(null);
  }

  /** Defaults computed from entry points (D12), merged with stored settings. */
  @Transactional(readOnly = true)
  public Map<String, Object> mergedSettings(String userId) {
    List<EntryPointEntity> entryPoints = entryPointRepo.findAll();
    return mergeUserSettings(entryPoints, getUserSettingsRaw(userId));
  }

  public Map<String, Object> mergeUserSettings(
      List<EntryPointEntity> entryPoints, Map<String, Object> stored) {
    Map<String, Object> defaults = defaultUserSettings(entryPoints);
    if (stored == null) {
      return defaults;
    }
    Map<String, Object> defaultsSidebar = castMap(defaults.get("sidebar"));
    Map<String, Object> storedSidebar =
        stored.get("sidebar") instanceof Map<?, ?> m ? castMap(m) : new LinkedHashMap<>();

    Map<String, Object> sidebar = new LinkedHashMap<>(defaultsSidebar);
    sidebar.putAll(storedSidebar);
    Object apps = storedSidebar.get("apps");
    if (!(apps instanceof List<?>)) {
      sidebar.put("apps", defaultsSidebar.get("apps"));
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
    // Explicit null for a known key is rejected (zod parity), not silently ignored.
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
      JsonNode apps = sidebar.get("apps");
      if (apps != null && apps.isNull()) {
        return "sidebar.apps: Invalid input: expected array, received null";
      }
      if (apps != null) {
        if (!apps.isArray() || apps.size() > 500) {
          return "sidebar.apps: must be an array of at most 500 strings";
        }
        for (JsonNode app : apps) {
          if (!app.isTextual() || app.asText().length() > 255) {
            return "sidebar.apps: must be an array of at most 500 strings";
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
        if (!item.isTextual() || item.asText().length() > 255) {
          return "sidebarExpanded: must be an array of at most 500 strings";
        }
      }
    }
    return null;
  }

  /** Merges the validated partial over the current effective settings. */
  @Transactional
  public Map<String, Object> applyPartial(String userId, JsonNode partial) {
    List<EntryPointEntity> entryPoints = entryPointRepo.findAll();
    Map<String, Object> current = mergeUserSettings(entryPoints, getUserSettingsRaw(userId));
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
      if (sidebar.hasNonNull("apps")) {
        List<String> apps = new ArrayList<>();
        sidebar.get("apps").forEach(a -> apps.add(a.asText()));
        nextSidebar.put("apps", apps);
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

  /** Default sidebar settings — every visible app ref ordered by sortOrder. */
  public Map<String, Object> defaultUserSettings(List<EntryPointEntity> entryPoints) {
    Map<String, Object> sidebar = new LinkedHashMap<>();
    sidebar.put("showPinned", true);
    sidebar.put("showWorkspaces", true);
    sidebar.put("apps", NavigationValidationService.buildDefaultSidebarApps(entryPoints));
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
      throw new IllegalStateException("navigation user settings serialization failed", e);
    }
  }
}
