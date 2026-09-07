package com.crosshubber.portal.modules.aihub.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared JSON utilities for the AI Hub module. */
public final class JsonUtils {

  private JsonUtils() {}

  public static Map<String, Object> parseMap(ObjectMapper mapper, String raw) {
    if (raw == null || raw.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      return mapper.readValue(raw, new TypeReference<>() {});
    } catch (Exception e) {
      return new LinkedHashMap<>();
    }
  }

  public static List<Map<String, Object>> parseList(ObjectMapper mapper, String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return mapper.readValue(json, new TypeReference<>() {});
    } catch (Exception e) {
      return List.of();
    }
  }

  public static String writeJson(ObjectMapper mapper, Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("JSON serialization failed", e);
    }
  }

  public static String firstEnabledModel(ObjectMapper mapper, String modelsJson) {
    try {
      JsonNode root = mapper.readTree(modelsJson == null ? "[]" : modelsJson);
      for (JsonNode model : root) {
        if (model.path("enabled").asBoolean(false) && !model.path("id").asText().isEmpty()) {
          return model.path("id").asText();
        }
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }
}
