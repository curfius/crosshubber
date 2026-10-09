package com.crosshubber.portal.modules.navigation.shelltree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shell-tree routes: category gate (settings fold), body shape validation, service delegation. */
class NavigationShellTreeControllerTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private ShellTreeService service;
  private NavigationShellTreeController controller;

  @BeforeEach
  void setUp() {
    service = mock(ShellTreeService.class);
    controller = new NavigationShellTreeController(service);
  }

  private static JsonNode treeBody() {
    return MAPPER.readTree("{\"groups\":[],\"items\":[]}");
  }

  @Test
  void getRejectsLegacySettingsAndFeaturesCategories() {
    for (String bad : new String[] {null, "settings", "features"}) {
      ResponseEntity<?> res = controller.get(null, bad);
      assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
      assertEquals(
          Map.of("error", "category must be applications|admin-settings|user-settings"),
          res.getBody());
    }
    verify(service, never()).shellTreePayload(any());
  }

  @Test
  void getDelegatesForTheThreeShellCategories() {
    for (String category : new String[] {"applications", "admin-settings", "user-settings"}) {
      ResponseEntity<?> res = controller.get(null, category);
      assertEquals(HttpStatus.OK, res.getStatusCode());
      verify(service).shellTreePayload(category);
    }
  }

  @Test
  void putRejectsPathCategoryBeforeBodyValidation() {
    ResponseEntity<?> res = controller.put(null, "settings", treeBody());

    assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
    verify(service, never()).saveShellTree(any(), any());
  }

  @Test
  void putRejectsBodiesWithoutGroupsOrItemsArrays() {
    JsonNode noGroups = MAPPER.readTree("{\"items\":[]}");
    ResponseEntity<?> res = controller.put(null, "applications", noGroups);
    assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
    assertEquals(
        Map.of("error", "groups: Invalid input: expected array, received undefined"),
        res.getBody());

    JsonNode noItems = MAPPER.readTree("{\"groups\":[]}");
    res = controller.put(null, "applications", noItems);
    assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
    assertEquals(
        Map.of("error", "items: Invalid input: expected array, received undefined"), res.getBody());

    verify(service, never()).saveShellTree(any(), any());
  }

  @Test
  void putDelegatesValidBodies() {
    when(service.saveShellTree(eq("applications"), any())).thenReturn(null);

    ResponseEntity<?> res = controller.put(null, "applications", treeBody());

    assertEquals(HttpStatus.OK, res.getStatusCode());
    verify(service).saveShellTree(eq("applications"), any());
  }

  @Test
  void putMapsValidationFailuresToErrorEnvelope() {
    when(service.saveShellTree(eq("admin-settings"), any()))
        .thenThrow(new IllegalArgumentException("cycle at nav-x"));

    ResponseEntity<?> res = controller.put(null, "admin-settings", treeBody());

    assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
    assertEquals(Map.of("error", "cycle at nav-x"), res.getBody());
  }
}
