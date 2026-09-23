package com.crosshubber.portal.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.modules.registry.entrypointgroups.EntryPointGroupRepository;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointEntity;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointRepository;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModuleRepository;
import com.crosshubber.portal.modules.usersettings.scopes.UserSettingsRepository;
import com.crosshubber.portal.security.PortalUser;
import com.crosshubber.portal.shell.dto.ShellConfigDto;

import tools.jackson.databind.json.JsonMapper;

class ShellConfigServiceTest {

  private final ModuleRepository moduleRepo = Mockito.mock(ModuleRepository.class);
  private final EntryPointRepository entryPointRepo = Mockito.mock(EntryPointRepository.class);
  private final EntryPointGroupRepository groupRepo = Mockito.mock(EntryPointGroupRepository.class);
  private final UserSettingsRepository userSettingsRepo =
      Mockito.mock(UserSettingsRepository.class);
  private final JsonMapper mapper = new JacksonConfig().jsonMapper();
  private final ShellConfigService svc =
      new ShellConfigService(
          moduleRepo,
          entryPointRepo,
          groupRepo,
          userSettingsRepo,
          new JsonUtils(mapper),
          new PortalProperties());

  private static PortalUser user() {
    return new PortalUser("sub-1", "Alice", null, List.of("portal-user"));
  }

  private static ModuleEntity module(String key, boolean active, String roles) {
    ModuleEntity m = new ModuleEntity();
    m.setKey(key);
    m.setName(key);
    m.setActive(active);
    m.setBuiltin(false);
    m.setManagedBy("manual");
    m.setRoles(roles);
    return m;
  }

  private static EntryPointEntity ep(String moduleKey, String entryKey, String category) {
    EntryPointEntity e = new EntryPointEntity();
    e.setModuleKey(moduleKey);
    e.setEntryKey(entryKey);
    e.setCategory(category);
    e.setName(entryKey);
    e.setType("embedded");
    e.setSortOrder(0);
    e.setActive(true);
    e.setMulti(false);
    return e;
  }

  @Test
  void buildConfigFiltersModulesGroupsAndEntryPointsByRole() {
    Mockito.when(moduleRepo.findAll())
        .thenReturn(List.of(module("visible", true, ""), module("other", true, "admin")));
    Mockito.when(groupRepo.findAll()).thenReturn(List.of());
    Mockito.when(entryPointRepo.findAll())
        .thenReturn(
            List.of(
                ep("visible", "a", "applications"),
                ep("other", "b", "applications"),
                ep("visible", "c", "admin-settings")));
    Mockito.when(userSettingsRepo.findByUserId("sub-1")).thenReturn(List.of());

    ShellConfigDto config = svc.buildConfig(user());

    assertEquals("sub-1", config.user().sub());
    assertNull(config.user().email());
    assertEquals(List.of("portal-user"), config.user().roles());
    assertEquals(1, config.entryPoints().size());
    assertEquals("a", config.entryPoints().get(0).entryKey());
    assertTrue(config.entryPointGroups().isEmpty());
    assertTrue(config.preferences().isEmpty());
  }

  @Test
  void buildConfigSortsEntryPointsByCategoryOrderThenSortOrderThenName() {
    Mockito.when(moduleRepo.findAll()).thenReturn(List.of(module("m", true, "")));
    Mockito.when(groupRepo.findAll()).thenReturn(List.of());
    EntryPointEntity zFirst = ep("m", "z", "settings");
    zFirst.setSortOrder(10);
    EntryPointEntity aSecond = ep("m", "a", "settings");
    aSecond.setSortOrder(20);
    EntryPointEntity app = ep("m", "app", "applications");
    Mockito.when(entryPointRepo.findAll()).thenReturn(List.of(zFirst, aSecond, app));
    Mockito.when(userSettingsRepo.findByUserId("sub-1")).thenReturn(List.of());

    ShellConfigDto config = svc.buildConfig(user());

    List<String> entryKeys =
        config.entryPoints().stream()
            .map(com.crosshubber.portal.modules.registry.dto.EntryPointDto::entryKey)
            .toList();
    assertEquals(List.of("app", "z", "a"), entryKeys);
  }

  @Test
  void servicesAlwaysIncludePostgresWithNullUrl() {
    Mockito.when(moduleRepo.findAll()).thenReturn(List.of());
    Mockito.when(groupRepo.findAll()).thenReturn(List.of());
    Mockito.when(entryPointRepo.findAll()).thenReturn(List.of());
    Mockito.when(userSettingsRepo.findByUserId("sub-1")).thenReturn(List.of());

    ShellConfigDto config = svc.buildConfig(user());

    assertEquals(1, config.services().size());
    assertEquals("postgres", config.services().get(0).key());
    assertNull(config.services().get(0).url());
    // url must survive serialization even though it is null (per-field ALWAYS include).
    String json = mapper.writeValueAsString(config.services().get(0));
    assertTrue(json.contains("\"url\":null"));
  }
}
