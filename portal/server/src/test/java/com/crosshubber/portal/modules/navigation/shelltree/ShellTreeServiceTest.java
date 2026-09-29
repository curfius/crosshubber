package com.crosshubber.portal.modules.navigation.shelltree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.modules.navigation.groups.NavigationGroupEntity;
import com.crosshubber.portal.modules.navigation.groups.NavigationGroupRepository;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentCategory;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentRepository;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ShellTreeServiceTest {

  private final JsonMapper mapper = new JacksonConfig().jsonMapper();
  private final NavigationGroupRepository groupRepo = mock(NavigationGroupRepository.class);
  private final ModuleContentRepository contentRepo = mock(ModuleContentRepository.class);
  private final ShellTreeService svc = new ShellTreeService(groupRepo, contentRepo);

  private static ModuleContentEntity ep(String moduleKey, String contentKey) {
    ModuleContentEntity e = new ModuleContentEntity();
    e.setModuleKey(moduleKey);
    e.setContentKey(contentKey);
    e.setCategory(ModuleContentCategory.SETTINGS);
    e.setName(contentKey);
    e.setType(ModuleContentType.EMBEDDED);
    e.setActive(true);
    e.setMulti(false);
    return e;
  }

  @Test
  void savePersistsHiddenFlagsAndRoundTripsThem() {
    ModuleContentEntity general = ep("settings", "general");
    List<ModuleContentEntity> rows = new ArrayList<>(List.of(general));
    when(groupRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.SETTINGS))
        .thenAnswer(inv -> new ArrayList<>(savedGroups));
    when(groupRepo.findAll()).thenReturn(List.of());
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.SETTINGS))
        .thenAnswer(inv -> new ArrayList<>(rows));
    when(groupRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(contentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

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

    svc.saveShellTree("settings", body);

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
    savedGroups.add(groupCap.getValue());
    var payload = svc.shellTreePayload("settings");
    assertEquals(true, payload.groups().get(0).hidden());
    assertEquals(true, payload.items().get(0).hidden());
  }

  private final List<NavigationGroupEntity> savedGroups = new ArrayList<>();

  @Test
  void visibleRowsOmitTheHiddenKey() {
    NavigationGroupEntity existing = new NavigationGroupEntity();
    existing.setGroupKey("nav-a");
    existing.setCategory(ModuleContentCategory.SETTINGS);
    existing.setName("A");
    existing.setSortOrder(0);
    existing.setRoles("");
    savedGroups.add(existing);
    ModuleContentEntity item = ep("settings", "general");
    List<ModuleContentEntity> rows = new ArrayList<>(List.of(item));
    when(groupRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.SETTINGS))
        .thenAnswer(inv -> new ArrayList<>(savedGroups));
    when(groupRepo.findAll()).thenReturn(List.of(existing));
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.SETTINGS))
        .thenAnswer(inv -> new ArrayList<>(rows));
    when(contentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    JsonNode body =
        mapper.readTree(
            """
            {
              "groups": [{"groupKey": "nav-a", "name": "A"}],
              "items": [{"moduleKey": "settings", "contentKey": "general", "groupKey": "nav-a"}]
            }
            """);

    svc.saveShellTree("settings", body);

    var payload = svc.shellTreePayload("settings");
    assertNull(payload.groups().get(0).hidden());
    assertNull(payload.items().get(0).hidden());
  }

  @Test
  void rejectsNonBooleanHidden() {
    when(groupRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.SETTINGS))
        .thenReturn(List.of());
    when(groupRepo.findAll()).thenReturn(List.of());
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.SETTINGS))
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
            IllegalArgumentException.class, () -> svc.saveShellTree("settings", body));
    assertTrue(ex.getMessage().contains("groups[0].hidden"));
  }

  @Test
  void shellTreePayloadFiltersInactiveItems() {
    when(groupRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.SETTINGS))
        .thenReturn(List.of());
    ModuleContentEntity active = ep("settings", "general");
    ModuleContentEntity inactive = ep("settings", "retired");
    inactive.setActive(false);
    when(contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.SETTINGS))
        .thenReturn(List.of(active, inactive));

    var payload = svc.shellTreePayload("settings");
    assertEquals(1, payload.items().size());
    assertNull(payload.items().get(0).hidden());
    assertEquals("general", payload.items().get(0).contentKey());
  }
}
