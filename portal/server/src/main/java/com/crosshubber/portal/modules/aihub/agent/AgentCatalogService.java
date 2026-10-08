package com.crosshubber.portal.modules.aihub.agent;

import java.util.List;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.security.PortalUser;

/**
 * Per-user view of the agent tool catalogue (phase 5): the registry entries the caller is
 * authorized for, mirroring {@link ToolDispatcher}'s RBAC semantics via {@code
 * AgentTool.authorizedFor}. Consumption toggles (user {@code ai} settings scope) are applied by the
 * chat pipeline ({@code AiHubChatService}); this listing stays toggle-agnostic.
 */
@Service
public class AgentCatalogService {

  private final ToolRegistry registry;

  public AgentCatalogService(ToolRegistry registry) {
    this.registry = registry;
  }

  /** Role-filtered catalogue entries for the authenticated user. */
  public List<AgentCatalogEntryDto> catalogFor(PortalUser user) {
    return registry.catalogue().stream()
        .filter(t -> t.authorizedFor(user.roles()))
        .map(AgentCatalogService::toDto)
        .toList();
  }

  private static AgentCatalogEntryDto toDto(AgentTool t) {
    return new AgentCatalogEntryDto(
        t.modelName(),
        t.name(),
        t.kind().name().toLowerCase(),
        t.moduleKey(),
        t.description(),
        t.mutates());
  }
}
