package com.crosshubber.portal.bootstrap;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.crosshubber.portal.modules.aihub.agent.AgentTool;
import com.crosshubber.portal.modules.aihub.agent.BuiltinToolProvider;
import com.crosshubber.portal.modules.registry.manifest.ManifestValidator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Synthesizes the manifest for each embedded (builtin) module (AI plan D5, B1 storage): content
 * comes from {@link EmbeddedCatalog} — {@code settings}-category entries fold into the {@code
 * adminSettings} group, mirroring the registry UI's category mapping — and {@code agentic.tools[]}
 * from the owning feature's {@link BuiltinToolProvider} declarations. The result is parsed through
 * {@link ManifestValidator} immediately (fail-fast: a bad declaration aborts boot) and returned
 * normalized, so the Reconciler can re-assert it as the module's ACTIVE version row.
 *
 * <p>The sentinel {@code baseUrl} satisfies manifest validation; embedded entries never carry a
 * {@code path}, so URL resolution and the portal-origin recursion guard never engage.
 */
@Component
public class BuiltinManifestSynthesizer {

  /** Satisfies {@code baseUrl} validation for modules that have no upstream. */
  static final String SENTINEL_BASE_URL = "portal://builtin";

  private final List<BuiltinToolProvider> providers;
  private final ManifestValidator validator;
  private final ObjectMapper objectMapper;

  public BuiltinManifestSynthesizer(
      List<BuiltinToolProvider> providers, ManifestValidator validator, ObjectMapper objectMapper) {
    validateAttribution(providers);
    this.providers = providers;
    this.validator = validator;
    this.objectMapper = objectMapper;
  }

  /** Synthesizes and validates the manifest for one catalog module. */
  public JsonNode synthesize(EmbeddedCatalog.Module mod) {
    ObjectNode manifest = objectMapper.createObjectNode();
    manifest.put("manifestVersion", 1);
    manifest.put("key", mod.key());
    manifest.put("name", mod.name());
    manifest.put("baseUrl", SENTINEL_BASE_URL);

    ObjectNode shell = manifest.putObject("shell");
    ArrayNode applications = shell.putArray("applications");
    ArrayNode features = shell.putArray("features");
    ArrayNode adminSettings = shell.putArray("adminSettings");
    ArrayNode userSettings = shell.putArray("userSettings");
    for (EmbeddedCatalog.Entry ep : mod.moduleContents()) {
      ObjectNode entry = objectMapper.createObjectNode();
      entry.put("key", ep.contentKey());
      entry.put("name", ep.name());
      entry.put("type", "embedded");
      entry.put("loadPath", ep.loadPath());
      entry.put("multi", ep.multi());
      if (ep.roles() != null && !ep.roles().isEmpty()) {
        ArrayNode roles = entry.putArray("requiredRoles");
        ep.roles().forEach(roles::add);
      }
      switch (ep.category()) {
        case "applications" -> applications.add(entry);
        case "features" -> features.add(entry);
        case "settings", "admin-settings" -> adminSettings.add(entry);
        case "user-settings" -> userSettings.add(entry);
        default ->
            throw new IllegalStateException(
                "builtin module \""
                    + mod.key()
                    + "\" entry \""
                    + ep.contentKey()
                    + "\" has unknown category \""
                    + ep.category()
                    + "\"");
      }
    }

    manifest.putObject("security").putArray("roles");
    ObjectNode events = manifest.putObject("events");
    events.putArray("published");
    events.putArray("consumed");

    ObjectNode agentic = manifest.putObject("agentic");
    ArrayNode toolEntries = agentic.putArray("tools");
    for (AgentTool tool : providers.stream().flatMap(p -> p.tools().stream()).toList()) {
      if (!mod.key().equals(tool.moduleKey())) {
        continue;
      }
      ObjectNode entry = objectMapper.createObjectNode();
      entry.put("name", manifestToolName(tool.name()));
      entry.put("description", tool.description());
      if (tool.arguments() != null) {
        entry.set("arguments", tool.arguments());
      }
      entry.put("mutates", tool.mutates());
      if (tool.roles() != null && !tool.roles().isEmpty()) {
        ArrayNode roles = entry.putArray("roles");
        tool.roles().forEach(roles::add);
      }
      toolEntries.add(entry);
    }
    agentic.putArray("skills");
    agentic.putArray("agents");
    agentic.putArray("knowledge");

    ManifestValidator.Result parsed = validator.parse(manifest);
    if (!parsed.ok()) {
      throw new IllegalStateException(
          "builtin module \""
              + mod.key()
              + "\" synthesized invalid manifest: "
              + ManifestValidator.formatIssues(parsed.issues()));
    }
    return parsed.manifest();
  }

  /**
   * Manifest tool names must match {@code Keys.AGENT_NAME_RE} (snake), while the model-facing
   * catalogue name stays camelCase for prompt/audit continuity ({@code listModules}). The manifest
   * entry is display-only for builtins — dispatch resolves from the code declarations — so it
   * carries the snake form ({@code list_modules}), matching what remote modules already declare.
   * Idempotent on already-snake names.
   */
  static String manifestToolName(String name) {
    StringBuilder out = new StringBuilder(name.length() + 4);
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isUpperCase(c)) {
        if (i > 0) {
          out.append('_');
        }
        out.append(Character.toLowerCase(c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  /**
   * A declared moduleKey must name an embedded module — a typo would silently strand the tool
   * outside every manifest and the active-gate. Fails boot at bean creation.
   */
  private static void validateAttribution(List<BuiltinToolProvider> providers) {
    Set<String> catalogKeys = new HashSet<>();
    for (EmbeddedCatalog.Module mod : EmbeddedCatalog.CATALOG) {
      catalogKeys.add(mod.key());
    }
    for (BuiltinToolProvider provider : providers) {
      for (AgentTool tool : provider.tools()) {
        if (tool.moduleKey() != null && !catalogKeys.contains(tool.moduleKey())) {
          throw new IllegalStateException(
              "builtin tool "
                  + tool.name()
                  + " is attributed to unknown embedded module \""
                  + tool.moduleKey()
                  + "\"");
        }
      }
    }
  }
}
