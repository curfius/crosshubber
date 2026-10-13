package com.crosshubber.portal.modules.navigation.shelltree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.modules.navigation.groups.NavigationGroupEntity;
import com.crosshubber.portal.modules.navigation.groups.NavigationGroupRepository;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentCategory;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentRepository;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentType;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModuleRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ShellTreeServiceTest {

  private final JsonMapper mapper = new JacksonConfig().jsonMapper();
  private final NavigationGroupRepository groupRepo = mock(NavigationGroupRepository.class);
  private final ModuleContentRepository contentRepo = mock(ModuleContentRepository.class);
  private final ModuleRepository moduleRepo = mock(ModuleRepository.class);
  private final ShellSections shellSections = mock(ShellSections.class);
  private final ShellTreeService svc =
      new ShellTreeService(groupRepo, contentRepo, moduleRepo, shellSections);

  private final List<NavigationGroupEntity> savedGroups = new ArrayList<>();

  private static ModuleContentEntity ep(String moduleKey, String contentKey) {
    ModuleContentEntity e = new ModuleContentEntity();
    e.setModuleKey(moduleKey);
    e.setContentKey(contentKey);
    e.setCategory(ModuleContentCategory.ADMIN_SETTINGS);
    e.setName(contentKey);
    e.setType(ModuleContentType.EMBEDDED);
    e.setActive(true);
    e.setMulti(false);
    return e;
  }

  private void stubActiveModules(String... moduleKeys) {
    when(moduleRepo.findAll())
        .thenAnswer(
            inv -> {
              List<ModuleEntity> out = new ArrayList<>();
              for (String key : moduleKeys) {
                ModuleEntity m = new ModuleEntity();
                m.setKey(key);
                m.setName(key);
                m.setActive(true);
                out.add(m);
              }
              return out;
            });
  }

  private void stubGroupLookups() {
    when(groupRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.ADMIN_SETTINGS))
        .thenAnswer(inv -> new ArrayList<>(savedGroups));
    when(groupRepo.findAll()).thenAnswer(inv -> new ArrayList<>(savedGroups));
    when(groupRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(contentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(shellSections.assignSection(any(ModuleContentCategory.class), anyString(), any()))
        .thenAnswer(inv -> "mod-" + inv.getArgument(1));
    when(groupRepo.findMaxSortOrder(any(ModuleContentCategory.class))).thenReturn(Optional.empty());
  }

  @Test
  void savePersistsHiddenFlagsAndRoundTripsThem() {
    stubActiveModules("settings");
    stubGroupLookups();
    ModuleContentEntity general = ep("settings", "general");
    List<ModuleContentEntity> rows = new ArrayList<>(List.of(general));
    savedGroups.add(builtinGroup("nav-test", 0));
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.ADMIN_SETTINGS))
        .thenAnswer(inv -> new ArrayList<>(rows));

    JsonNode body =
        mapper.readTree(
            """
            {
              "groups": [
                {"groupKey": "nav-test", "name": "Test", "parentKey": null, "hidden": true}
              ],
              "items": [
                {"moduleKey": "settings", "contentKey": "general", "groupKey": "nav-test",
                 "hidden": true}
              ]
            }
            """);

    svc.saveShellTree("admin-settings", body);

    ArgumentCaptor<NavigationGroupEntity> groupCap =
        ArgumentCaptor.forClass(NavigationGroupEntity.class);
    org.mockito.Mockito.verify(groupRepo).save(groupCap.capture());
    assertTrue(groupCap.getValue().getHidden());
    assertEquals("nav-test", groupCap.getValue().getGroupKey());

    ArgumentCaptor<ModuleContentEntity> epCap = ArgumentCaptor.forClass(ModuleContentEntity.class);
    org.mockito.Mockito.verify(contentRepo, org.mockito.Mockito.atLeastOnce())
        .save(epCap.capture());
    assertTrue(epCap.getValue().getHidden());
    assertEquals("nav-test", epCap.getValue().getGroupKey());

    // Round-trip: hidden=true surfaces in the payload, visible rows omit the key.
    var payload = svc.shellTreePayload("admin-settings");
    assertEquals(true, payload.groups().get(0).hidden());
    assertEquals(true, payload.items().get(0).hidden());
  }

  @Test
  void visibleRowsOmitTheHiddenKey() {
    stubActiveModules("settings");
    stubGroupLookups();
    savedGroups.add(builtinGroup("nav-a", 0));
    ModuleContentEntity item = ep("settings", "general");
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.ADMIN_SETTINGS))
        .thenAnswer(inv -> new ArrayList<>(List.of(item)));

    JsonNode body =
        mapper.readTree(
            """
            {
              "groups": [{"groupKey": "nav-a", "name": "A"}],
              "items": [{"moduleKey": "settings", "contentKey": "general", "groupKey": "nav-a"}]
            }
            """);

    svc.saveShellTree("admin-settings", body);

    var payload = svc.shellTreePayload("admin-settings");
    assertNull(payload.groups().get(0).hidden());
    assertNull(payload.items().get(0).hidden());
  }

  @Test
  void rejectsNonBooleanHidden() {
    stubGroupLookups();
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.ADMIN_SETTINGS))
        .thenReturn(List.of());

    JsonNode body =
        mapper.readTree(
            """
            {
              "groups": [{"name": "X", "hidden": "yes"}],
              "items": []
            }
            """);

    IllegalArgumentException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> svc.saveShellTree("admin-settings", body));
    assertTrue(ex.getMessage().contains("groups[0].hidden"));
  }

  @Test
  void shellTreePayloadFiltersInactiveItemsAndInactiveModules() {
    stubActiveModules("settings");
    when(groupRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.ADMIN_SETTINGS))
        .thenReturn(List.of());
    ModuleContentEntity active = ep("settings", "general");
    ModuleContentEntity inactiveRow = ep("settings", "retired");
    inactiveRow.setActive(false);
    ModuleContentEntity inactiveModule = ep("gone-mod", "orphaned");
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.ADMIN_SETTINGS))
        .thenReturn(List.of(active, inactiveRow, inactiveModule));

    var payload = svc.shellTreePayload("admin-settings");
    assertEquals(1, payload.items().size());
    assertEquals("general", payload.items().get(0).contentKey());
  }

  @Test
  void savesValidItemColor() {
    stubActiveModules("settings");
    stubGroupLookups();
    ModuleContentEntity general = ep("settings", "general");
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.ADMIN_SETTINGS))
        .thenAnswer(inv -> new ArrayList<>(List.of(general)));

    JsonNode body =
        mapper.readTree(
            """
            {
              "groups": [{"groupKey": "nav-g", "name": "G"}],
              "items": [
                {"moduleKey": "settings", "contentKey": "general", "groupKey": "nav-g",
                 "color": "#3b82f6"}
              ]
            }
            """);

    svc.saveShellTree("admin-settings", body);

    assertEquals("#3b82f6", general.getColor());
    assertEquals("nav-g", general.getGroupKey());
  }

  @Test
  void missingColorPreservesStoredValue() {
    stubActiveModules("settings");
    stubGroupLookups();
    ModuleContentEntity general = ep("settings", "general");
    general.setColor("#ef4444");
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.ADMIN_SETTINGS))
        .thenAnswer(inv -> new ArrayList<>(List.of(general)));

    JsonNode body =
        mapper.readTree(
            """
            {
              "groups": [{"groupKey": "nav-g", "name": "G"}],
              "items": [
                {"moduleKey": "settings", "contentKey": "general", "groupKey": "nav-g"}
              ]
            }
            """);

    svc.saveShellTree("admin-settings", body);

    assertEquals("#ef4444", general.getColor());
  }

  @Test
  void nullColorClearsStoredValue() {
    stubActiveModules("settings");
    stubGroupLookups();
    ModuleContentEntity general = ep("settings", "general");
    general.setColor("#ef4444");
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.ADMIN_SETTINGS))
        .thenAnswer(inv -> new ArrayList<>(List.of(general)));

    JsonNode body =
        mapper.readTree(
            """
            {
              "groups": [{"groupKey": "nav-g", "name": "G"}],
              "items": [
                {"moduleKey": "settings", "contentKey": "general", "groupKey": "nav-g",
                 "color": null}
              ]
            }
            """);

    svc.saveShellTree("admin-settings", body);

    assertNull(general.getColor());
  }

  @Test
  void rejectsNonHexColor() {
    stubGroupLookups();
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.ADMIN_SETTINGS))
        .thenReturn(List.of());

    JsonNode body =
        mapper.readTree(
            """
            {
              "groups": [],
              "items": [
                {"moduleKey": "settings", "contentKey": "general", "color": "red"}
              ]
            }
            """);

    IllegalArgumentException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> svc.saveShellTree("admin-settings", body));
    assertTrue(ex.getMessage().contains("items[0].color"));
  }

  @Test
  void savesGroupColor() {
    stubActiveModules("settings");
    stubGroupLookups();
    ModuleContentEntity general = ep("settings", "general");
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.ADMIN_SETTINGS))
        .thenAnswer(inv -> new ArrayList<>(List.of(general)));

    JsonNode body =
        mapper.readTree(
            """
            {
              "groups": [{"groupKey": "nav-g", "name": "G", "color": "#10b981"}],
              "items": [
                {"moduleKey": "settings", "contentKey": "general", "groupKey": "nav-g"}
              ]
            }
            """);

    svc.saveShellTree("admin-settings", body);

    ArgumentCaptor<NavigationGroupEntity> groupCap =
        ArgumentCaptor.forClass(NavigationGroupEntity.class);
    org.mockito.Mockito.verify(groupRepo).save(groupCap.capture());
    assertEquals("#10b981", groupCap.getValue().getColor());
    // Round-trip: the color surfaces in the payload DTO.
    savedGroups.add(groupCap.getValue());
    var payload = svc.shellTreePayload("admin-settings");
    assertEquals("#10b981", payload.groups().get(0).color());
  }

  @Test
  void rejectsBadGroupColor() {
    stubActiveModules("settings");
    stubGroupLookups();
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.ADMIN_SETTINGS))
        .thenReturn(List.of());

    JsonNode body =
        mapper.readTree(
            """
            {
              "groups": [{"name": "G", "color": "teal"}],
              "items": []
            }
            """);

    IllegalArgumentException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> svc.saveShellTree("admin-settings", body));
    assertTrue(ex.getMessage().contains("groups[0].color"));
  }

  @Test
  void rejectsRootLevelItems() {
    stubActiveModules("settings");
    stubGroupLookups();
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.ADMIN_SETTINGS))
        .thenAnswer(inv -> new ArrayList<>(List.of(ep("settings", "general"))));

    JsonNode body =
        mapper.readTree(
            """
            {
              "groups": [],
              "items": [
                {"moduleKey": "settings", "contentKey": "general", "groupKey": null}
              ]
            }
            """);

    IllegalArgumentException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> svc.saveShellTree("admin-settings", body));
    assertTrue(ex.getMessage().contains("must live in a section"));
  }

  @Test
  void unlistedItemsKeepHomeInTheirModuleSection() {
    stubActiveModules("settings");
    stubGroupLookups();
    ModuleContentEntity listed = ep("settings", "general");
    ModuleContentEntity unlisted = ep("other-mod", "late");
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.ADMIN_SETTINGS))
        .thenAnswer(inv -> new ArrayList<>(List.of(listed, unlisted)));

    JsonNode body =
        mapper.readTree(
            """
            {
              "groups": [{"groupKey": "nav-g", "name": "G"}],
              "items": [
                {"moduleKey": "settings", "contentKey": "general", "groupKey": "nav-g"}
              ]
            }
            """);

    svc.saveShellTree("admin-settings", body);

    org.mockito.Mockito.verify(shellSections)
        .assignSection(ModuleContentCategory.ADMIN_SETTINGS, "other-mod", List.of());
    assertEquals("mod-other-mod", unlisted.getGroupKey());
  }

  @Test
  void removedGroupItemsAreReHomed() {
    stubActiveModules("m2");
    stubGroupLookups();
    savedGroups.add(builtinGroup("nav-g", 0));
    ModuleContentEntity hosted = ep("m2", "two");
    hosted.setGroupKey("nav-g");
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.ADMIN_SETTINGS))
        .thenAnswer(inv -> new ArrayList<>(List.of(hosted)));
    when(contentRepo.findByGroupKey("nav-g")).thenReturn(List.of());

    // Empty payload: the group is removed; unlisted items get re-homed into
    // their module section in the same transaction (no group is ever left hosting).
    JsonNode body =
        mapper.readTree(
            """
            {
              "groups": [],
              "items": []
            }
            """);

    svc.saveShellTree("admin-settings", body);

    org.mockito.Mockito.verify(shellSections)
        .assignSection(ModuleContentCategory.ADMIN_SETTINGS, "m2", List.of());
    assertEquals("mod-m2", hosted.getGroupKey());
    org.mockito.Mockito.verify(groupRepo).delete(savedGroups.get(0));
  }

  private static NavigationGroupEntity builtinGroup(String key, int sortOrder) {
    NavigationGroupEntity g = new NavigationGroupEntity();
    g.setGroupKey(key);
    g.setCategory(ModuleContentCategory.ADMIN_SETTINGS);
    g.setName(key);
    g.setSortOrder(sortOrder);
    g.setRoles("");
    return g;
  }
}
