package com.crosshubber.portal.modules.navigation.shelltree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.crosshubber.portal.modules.registry.entrypointgroups.EntryPointGroupEntity;
import com.crosshubber.portal.modules.registry.entrypointgroups.EntryPointGroupRepository;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointEntity;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ShellTreeServiceTest {

  private final JsonMapper mapper = new JacksonConfig().jsonMapper();
  private final EntryPointGroupRepository groupRepo = mock(EntryPointGroupRepository.class);
  private final EntryPointRepository entryPointRepo = mock(EntryPointRepository.class);
  private final ShellTreeService svc = new ShellTreeService(groupRepo, entryPointRepo);

  private static EntryPointEntity ep(String moduleKey, String entryKey) {
    EntryPointEntity e = new EntryPointEntity();
    e.setModuleKey(moduleKey);
    e.setEntryKey(entryKey);
    e.setCategory("settings");
    e.setName(entryKey);
    e.setType("embedded");
    e.setActive(true);
    e.setMulti(false);
    return e;
  }

  @Test
  void savePersistsHiddenFlagsAndRoundTripsThem() {
    EntryPointEntity general = ep("settings", "general");
    List<EntryPointEntity> rows = new ArrayList<>(List.of(general));
    when(groupRepo.findByCategoryOrderBySortOrderAscNameAsc("settings"))
        .thenAnswer(inv -> new ArrayList<>(savedGroups));
    when(groupRepo.findAll()).thenReturn(List.of());
    when(entryPointRepo.findByCategoryOrderBySortOrderAscNameAsc("settings"))
        .thenAnswer(inv -> new ArrayList<>(rows));
    when(groupRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(entryPointRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    JsonNode body =
        mapper.readTree(
            """
            {
              "groups": [
                {"groupKey": "nav-test", "name": "Test", "parentKey": null, "hidden": true}
              ],
              "items": [
                {"moduleKey": "settings", "entryKey": "general", "groupKey": "nav-test",
                 "hidden": true}
              ]
            }
            """);

    svc.saveShellTree("settings", body);

    ArgumentCaptor<EntryPointGroupEntity> groupCap =
        ArgumentCaptor.forClass(EntryPointGroupEntity.class);
    org.mockito.Mockito.verify(groupRepo).save(groupCap.capture());
    assertTrue(groupCap.getValue().getHidden());
    assertEquals("nav-test", groupCap.getValue().getGroupKey());

    ArgumentCaptor<EntryPointEntity> epCap = ArgumentCaptor.forClass(EntryPointEntity.class);
    org.mockito.Mockito.verify(entryPointRepo, org.mockito.Mockito.atLeastOnce())
        .save(epCap.capture());
    assertTrue(epCap.getValue().getHidden());
    assertEquals("nav-test", epCap.getValue().getGroupKey());

    // Round-trip: hidden=true surfaces in the payload, visible rows omit the key.
    savedGroups.add(groupCap.getValue());
    var payload = svc.shellTreePayload("settings");
    @SuppressWarnings("unchecked")
    List<java.util.Map<String, Object>> groups =
        (List<java.util.Map<String, Object>>) payload.get("groups");
    assertEquals(true, groups.get(0).get("hidden"));
    @SuppressWarnings("unchecked")
    List<java.util.Map<String, Object>> items =
        (List<java.util.Map<String, Object>>) payload.get("items");
    assertEquals(true, items.get(0).get("hidden"));
  }

  private final List<EntryPointGroupEntity> savedGroups = new ArrayList<>();

  @Test
  void visibleRowsOmitTheHiddenKey() {
    EntryPointGroupEntity existing = new EntryPointGroupEntity();
    existing.setGroupKey("nav-a");
    existing.setCategory("settings");
    existing.setName("A");
    existing.setSortOrder(0);
    existing.setRoles("");
    savedGroups.add(existing);
    EntryPointEntity item = ep("settings", "general");
    List<EntryPointEntity> rows = new ArrayList<>(List.of(item));
    when(groupRepo.findByCategoryOrderBySortOrderAscNameAsc("settings"))
        .thenAnswer(inv -> new ArrayList<>(savedGroups));
    when(groupRepo.findAll()).thenReturn(List.of(existing));
    when(entryPointRepo.findByCategoryOrderBySortOrderAscNameAsc("settings"))
        .thenAnswer(inv -> new ArrayList<>(rows));
    when(entryPointRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    JsonNode body =
        mapper.readTree(
            """
            {
              "groups": [{"groupKey": "nav-a", "name": "A"}],
              "items": [{"moduleKey": "settings", "entryKey": "general", "groupKey": "nav-a"}]
            }
            """);

    svc.saveShellTree("settings", body);

    var payload = svc.shellTreePayload("settings");
    @SuppressWarnings("unchecked")
    List<java.util.Map<String, Object>> groups =
        (List<java.util.Map<String, Object>>) payload.get("groups");
    assertFalse(groups.get(0).containsKey("hidden"));
    @SuppressWarnings("unchecked")
    List<java.util.Map<String, Object>> items =
        (List<java.util.Map<String, Object>>) payload.get("items");
    assertFalse(items.get(0).containsKey("hidden"));
  }

  @Test
  void rejectsNonBooleanHidden() {
    when(groupRepo.findByCategoryOrderBySortOrderAscNameAsc("settings")).thenReturn(List.of());
    when(groupRepo.findAll()).thenReturn(List.of());
    when(entryPointRepo.findByCategoryOrderBySortOrderAscNameAsc("settings")).thenReturn(List.of());

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
    when(groupRepo.findByCategoryOrderBySortOrderAscNameAsc("settings")).thenReturn(List.of());
    EntryPointEntity active = ep("settings", "general");
    EntryPointEntity inactive = ep("settings", "retired");
    inactive.setActive(false);
    when(entryPointRepo.findByCategoryOrderBySortOrderAscNameAsc("settings"))
        .thenReturn(List.of(active, inactive));

    var payload = svc.shellTreePayload("settings");
    @SuppressWarnings("unchecked")
    List<java.util.Map<String, Object>> items =
        (List<java.util.Map<String, Object>>) payload.get("items");
    assertEquals(1, items.size());
    assertNull(items.get(0).get("hidden"));
    assertEquals("general", items.get(0).get("entryKey"));
  }
}
