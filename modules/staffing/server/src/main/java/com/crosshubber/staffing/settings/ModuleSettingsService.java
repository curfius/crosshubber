package com.crosshubber.staffing.settings;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.staffing.agent.AgentToolsController;

import tools.jackson.databind.ObjectMapper;

/**
 * Module-owned settings storage (DB-backed, JSONB groups): per-tool toggles and matching params.
 * Consumers: the module settings UI (REST) and {@code AgentToolsController} (tool gating). The
 * single source of truth for tool names is the tools controller.
 */
@Service
public class ModuleSettingsService {

  public static final String GROUP_TOOLS = "tools";
  public static final String GROUP_MATCH = "match";

  public static final int DEFAULT_TOP_N = 5;

  public record ToolSettings(Map<String, Boolean> enabled) {}

  public record MatchSettings(Integer topN) {}

  public record SettingsView(Map<String, Boolean> tools, MatchSettings match) {}

  public record SettingsUpdateRequest(Map<String, Boolean> tools, MatchUpdate match) {}

  public record MatchUpdate(Integer topN) {}

  private final ModuleSettingRepository repo;
  private final ObjectMapper objectMapper;

  public ModuleSettingsService(ModuleSettingRepository repo, ObjectMapper objectMapper) {
    this.repo = repo;
    this.objectMapper = objectMapper;
  }

  // ── Reads ────────────────────────────────────────────────────────────

  public ToolSettings tools() {
    return readGroup(GROUP_TOOLS, ToolSettings.class, new ToolSettings(Map.of()));
  }

  public MatchSettings match() {
    return readGroup(GROUP_MATCH, MatchSettings.class, new MatchSettings(DEFAULT_TOP_N));
  }

  public boolean isToolEnabled(String toolName) {
    Boolean enabled = tools().enabled().get(toolName);
    return enabled == null || enabled;
  }

  public int matchTopN() {
    Integer topN = match().topN();
    return topN == null ? DEFAULT_TOP_N : topN;
  }

  public SettingsView view() {
    Map<String, Boolean> tools = new LinkedHashMap<>();
    for (String name : AgentToolsController.toolNames()) {
      tools.put(name, isToolEnabled(name));
    }
    return new SettingsView(tools, new MatchSettings(matchTopN()));
  }

  // ── Writes ───────────────────────────────────────────────────────────

  /** Validates the update; returns an error message or null. */
  public String validate(SettingsUpdateRequest request) {
    if (request == null) {
      return "body is required";
    }
    if (request.tools() != null) {
      for (Map.Entry<String, Boolean> entry : request.tools().entrySet()) {
        if (!AgentToolsController.toolNames().contains(entry.getKey())) {
          return "tools: unknown tool \"" + entry.getKey() + "\"";
        }
        if (entry.getValue() == null) {
          return "tools." + entry.getKey() + ": boolean required";
        }
      }
    }
    if (request.match() != null) {
      Integer topN = request.match().topN();
      if (topN != null && (topN < 1 || topN > 50)) {
        return "match.topN: must be 1-50";
      }
    }
    return null;
  }

  @Transactional
  public SettingsView update(SettingsUpdateRequest request) {
    if (request.tools() != null) {
      Map<String, Boolean> merged = new LinkedHashMap<>(tools().enabled());
      merged.putAll(request.tools());
      writeGroup(GROUP_TOOLS, new ToolSettings(merged));
    }
    if (request.match() != null) {
      MatchSettings current = match();
      writeGroup(
          GROUP_MATCH,
          new MatchSettings(
              request.match().topN() != null ? request.match().topN() : current.topN()));
    }
    return view();
  }

  // ── Internals ────────────────────────────────────────────────────────

  private <T> T readGroup(String key, Class<T> type, T fallback) {
    Optional<ModuleSettingEntity> row = repo.findByKey(key);
    if (row.isEmpty()) {
      return fallback;
    }
    try {
      return objectMapper.readValue(row.get().getValue(), type);
    } catch (Exception e) {
      return fallback;
    }
  }

  private <T> void writeGroup(String key, T value) {
    ModuleSettingEntity entity =
        repo.findByKey(key)
            .orElseGet(
                () -> {
                  ModuleSettingEntity fresh = new ModuleSettingEntity();
                  fresh.setKey(key);
                  return fresh;
                });
    entity.setValue(objectMapper.writeValueAsString(value));
    entity.setUpdatedAt(Instant.now());
    repo.save(entity);
  }
}
