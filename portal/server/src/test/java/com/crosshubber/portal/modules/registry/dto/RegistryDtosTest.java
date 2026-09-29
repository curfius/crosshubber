package com.crosshubber.portal.modules.registry.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.modules.navigation.groups.NavigationGroupDto;
import com.crosshubber.portal.modules.navigation.groups.NavigationGroupEntity;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentCategory;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentType;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Response-shape tests for the registry DTO records: conditional keys are omitted on serialization,
 * always-present keys survive nulls, and stored-flattened columns round-trip.
 */
class RegistryDtosTest {

  private final JsonMapper mapper = new JacksonConfig().jsonMapper();

  // --- ModuleContentDto ---

  @Test
  void moduleContentDtoOmitsBlankAndFalsyOptionals() {
    ModuleContentEntity ep = new ModuleContentEntity();
    ep.setModuleKey("m");
    ep.setContentKey("main");
    ep.setCategory(ModuleContentCategory.APPLICATIONS);
    ep.setName("Main");
    ep.setType(ModuleContentType.EMBEDDED);
    ep.setSortOrder(10);
    ep.setActive(true);
    ep.setMulti(false);
    ep.setDescription("");
    ep.setGroupKey("");
    ep.setRoles("");
    ep.setHidden(false);

    ModuleContentDto dto = ModuleContentDto.fromEntity(ep);

    assertNull(dto.description());
    assertNull(dto.groupKey());
    assertNull(dto.roles());
    assertNull(dto.hidden());
    String json = mapper.writeValueAsString(dto);
    JsonNode node = mapper.readTree(json);
    assertTrue(!node.has("description"));
    assertTrue(!node.has("groupKey"));
    assertTrue(!node.has("roles"));
    assertTrue(!node.has("hidden"));
    assertTrue(!node.has("sandbox"));
    assertEquals("Main", node.get("name").asString());
    assertEquals(10, node.get("sortOrder").asInt());
  }

  @Test
  void moduleContentDtoEmitsHiddenOnlyWhenTrueAndSplitsSandbox() {
    ModuleContentEntity ep = new ModuleContentEntity();
    ep.setModuleKey("m");
    ep.setContentKey("main");
    ep.setCategory(ModuleContentCategory.APPLICATIONS);
    ep.setName("Main");
    ep.setType(ModuleContentType.IFRAME);
    ep.setRoles("admin,editor");
    ep.setSandbox("allow-scripts,allow-forms");
    ep.setHidden(true);

    ModuleContentDto dto = ModuleContentDto.fromEntity(ep);

    assertEquals(java.util.List.of("admin", "editor"), dto.roles());
    assertEquals(java.util.List.of("allow-scripts", "allow-forms"), dto.sandbox());
    assertEquals(true, dto.hidden());
    JsonNode node = mapper.readTree(mapper.writeValueAsString(dto));
    assertTrue(node.has("hidden"));
    assertTrue(node.has("roles"));
  }

  // --- NavigationGroupDto ---

  @Test
  void groupDtoAlwaysEmitsParentKeyEvenWhenNull() {
    NavigationGroupEntity g = new NavigationGroupEntity();
    g.setGroupKey("nav-a");
    g.setCategory(ModuleContentCategory.SETTINGS);
    g.setName("A");
    g.setParentKey(null);
    g.setSortOrder(0);
    g.setRoles("");
    g.setIcon("");

    NavigationGroupDto dto = NavigationGroupDto.fromEntity(g);

    assertNull(dto.parentKey());
    assertNull(dto.icon());
    JsonNode node = mapper.readTree(mapper.writeValueAsString(dto));
    assertTrue(node.has("parentKey"));
    assertTrue(node.get("parentKey").isNull());
    assertTrue(!node.has("icon"));
    assertTrue(!node.has("hidden"));
    assertTrue(!node.has("roles"));
  }

  // --- ModuleDto ---

  @Test
  void moduleDtoOmitsEmptyCollectionsAndParsesSecurityRoles() {
    ModuleEntity m = new ModuleEntity();
    m.setKey("portal-navigation");
    m.setName("Navigation");
    m.setActive(true);
    m.setBuiltin(true);
    m.setManagedBy("manifest");
    m.setRoles("");
    m.setSecurityRoles("[{\"key\":\"portal-editor\",\"name\":\"Editor\"}]");

    ModuleDto dto =
        ModuleDto.fromEntity(
            m, java.util.List.of(new SecurityRoleDto("portal-editor", "Editor", null)));

    assertNull(dto.roles());
    assertEquals(1, dto.securityRoles().size());
    assertNull(dto.securityRoles().get(0).description());
    JsonNode node = mapper.readTree(mapper.writeValueAsString(dto));
    assertTrue(!node.has("roles"));
    assertTrue(node.get("securityRoles").get(0).has("key"));
    assertTrue(!node.get("securityRoles").get(0).has("description"));
  }

  @Test
  void moduleDtoOmitsSecurityRolesWhenEmpty() {
    ModuleEntity m = new ModuleEntity();
    m.setKey("x");
    m.setName("X");
    m.setActive(true);
    m.setBuiltin(false);
    m.setManagedBy("manual");

    ModuleDto dto = ModuleDto.fromEntity(m, java.util.List.of());

    assertNull(dto.securityRoles());
    assertTrue(!mapper.readTree(mapper.writeValueAsString(dto)).has("securityRoles"));
  }
}
