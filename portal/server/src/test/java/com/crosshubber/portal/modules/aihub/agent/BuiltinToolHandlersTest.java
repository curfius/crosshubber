package com.crosshubber.portal.modules.aihub.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI plan D5: provider aggregation — deterministic order (platform-global first, then by module
 * key), duplicate-name and wrong-kind fail-fast, dispatch delegation to the owning provider.
 */
class BuiltinToolHandlersTest {

  private final ObjectMapper mapper = new JacksonConfig().jsonMapper();

  private static final PortalUser USER = new PortalUser("u1", "Dev", "d@x", List.of());

  private final class FakeProvider implements BuiltinToolProvider {

    private final String marker;
    private final List<AgentTool> declared;

    private FakeProvider(String marker, AgentTool... tools) {
      this.marker = marker;
      this.declared = List.of(tools);
    }

    @Override
    public List<AgentTool> tools() {
      return declared;
    }

    @Override
    public JsonNode handle(AgentTool tool, PortalUser user, JsonNode args) {
      ObjectNode node = mapper.createObjectNode();
      node.put("provider", marker);
      node.put("tool", tool.name());
      return node;
    }
  }

  private FakeProvider provider(String marker, AgentTool... tools) {
    return new FakeProvider(marker, tools);
  }

  @Test
  void aggregatesGlobalsFirstThenByModuleKey() {
    AgentTool nav =
        AgentTool.builtin("navigation", "getInstanceSettings", "s", null, false, List.of());
    AgentTool global = AgentTool.builtin("getShellConfig", "c", null, false, List.of());
    AgentTool listModules =
        AgentTool.builtin("module-registry", "listModules", "l", null, false, List.of());
    AgentTool listContents =
        AgentTool.builtin("module-registry", "listModuleContents", "l", null, false, List.of());

    BuiltinToolHandlers handlers =
        new BuiltinToolHandlers(
            List.of(
                provider("nav", nav),
                provider("shell", global),
                provider("reg", listModules, listContents)),
            mapper);

    assertThat(handlers.tools())
        .extracting(AgentTool::name)
        .containsExactly(
            "getShellConfig", "listModules", "listModuleContents", "getInstanceSettings");
  }

  @Test
  void duplicateToolNamesFailBoot() {
    AgentTool a = AgentTool.builtin("listModules", "a", null, false, List.of());
    AgentTool b = AgentTool.builtin("listModules", "b", null, false, List.of());

    assertThatThrownBy(
            () -> new BuiltinToolHandlers(List.of(provider("one", a), provider("two", b)), mapper))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate builtin agent tool name: listModules");
  }

  @Test
  void nonBuiltinDeclarationFailsBoot() {
    AgentTool remote =
        AgentTool.remote(
            "solutions", "http://x", "list_projects", "d", null, false, List.of(), null);

    assertThatThrownBy(() -> new BuiltinToolHandlers(List.of(provider("bad", remote)), mapper))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("declared non-builtin tool list_projects");
  }

  @Test
  void dispatchDelegatesToOwningProvider() {
    AgentTool global = AgentTool.builtin("getShellConfig", "c", null, false, List.of());
    AgentTool msg = AgentTool.builtin("msgcenter", "msgcenter_list", "l", null, false, List.of());
    BuiltinToolHandlers handlers =
        new BuiltinToolHandlers(List.of(provider("shell", global), provider("mc", msg)), mapper);

    JsonNode args = mapper.createObjectNode();
    assertThat(handlers.handle(global, USER, args).path("provider").asString()).isEqualTo("shell");
    assertThat(handlers.handle(msg, USER, args).path("provider").asString()).isEqualTo("mc");
  }

  @Test
  void unknownToolReturnsErrorPayload() {
    BuiltinToolHandlers handlers = new BuiltinToolHandlers(List.of(provider("shell")), mapper);

    JsonNode result =
        handlers.handle(
            AgentTool.builtin("ghostTool", "g", null, false, List.of()),
            USER,
            mapper.createObjectNode());

    assertThat(result.path("error").asString()).contains("unknown builtin tool: ghostTool");
  }
}
