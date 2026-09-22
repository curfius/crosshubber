package com.crosshubber.portal.modules.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.crosshubber.portal.config.JacksonConfig;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class NavigationValidationServiceTest {

  private final JsonMapper mapper = new JacksonConfig().jsonMapper();

  // --- stripLayout: hidden flag round-trip (README divergence #7) ---

  @Test
  void stripLayoutPreservesHiddenOnNodes() {
    JsonNode layout =
        mapper.readTree(
            """
            {
              "pinnedSectionEnabled": true,
              "sections": [
                {"id": "s1", "name": "Apps", "hidden": true, "children": [
                  {"id": "i1", "type": "item", "ref": "a:main", "hidden": true},
                  {"id": "i2", "type": "item", "ref": "b:main", "junk": "dropped"}
                ]}
              ]
            }
            """);

    JsonNode stripped = NavigationValidationService.stripLayout(layout);

    assertEquals(true, stripped.path("sections").get(0).path("hidden").asBoolean());
    assertEquals(
        true, stripped.path("sections").get(0).path("children").get(0).path("hidden").asBoolean());
    assertFalse(stripped.path("sections").get(0).path("children").get(1).has("hidden"));
    assertFalse(stripped.path("sections").get(0).path("children").get(1).has("junk"));
  }

  // --- validateLayout: hidden must be boolean when present ---

  @Test
  void validateLayoutRejectsNonBooleanHidden() {
    JsonNode layout =
        mapper.readTree(
            """
            {"pinnedSectionEnabled": true, "sections": [
              {"id": "s1", "name": "Apps", "hidden": "yes", "children": []}]}
            """);
    NavigationValidationService.Validation res =
        NavigationValidationService.validateLayout(layout, Set.of());
    assertFalse(res.success());
    assertTrue(res.error().contains("hidden"));
  }

  @Test
  void validateLayoutAcceptsBooleanHidden() {
    JsonNode layout =
        mapper.readTree(
            """
            {"pinnedSectionEnabled": true, "sections": [
              {"id": "s1", "name": "Apps", "hidden": true, "children": [
                {"id": "i1", "type": "item", "ref": "a:main"}]}]}
            """);
    NavigationValidationService.Validation res =
        NavigationValidationService.validateLayout(layout, Set.of("a:main"));
    assertTrue(res.success(), res.error());
  }

  // --- validateShellTree: unchanged baseline guard ---

  @Test
  void validateShellTreeRejectsUnknownParent() {
    NavigationValidationService.Validation res =
        NavigationValidationService.validateShellTree(
            java.util.List.of(new NavigationValidationService.ShellGroup("nav-a", "A", "nav-x")),
            java.util.List.of());
    assertFalse(res.success());
    assertTrue(res.error().contains("unknown parent"));
  }
}
