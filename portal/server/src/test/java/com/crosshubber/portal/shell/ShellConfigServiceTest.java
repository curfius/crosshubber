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
import com.crosshubber.portal.modules.navigation.groups.NavigationGroupRepository;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentCategory;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentRepository;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentType;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModuleRepository;
import com.crosshubber.portal.modules.usersettings.scopes.UserSettingsRepository;
import com.crosshubber.portal.security.PortalUser;
import com.crosshubber.portal.shell.dto.ShellConfigDto;

import tools.jackson.databind.json.JsonMapper;

class ShellConfigServiceTest {

  private final ModuleRepository moduleRepo = Mockito.mock(ModuleRepository.class);
  private final ModuleContentRepository contentRepo = Mockito.mock(ModuleContentRepository.class);
  private final NavigationGroupRepository groupRepo = Mockito.mock(NavigationGroupRepository.class);
  private final UserSettingsRepository userSettingsRepo =
      Mockito.mock(UserSettingsRepository.class);
  private final JsonMapper mapper = new JacksonConfig().jsonMapper();
  private final ShellConfigService svc =
      new ShellConfigService(
          moduleRepo,
          contentRepo,
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

  private static ModuleContentEntity ep(
      String moduleKey, String contentKey, ModuleContentCategory category) {
    ModuleContentEntity e = new ModuleContentEntity();
    e.setModuleKey(moduleKey);
    e.setContentKey(contentKey);
    e.setCategory(category);
    e.setName(contentKey);
    e.setType(ModuleContentType.EMBEDDED);
    e.setSortOrder(0);
    e.setActive(true);
    e.setMulti(false);
    return e;
  }

  @Test
  void buildConfigFiltersModulesGroupsAndModuleContentsByRole() {
    Mockito.when(moduleRepo.findAll())
        .thenReturn(List.of(module("visible", true, ""), module("other", true, "admin")));
    Mockito.when(groupRepo.findAll()).thenReturn(List.of());
    Mockito.when(contentRepo.findAll())
        .thenReturn(
            List.of(
                ep("visible", "a", ModuleContentCategory.APPLICATIONS),
                ep("other", "b", ModuleContentCategory.APPLICATIONS),
                ep("visible", "c", ModuleContentCategory.ADMIN_SETTINGS)));
    Mockito.when(userSettingsRepo.findByUserId("sub-1")).thenReturn(List.of());

    ShellConfigDto config = svc.buildConfig(user());

    assertEquals("sub-1", config.user().sub());
    assertNull(config.user().email());
    assertEquals(List.of("portal-user"), config.user().roles());
    assertEquals(1, config.moduleContents().size());
    assertEquals("a", config.moduleContents().get(0).contentKey());
    assertTrue(config.navigationGroups().isEmpty());
    assertTrue(config.preferences().isEmpty());
  }

  @Test
  void buildConfigSortsModuleContentsByCategoryOrderThenSortOrderThenName() {
    Mockito.when(moduleRepo.findAll()).thenReturn(List.of(module("m", true, "")));
    Mockito.when(groupRepo.findAll()).thenReturn(List.of());
    ModuleContentEntity zFirst = ep("m", "z", ModuleContentCategory.SETTINGS);
    zFirst.setSortOrder(10);
    ModuleContentEntity aSecond = ep("m", "a", ModuleContentCategory.SETTINGS);
    aSecond.setSortOrder(20);
    ModuleContentEntity app = ep("m", "app", ModuleContentCategory.APPLICATIONS);
    Mockito.when(contentRepo.findAll()).thenReturn(List.of(zFirst, aSecond, app));
    Mockito.when(userSettingsRepo.findByUserId("sub-1")).thenReturn(List.of());

    ShellConfigDto config = svc.buildConfig(user());

    List<String> contentKeys =
        config.moduleContents().stream()
            .map(com.crosshubber.portal.modules.registry.dto.ModuleContentDto::contentKey)
            .toList();
    assertEquals(List.of("app", "z", "a"), contentKeys);
  }

  @Test
  void servicesAlwaysIncludePostgresWithNullUrl() {
    Mockito.when(moduleRepo.findAll()).thenReturn(List.of());
    Mockito.when(groupRepo.findAll()).thenReturn(List.of());
    Mockito.when(contentRepo.findAll()).thenReturn(List.of());
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
