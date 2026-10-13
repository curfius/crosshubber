package com.crosshubber.portal.modules.navigation.usersettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentRepository;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class NavigationUserSettingsServiceTest {

  private final NavigationUserSettingsRepository repo =
      mock(NavigationUserSettingsRepository.class);
  private final ModuleContentRepository contentRepo = mock(ModuleContentRepository.class);
  private final JsonMapper mapper = new JacksonConfig().jsonMapper();
  private final NavigationUserSettingsService svc =
      new NavigationUserSettingsService(
          repo, contentRepo, (ObjectMapper) mapper, new JsonUtils(mapper));

  @Test
  void defaultsEnableRecentlyUsedWithLimit10() {
    var settings = svc.defaultUserSettings();
    Map<?, ?> sidebar = (Map<?, ?>) settings.get("sidebar");
    assertEquals(true, sidebar.get("showRecent"));
    assertEquals(10, sidebar.get("recentLimit"));
    assertEquals(List.of(), sidebar.get("recentApps"));
  }

  @Test
  void mergeNormalizesMissingAndInvalidRecentKeys() {
    Map<String, Object> stored =
        Map.of(
            "sidebar",
            Map.of("showPinned", true, "recentLimit", 999, "recentApps", 42),
            "sidebarExpanded",
            List.of());
    var merged = svc.mergeUserSettings(List.of(), stored);
    Map<?, ?> sidebar = (Map<?, ?>) merged.get("sidebar");
    assertEquals(true, sidebar.get("showPinned"));
    assertEquals(10, sidebar.get("recentLimit"));
    assertEquals(List.of(), sidebar.get("recentApps"));
    assertEquals(true, sidebar.get("showRecent"));
  }

  @Test
  void mergeKeepsStoredRecentApps() {
    Map<String, Object> stored =
        Map.of(
            "sidebar",
                Map.of("showRecent", true, "recentLimit", 5, "recentApps", List.of("m:c", "m:b")),
            "sidebarExpanded", List.of());
    var merged = svc.mergeUserSettings(List.of(), stored);
    Map<?, ?> sidebar = (Map<?, ?>) merged.get("sidebar");
    assertEquals(List.of("m:c", "m:b"), sidebar.get("recentApps"));
    assertEquals(5, sidebar.get("recentLimit"));
  }

  @Test
  void validatePartialAcceptsRecentKeys() {
    String err =
        svc.validatePartial(
            mapper.readTree(
                """
                {"sidebar": {"showRecent": false, "recentLimit": 25,
                 "recentApps": ["ai-hub:main", "navigation:portal"]}}
                """));
    assertNull(err);
  }

  @Test
  void validatePartialRejectsOutOfRangeLimit() {
    String err = svc.validatePartial(mapper.readTree("{\"sidebar\": {\"recentLimit\": 0}}"));
    assertEquals("sidebar.recentLimit: must be an integer between 1 and 50", err);
  }

  @Test
  void validatePartialRejectsBadRef() {
    String err =
        svc.validatePartial(mapper.readTree("{\"sidebar\": {\"recentApps\": [\"no-colon\"]}}"));
    assertEquals("sidebar.recentApps: must be an array of \"moduleKey:contentKey\" strings", err);
  }

  @Test
  void legacyAppsPartialIsIgnoredWithoutError() {
    assertNull(svc.validatePartial(mapper.readTree("{\"sidebar\": {\"apps\": [\"m:c\"]}}")));
  }
}
