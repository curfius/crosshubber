package com.crosshubber.portal.modules.registry.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.config.PortalProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Legacy manifest upgrade (pre-2026-10 layout → baseline): {@code content} → {@code shell}, {@code
 * agentContributions} → {@code agentic}, dead keys + entry {@code sortOrder} dropped.
 */
class LegacyManifestsTest {

  private ObjectMapper mapper;
  private ManifestValidator validator;

  @BeforeEach
  void setUp() {
    mapper = new JacksonConfig().jsonMapper();
    validator = new ManifestValidator(new PortalProperties(), mapper);
  }

  private static final String LEGACY =
      """
      {
        "manifestVersion": 1, "key": "demo", "name": "Demo",
        "baseUrl": "https://demo.example.com",
        "content": {
          "applications": [
            {"key": "home", "name": "Home", "type": "iframe",
             "url": "https://demo.example.com/app", "sortOrder": 0}
          ],
          "features": [{"key": "chat", "name": "Chat", "type": "embedded", "loadPath": "chat"}]
        },
        "capabilities": [],
        "agentContributions": {"tools": [{"name": "ping", "description": "pings"}]},
        "userSettingsSchema": {},
        "adminSettingsSchema": {}
      }
      """;

  @Test
  void upgradeRenamesBlocksAndStripsDeadKeys() throws Exception {
    JsonNode upgraded = LegacyManifests.upgrade(mapper.readTree(LEGACY));

    assertTrue(LegacyManifests.isLegacy(mapper.readTree(LEGACY)));
    assertFalse(LegacyManifests.isLegacy(upgraded));
    assertTrue(upgraded.has("shell"));
    assertFalse(upgraded.has("content"));
    assertFalse(upgraded.has("agentContributions"));
    assertTrue(upgraded.has("agentic"));
    assertFalse(upgraded.has("capabilities"));
    assertFalse(upgraded.has("userSettingsSchema"));
    assertFalse(upgraded.has("adminSettingsSchema"));
    assertEquals("chat", upgraded.path("shell").path("features").get(0).path("key").asString());
    assertEquals("ping", upgraded.path("agentic").path("tools").get(0).path("name").asString());
  }

  @Test
  void upgradedManifestValidatesAgainstBaselineSchema() throws Exception {
    ManifestValidator.Result result =
        validator.parse(LegacyManifests.upgrade(mapper.readTree(LEGACY)));

    assertTrue(result.ok(), "issues: " + result.issues());
    assertEquals(1, result.manifest().path("shell").path("applications").size());
    assertEquals(1, result.manifest().path("agentic").path("tools").size());
  }

  @Test
  void upgradeStripsEntryLevelSortOrder() throws Exception {
    JsonNode upgraded = LegacyManifests.upgrade(mapper.readTree(LEGACY));

    JsonNode home = upgraded.path("shell").path("applications").get(0);
    assertFalse(home.has("sortOrder"));
    assertEquals("home", home.path("key").asString());
    assertEquals("https://demo.example.com/app", home.path("url").asString());
  }

  @Test
  void upgradePreservesNonLegacyManifestAndIsIdempotent() throws Exception {
    JsonNode modern =
        mapper.readTree(
            """
            {"manifestVersion":1,"key":"demo","name":"Demo","baseUrl":"https://demo.example.com",
             "shell":{"applications":[{"key":"home","name":"Home","type":"iframe",
             "url":"https://demo.example.com/app"}]}}
            """);
    JsonNode copy = modern.deepCopy();

    JsonNode upgraded = LegacyManifests.upgrade(modern);

    assertEquals(copy.toString(), upgraded.toString());
    assertFalse(LegacyManifests.isLegacy(upgraded));
  }

  @Test
  void upgradeRejectsCorruptRowsWithBothLegacyAndModernBlocks() throws Exception {
    JsonNode corrupt =
        mapper.readTree(
            """
            {"manifestVersion":1,"key":"demo","name":"Demo","baseUrl":"https://demo.example.com",
             "content":{}, "shell":{}}
            """);

    assertThrows(IllegalStateException.class, () -> LegacyManifests.upgrade(corrupt));
  }
}
