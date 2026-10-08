package com.crosshubber.portal.modules.registry.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.modules.aihub.agent.AgentTool;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentCategory;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentType;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentsService;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModulesService;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** AI plan D5: registry tool declarations (module-attributed) and handler passthrough. */
class RegistryAgentToolsTest {

  private ModulesService modulesService;
  private ModuleContentsService moduleContentsService;
  private RegistryAgentTools tools;
  private ObjectMapper mapper;

  private static final PortalUser USER = new PortalUser("u1", "Dev", "d@x", List.of());

  @BeforeEach
  void setUp() {
    modulesService = mock(ModulesService.class);
    moduleContentsService = mock(ModuleContentsService.class);
    mapper = new JacksonConfig().jsonMapper();
    tools = new RegistryAgentTools(modulesService, moduleContentsService, mapper);
  }

  @Test
  void declaresTwoModuleAttributedBuiltinTools() {
    List<AgentTool> declared = tools.tools();

    assertThat(declared).hasSize(2);
    assertThat(declared.get(0).name()).isEqualTo("listModules");
    assertThat(declared.get(1).name()).isEqualTo("listModuleContents");
    for (AgentTool tool : declared) {
      assertThat(tool.kind()).isEqualTo(AgentTool.Kind.BUILTIN);
      assertThat(tool.moduleKey()).isEqualTo("module-registry");
      assertThat(tool.id()).isEqualTo(tool.name());
      assertThat(tool.modelName()).isEqualTo(tool.name());
      assertThat(tool.mutates()).isFalse();
      assertThat(tool.roles()).isEmpty();
    }
    assertThat(declared.get(1).arguments().path("properties").has("moduleKey")).isTrue();
  }

  @Test
  void listModulesReturnsRowPerActiveModule() {
    ModuleEntity module = new ModuleEntity();
    module.setKey("ai-hub");
    module.setName("AI Hub");
    module.setActive(true);
    module.setBuiltin(true);
    module.setVersion("1.0");
    module.setManagedBy("manual");
    when(modulesService.list(false)).thenReturn(List.of(module));

    JsonNode rows = tools.handle(tools.tools().get(0), USER, mapper.createObjectNode());

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).path("key").asString()).isEqualTo("ai-hub");
    assertThat(rows.get(0).path("builtin").asBoolean()).isTrue();
  }

  @Test
  void listModuleContentsHonoursModuleKeyFilter() {
    ModuleContentEntity content = new ModuleContentEntity();
    content.setModuleKey("ai-hub");
    content.setContentKey("main");
    content.setCategory(ModuleContentCategory.APPLICATIONS);
    content.setName("AI Hub");
    content.setType(ModuleContentType.EMBEDDED);
    content.setActive(true);
    when(moduleContentsService.list("ai-hub")).thenReturn(List.of(content));

    ObjectNode args = mapper.createObjectNode();
    args.put("moduleKey", "ai-hub");
    JsonNode result = tools.handle(tools.tools().get(1), USER, args);

    assertThat(result.path("truncated").asBoolean()).isFalse();
    assertThat(result.path("contents")).hasSize(1);
    assertThat(result.path("contents").get(0).path("contentKey").asString()).isEqualTo("main");
  }

  @Test
  void unknownToolNameReturnsError() {
    JsonNode result =
        tools.handle(
            AgentTool.builtin("listSomething", "d", null, false, List.of()),
            USER,
            mapper.createObjectNode());

    assertThat(result.path("error").asString()).contains("unknown registry tool");
  }
}
