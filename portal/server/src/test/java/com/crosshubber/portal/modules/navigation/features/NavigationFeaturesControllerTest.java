package com.crosshubber.portal.modules.navigation.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.crosshubber.portal.modules.navigation.settings.NavigationSettingsService;

/** Feature switches endpoint: payload mapping, partial updates, body validation. */
class NavigationFeaturesControllerTest {

  private NavigationSettingsService settings;
  private NavigationFeaturesController controller;

  @BeforeEach
  void setUp() {
    settings = mock(NavigationSettingsService.class);
    controller = new NavigationFeaturesController(settings);
  }

  private static Map<String, Object> all(boolean apps, boolean ws, boolean section) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("pinnedAppsEnabled", apps);
    m.put("workspacesEnabled", ws);
    m.put("pinnedSectionEnabled", section);
    return m;
  }

  @Test
  void getMapsStoredSettingsOntoFlags() {
    when(settings.get()).thenReturn(all(true, false, false));

    FeatureFlagsDto dto = controller.get(null);

    assertTrue(dto.pinnedAppsEnabled());
    assertFalse(dto.workspacesEnabled());
    assertFalse(dto.pinnedSectionEnabled());
  }

  @Test
  void getTreatsMissingPinnedSectionAsDisabled() {
    when(settings.get()).thenReturn(Map.of("pinnedAppsEnabled", true, "workspacesEnabled", true));

    FeatureFlagsDto dto = controller.get(null);

    assertTrue(dto.pinnedAppsEnabled());
    assertTrue(dto.workspacesEnabled());
    assertFalse(dto.pinnedSectionEnabled());
  }

  @Test
  void putMergesPartialUpdateAndReturnsFreshFlags() {
    when(settings.update(Map.of("pinnedSectionEnabled", false))).thenReturn(all(true, true, false));

    ResponseEntity<?> res = controller.put(null, Map.of("pinnedSectionEnabled", false));

    assertEquals(HttpStatus.OK, res.getStatusCode());
    FeatureFlagsDto dto = (FeatureFlagsDto) res.getBody();
    assertTrue(dto.pinnedAppsEnabled());
    assertTrue(dto.workspacesEnabled());
    assertFalse(dto.pinnedSectionEnabled());
  }

  @Test
  void putDropsUnknownKeysBeforeUpdating() {
    when(settings.update(Map.of())).thenReturn(all(true, true, true));

    ResponseEntity<?> res = controller.put(null, Map.of("bogusKey", true));

    assertEquals(HttpStatus.OK, res.getStatusCode());
    verify(settings).update(Map.of());
  }

  @Test
  void putRejectsNonBooleanPinnedSection() {
    ResponseEntity<?> res = controller.put(null, Map.of("pinnedSectionEnabled", "yes"));

    assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
    assertEquals(Map.of("error", "pinnedSectionEnabled must be a boolean"), res.getBody());
  }

  @Test
  void putRejectsNullBody() {
    assertEquals(HttpStatus.BAD_REQUEST, controller.put(null, null).getStatusCode());
  }
}
