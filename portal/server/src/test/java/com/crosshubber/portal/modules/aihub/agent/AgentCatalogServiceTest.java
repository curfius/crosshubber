package com.crosshubber.portal.modules.aihub.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.crosshubber.portal.security.PortalUser;

class AgentCatalogServiceTest {

  private final ToolRegistry registry = mock(ToolRegistry.class);
  private final AgentCatalogService service = new AgentCatalogService(registry);

  private static PortalUser user(String... roles) {
    return new PortalUser("u1", "Dev", "d@x", List.of(roles));
  }

  @Test
  void listsBuiltinRemoteAndAgentEntriesWithKindAndModule() {
    when(registry.catalogue())
        .thenReturn(
            List.of(
                AgentTool.builtin("getShellConfig", "Shell config", null, false, List.of()),
                AgentTool.remote(
                    "solutions",
                    "http://x",
                    "search-docs",
                    "Searches docs",
                    null,
                    false,
                    List.of(),
                    null),
                AgentTool.agent(
                    "staffing",
                    "http://y",
                    "matcher",
                    "Runs matching",
                    null,
                    List.of(),
                    "/agent/tasks")));

    List<AgentCatalogEntryDto> entries = service.catalogFor(user());

    assertEquals(3, entries.size());
    AgentCatalogEntryDto builtin = entries.get(0);
    assertEquals("getShellConfig", builtin.modelName());
    assertEquals("builtin", builtin.kind());
    assertNull(builtin.moduleKey());
    AgentCatalogEntryDto remote = entries.get(1);
    assertEquals("solutions_search_docs", remote.modelName());
    assertEquals("remote", remote.kind());
    assertEquals("solutions", remote.moduleKey());
    AgentCatalogEntryDto agent = entries.get(2);
    assertEquals("staffing_matcher", agent.modelName());
    assertEquals("agent", agent.kind());
  }

  @Test
  void filtersEntriesByDeclaredRoles() {
    when(registry.catalogue())
        .thenReturn(
            List.of(
                AgentTool.builtin("open_tool", "Open", null, false, List.of()),
                AgentTool.remote(
                    "solutions",
                    "http://x",
                    "admin_tool",
                    "Admin",
                    null,
                    false,
                    List.of("portal-solutions-admin"),
                    null)));

    assertEquals(1, service.catalogFor(user()).size());
    assertEquals(2, service.catalogFor(user("portal-solutions-admin")).size());
    assertTrue(
        service.catalogFor(user("portal-solutions-admin")).stream()
            .anyMatch(e -> "solutions_admin_tool".equals(e.modelName())));
  }

  @Test
  void userWithNullRolesOnlySeesUndeclaredTools() {
    when(registry.catalogue())
        .thenReturn(
            List.of(
                AgentTool.builtin("open_tool", "Open", null, false, List.of()),
                AgentTool.builtin("gated_tool", "Gated", null, false, List.of("portal-admin"))));
    PortalUser noRoles = new PortalUser("u1", "Dev", "d@x", null);

    List<AgentCatalogEntryDto> entries = service.catalogFor(noRoles);

    assertEquals(1, entries.size());
    assertEquals("open_tool", entries.get(0).modelName());
  }
}
