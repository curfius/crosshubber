package com.crosshubber.portal.modules.aihub.agent;

import java.util.List;

import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;

/**
 * SPI for portal-native (built-in) agent tools: each owning feature package declares its tools and
 * handles their execution through this interface; aihub only aggregates the beans (AI plan D5,
 * provider-as-declaration). Tools whose {@link AgentTool#moduleKey()} is null are platform-global
 * reads (never gated by module state); attributed tools disappear from the catalogue while their
 * owning module is inactive and are synthesized into that module's boot-validated manifest.
 *
 * <p>Implementations are plain {@code @Service} beans — Spring collects them, so features never
 * import each other's internals (AGENTS.md invariant).
 */
public interface BuiltinToolProvider {

  /**
   * Tool declarations in stable order. Names must be unique across all providers — the aggregator
   * fails boot on duplicates. The {@code moduleKey} on each tool must be null or an {@link
   * com.crosshubber.portal.bootstrap.EmbeddedCatalog} key (validated at boot).
   */
  List<AgentTool> tools();

  /**
   * Executes one of this provider's tools.
   *
   * @param tool one of the tools returned by {@link #tools()}
   * @param user the caller — handlers see the same identity the HTTP layer would
   * @param args tool arguments, never null
   * @return the tool result payload
   */
  JsonNode handle(AgentTool tool, PortalUser user, JsonNode args);
}
