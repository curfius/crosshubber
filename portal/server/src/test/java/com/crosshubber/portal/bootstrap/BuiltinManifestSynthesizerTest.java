package com.crosshubber.portal.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.modules.aihub.agent.AgentTool;
import com.crosshubber.portal.modules.aihub.agent.BuiltinToolProvider;
import com.crosshubber.portal.modules.registry.manifest.ManifestValidator;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI plan D5 (B1 storage): builtin manifest synthesis — every catalog module parses, settings
 * entries fold into adminSettings, agentic tools map to the owning module only, attribution and
 * content-category mistakes fail fast.
 */
class BuiltinManifestSynthesizerTest {

  private final ObjectMapper mapper = new JacksonConfig().jsonMapper();
  private final ManifestValidator validator = newValidator();

  private static ManifestValidator newValidator() {
    PortalProperties props = new PortalProperties();
    props.setPublicBaseUrl("http://localhost:28084");
    return new ManifestValidator(props, (JsonMapper) new JacksonConfig().jsonMapper());
  }

  private static BuiltinToolProvider provider(AgentTool... tools) {
    return new BuiltinToolProvider() {
      @Override
      public List<AgentTool> tools() {
        return List.of(tools);
      }

      @Override
      public JsonNode handle(AgentTool tool, PortalUser user, JsonNode args) {
        return tool.arguments();
      }
    };
  }

  private static List<BuiltinToolProvider> defaultProviders() {
    return List.of(
        provider(
            AgentTool.builtin(
                "msgcenter", "msgcenter_list", "Lists the inbox.", null, false, List.of()),
            AgentTool.builtin(
                "msgcenter",
                "msgcenter_send",
                "Publishes an envelope.",
                null,
                true,
                List.of("portal-msgcenter-edit"))),
        provider(
            AgentTool.builtin(
                "module-registry", "listModules", "Lists modules.", null, false, List.of()),
            AgentTool.builtin(
                "module-registry",
                "listModuleContents",
                "Lists a module's contents.",
                null,
                false,
                List.of()),
            AgentTool.builtin(
                "navigation", "getInstanceSettings", "Reads settings.", null, false, List.of())),
        provider(AgentTool.builtin("getShellConfig", "Shell config.", null, false, List.of())));
  }

  private BuiltinManifestSynthesizer synthesizer(List<BuiltinToolProvider> providers) {
    return new BuiltinManifestSynthesizer(providers, validator, mapper);
  }

  @Test
  void everyCatalogModuleSynthesizesToAValidManifest() {
    BuiltinManifestSynthesizer synthesizer = synthesizer(defaultProviders());

    for (EmbeddedCatalog.Module mod : EmbeddedCatalog.CATALOG) {
      JsonNode manifest = synthesizer.synthesize(mod);
      assertThat(manifest.path("manifestVersion").asInt()).isEqualTo(1);
      assertThat(manifest.path("key").asString()).isEqualTo(mod.key());
      assertThat(manifest.path("name").asString()).isEqualTo(mod.name());
      assertThat(manifest.path("baseUrl").asString())
          .isEqualTo(BuiltinManifestSynthesizer.SENTINEL_BASE_URL);
      for (String group : List.of("applications", "features", "adminSettings", "userSettings")) {
        assertThat(manifest.path("shell").path(group).isArray())
            .as("%s.%s", mod.key(), group)
            .isTrue();
      }
      for (String block : List.of("tools", "skills", "agents", "knowledge")) {
        assertThat(manifest.path("agentic").path(block).isArray())
            .as("%s.agentic.%s", mod.key(), block)
            .isTrue();
      }
      assertThat(manifest.path("security").path("roles").isArray()).isTrue();
    }
  }

  @Test
  void settingsCategoryEntriesFoldIntoAdminSettings() {
    EmbeddedCatalog.Module registry = catalogModule("module-registry");

    JsonNode shell = synthesizer(defaultProviders()).synthesize(registry).path("shell");

    assertThat(shell.path("applications")).isEmpty();
    JsonNode admin = shell.path("adminSettings");
    assertThat(admin).hasSize(1);
    assertThat(admin.get(0).path("key").asString()).isEqualTo("main");
    assertThat(admin.get(0).path("type").asString()).isEqualTo("embedded");
    assertThat(admin.get(0).path("loadPath").asString()).isEqualTo("module-registry");
  }

  @Test
  void agenticToolsMapToTheOwningModuleOnly() {
    BuiltinManifestSynthesizer synthesizer = synthesizer(defaultProviders());

    JsonNode msgcenter = synthesizer.synthesize(catalogModule("msgcenter")).path("agentic");
    JsonNode tools = msgcenter.path("tools");
    assertThat(tools).hasSize(2);
    assertThat(tools.get(0).path("name").asString()).isEqualTo("msgcenter_list");
    assertThat(tools.get(0).path("mutates").asBoolean()).isFalse();
    assertThat(tools.get(1).path("name").asString()).isEqualTo("msgcenter_send");
    assertThat(tools.get(1).path("mutates").asBoolean()).isTrue();
    assertThat(tools.get(1).path("roles").toString()).isEqualTo("[\"portal-msgcenter-edit\"]");
    assertThat(msgcenter.path("skills")).isEmpty();

    JsonNode userSettings = synthesizer.synthesize(catalogModule("user-settings"));
    assertThat(userSettings.path("agentic").path("tools")).isEmpty();
  }

  @Test
  void camelCaseCatalogueNamesNormalizeToSnakeInManifest() {
    BuiltinManifestSynthesizer synthesizer = synthesizer(defaultProviders());

    JsonNode registry =
        synthesizer.synthesize(catalogModule("module-registry")).path("agentic").path("tools");
    assertThat(registry).hasSize(2);
    assertThat(registry.get(0).path("name").asString()).isEqualTo("list_modules");
    assertThat(registry.get(1).path("name").asString()).isEqualTo("list_module_contents");

    JsonNode navigation =
        synthesizer.synthesize(catalogModule("navigation")).path("agentic").path("tools");
    assertThat(navigation).hasSize(1);
    assertThat(navigation.get(0).path("name").asString()).isEqualTo("get_instance_settings");

    JsonNode msgcenter =
        synthesizer.synthesize(catalogModule("msgcenter")).path("agentic").path("tools");
    assertThat(msgcenter.get(0).path("name").asString()).isEqualTo("msgcenter_list");
  }

  @Test
  void unknownModuleAttributionFailsAtStartup() {
    AgentTool ghost = AgentTool.builtin("ghost-module", "ghostTool", "d", null, false, List.of());

    assertThatThrownBy(() -> synthesizer(List.of(provider(ghost))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unknown embedded module \"ghost-module\"");
  }

  @Test
  void unknownContentCategoryFailsSynthesis() {
    EmbeddedCatalog.Module bogus =
        new EmbeddedCatalog.Module(
            "bogus",
            "Bogus",
            null,
            "1.0",
            List.of(),
            List.of(
                new EmbeddedCatalog.Entry(
                    "main", "mystery", "Main", "bogus-load", null, 0, false, List.of())));

    assertThatThrownBy(() -> synthesizer(defaultProviders()).synthesize(bogus))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unknown category \"mystery\"");
  }

  private static EmbeddedCatalog.Module catalogModule(String key) {
    return EmbeddedCatalog.CATALOG.stream()
        .filter(m -> m.key().equals(key))
        .findFirst()
        .orElseThrow();
  }
}
