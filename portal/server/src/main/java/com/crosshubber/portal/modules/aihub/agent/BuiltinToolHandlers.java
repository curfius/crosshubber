package com.crosshubber.portal.modules.aihub.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Aggregator for portal-native agent tools (AI plan D5, provider-as-declaration): collects the
 * {@link BuiltinToolProvider} beans that owning feature packages declare and routes dispatch to the
 * owning provider. Feature-to-feature imports are gone — aihub only knows the SPI.
 *
 * <p>Aggregation order is deterministic: platform-global providers (no module attribution) first,
 * then attributed ones by module key, each keeping its declaration order. Duplicate tool names
 * across providers fail boot.
 */
@Service
public class BuiltinToolHandlers {

  private final Map<String, BuiltinToolProvider> providersByName;
  private final List<AgentTool> tools;
  private final ObjectMapper objectMapper;

  public BuiltinToolHandlers(List<BuiltinToolProvider> providers, ObjectMapper objectMapper) {
    List<BuiltinToolProvider> ordered = new ArrayList<>(providers);
    ordered.sort(
        Comparator.comparing(
            BuiltinToolHandlers::moduleKeyOf, Comparator.nullsFirst(Comparator.naturalOrder())));
    Map<String, BuiltinToolProvider> byName = new LinkedHashMap<>();
    List<AgentTool> all = new ArrayList<>();
    for (BuiltinToolProvider provider : ordered) {
      for (AgentTool tool : provider.tools()) {
        if (tool.kind() != AgentTool.Kind.BUILTIN) {
          throw new IllegalStateException(
              "builtin tool provider "
                  + provider.getClass().getSimpleName()
                  + " declared non-builtin tool "
                  + tool.name());
        }
        if (byName.putIfAbsent(tool.name(), provider) != null) {
          throw new IllegalStateException("duplicate builtin agent tool name: " + tool.name());
        }
        all.add(tool);
      }
    }
    this.providersByName = Map.copyOf(byName);
    this.tools = List.copyOf(all);
    this.objectMapper = objectMapper;
  }

  /** The built-in tool catalogue, in deterministic provider order (AI plan B1/D5). */
  public List<AgentTool> tools() {
    return tools;
  }

  /**
   * Executes a built-in tool by delegating to the owning provider.
   *
   * @param tool the built-in tool (kind BUILTIN)
   * @param user the caller — handlers see the same identity the HTTP layer would
   * @param args tool arguments, never null
   * @return the tool result payload
   */
  public JsonNode handle(AgentTool tool, PortalUser user, JsonNode args) {
    BuiltinToolProvider provider = providersByName.get(tool.name());
    if (provider == null) {
      ObjectNode node = objectMapper.createObjectNode();
      node.put("error", "unknown builtin tool: " + tool.name());
      return node;
    }
    return provider.handle(tool, user, args);
  }

  /** Module attribution of a provider's first tool — all its tools share one owner. */
  private static String moduleKeyOf(BuiltinToolProvider provider) {
    List<AgentTool> declared = provider.tools();
    return declared.isEmpty() ? null : declared.get(0).moduleKey();
  }
}
