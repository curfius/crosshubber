package com.crosshubber.portal.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Locks the JSON serialization contract that API consumers depend on. Run before/after any Jackson
 * upgrade — behavior drift here (date formats, property names, inclusion rules) breaks the Angular
 * client without any compile error.
 */
class JacksonContractTest {

  private final JsonMapper mapper = new JacksonConfig().jsonMapper();

  @Test
  void instantsSerializeAsIso8601NotTimestamps() throws Exception {
    String json = mapper.writeValueAsString(Map.of("at", Instant.parse("2026-01-15T10:30:00Z")));
    assertTrue(json.contains("2026-01-15T10:30:00Z"), "expected ISO-8601 date, got: " + json);
    assertFalse(json.contains("1768473000"), "dates must not serialize as epoch numbers");
  }

  record Sample(String userId, int count) {}

  @Test
  void unknownPropertiesAreIgnoredOnPojoDeserialize() throws Exception {
    Sample parsed =
        mapper.readValue("{\"userId\":\"u1\",\"count\":3,\"futureField\":true}", Sample.class);
    assertEquals(new Sample("u1", 3), parsed);
  }

  @Test
  void nullValuesAreIncludedByDefault() throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("name", "portal");
    payload.put("icon", null);
    assertEquals("{\"name\":\"portal\",\"icon\":null}", mapper.writeValueAsString(payload));
  }

  @Test
  void mapKeysPassThroughWithoutCaseTransformation() throws Exception {
    String json = mapper.writeValueAsString(Map.of("userId", "u1", "created_at", "t"));
    assertTrue(json.contains("\"userId\""), "camelCase keys must be preserved");
    assertTrue(json.contains("\"created_at\""), "snake_case keys must be preserved");
  }

  @Test
  void recordsSerializeWithCamelCaseComponentNames() throws Exception {
    String json = mapper.writeValueAsString(new Sample("u1", 3));
    assertEquals("{\"userId\":\"u1\",\"count\":3}", json);
    Sample parsed = mapper.readValue("{\"userId\":\"u2\",\"count\":5}", Sample.class);
    assertEquals(new Sample("u2", 5), parsed);
  }
}
