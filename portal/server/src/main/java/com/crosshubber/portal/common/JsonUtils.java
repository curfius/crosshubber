package com.crosshubber.portal.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared JSON helper — replaces the per-service {@code parseJson}/{@code writeJson} copies.
 *
 * <p>Lenient readers fall back to empty containers (stored JSON columns are trusted-but-legacy
 * data); {@link #write} is strict because serialization failures are programming errors.
 */
@Component
public class JsonUtils {

  private static final Logger log = LoggerFactory.getLogger(JsonUtils.class);

  private final ObjectMapper mapper;

  public JsonUtils(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** Mutable {@link LinkedHashMap}; null/blank input or invalid JSON yields an empty map. */
  public Map<String, Object> parseMap(String raw) {
    if (raw == null || raw.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      return mapper.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception e) {
      log.warn("[json] parseMap failed: {}", e.getMessage());
      return new LinkedHashMap<>();
    }
  }

  /** Mutable {@link LinkedHashMap}; null/blank input or invalid JSON yields {@code null}. */
  public Map<String, Object> parseMapOrNull(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return mapper.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception e) {
      log.warn("[json] parseMapOrNull failed: {}", e.getMessage());
      return null;
    }
  }

  /** List of maps; null/blank input or invalid JSON yields an empty list. */
  public List<Map<String, Object>> parseList(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    try {
      return mapper.readValue(raw, new TypeReference<>() {});
    } catch (Exception e) {
      log.warn("[json] parseList failed: {}", e.getMessage());
      return List.of();
    }
  }

  /** Raw {@link JsonNode} passthrough; null/blank input or invalid JSON yields {@code null}. */
  public JsonNode parseTreeOrNull(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return mapper.readTree(raw);
    } catch (Exception e) {
      log.warn("[json] parseTree failed: {}", e.getMessage());
      return null;
    }
  }

  /** Strict serialization — throws unchecked {@code JacksonException} on failure. */
  public String write(Object value) {
    return mapper.writeValueAsString(value);
  }
}
