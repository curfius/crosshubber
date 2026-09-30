package com.crosshubber.portal.modules.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.modules.registry.manifest.InstallService;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModulesService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI plan D3/F2: catalogue hydration from active manifests — remote tools and sub-agent entries
 * (manifest {@code agentContributions.agents[]}) resolve together, skip incomplete modules, and
 * cache for 60s.
 */
class ToolRegistryTest {

  private ModulesService modulesService;
  private InstallService installService;
  private BuiltinToolHandlers builtinHandlers;
  private ToolRegistry registry;
  private ObjectMapper mapper;

  private static final String MANIFEST =
      """
      {
        "agentContributions": {
          "tools": [
            {"name": "list_projects", "description": "Lists projects", "mutates": false,
             "roles": ["solutions-user"]}
          ],
          "agents": [
            {"name": "projects-agent", "description": "Answers project status questions",
             "endpoint": "/agent/tasks", "roles": ["solutions-user"]}
          ]
        }
      }
      """;

  @BeforeEach
  void setUp() throws Exception {
    modulesService = mock(ModulesService.class);
    installService = mock(InstallService.class);
    builtinHandlers = mock(BuiltinToolHandlers.class);
    mapper = new JacksonConfig().jsonMapper();
    registry = new ToolRegistry(modulesService, installService, builtinHandlers, mapper);
    when(builtinHandlers.tools())
        .thenReturn(List.of(AgentTool.builtin("getShellConfig", "config", null, false, List.of())));
  }

  private ModuleEntity module(String key, String baseUrl) {
    ModuleEntity module = mock(ModuleEntity.class);
    when(module.getKey()).thenReturn(key);
    when(module.getBaseUrl()).thenReturn(baseUrl);
    return module;
  }

  private void listReturns(ModuleEntity... modules) {
    when(modulesService.list(false)).thenReturn(List.of(modules));
  }

  @Test
  void catalogueHydratesToolsAndSubAgentsFromActiveManifest() throws Exception {
    listReturns(module("solutions", "http://solutions:8090"));
    when(installService.activeManifest("solutions")).thenReturn(mapper.readTree(MANIFEST));

    List<AgentTool> catalogue = registry.catalogue();

    assertThat(catalogue).hasSize(3);
    assertThat(catalogue.get(0).kind()).isEqualTo(AgentTool.Kind.BUILTIN);

    AgentTool tool = catalogue.get(1);
    assertThat(tool.kind()).isEqualTo(AgentTool.Kind.REMOTE);
    assertThat(tool.modelName()).isEqualTo("solutions_list_projects");
    assertThat(tool.path()).isNull();

    AgentTool agent = catalogue.get(2);
    assertThat(agent.kind()).isEqualTo(AgentTool.Kind.AGENT);
    assertThat(agent.id()).isEqualTo("solutions:projects-agent");
    assertThat(agent.modelName()).isEqualTo("solutions_projects_agent");
    assertThat(agent.baseUrl()).isEqualTo("http://solutions:8090");
    assertThat(agent.path()).isEqualTo("/agent/tasks");
    assertThat(agent.mutates()).isFalse();
    assertThat(agent.roles()).containsExactly("solutions-user");
  }

  @Test
  void subAgentArgumentsSchemaRequiresTask() throws Exception {
    listReturns(module("solutions", "http://solutions:8090"));
    when(installService.activeManifest("solutions")).thenReturn(mapper.readTree(MANIFEST));

    JsonNode args = registry.byModelName("solutions_projects_agent").arguments();

    assertThat(args.path("type").asString()).isEqualTo("object");
    assertThat(args.path("required").toString()).contains("task");
    assertThat(args.path("properties").has("task")).isTrue();
    assertThat(args.path("properties").has("expectedOutput")).isTrue();
  }

  @Test
  void byModelNameFindsSubAgent() throws Exception {
    listReturns(module("solutions", "http://solutions:8090"));
    when(installService.activeManifest("solutions")).thenReturn(mapper.readTree(MANIFEST));

    AgentTool agent = registry.byModelName("solutions_projects_agent");

    assertThat(agent).isNotNull();
    assertThat(agent.kind()).isEqualTo(AgentTool.Kind.AGENT);
    assertThat(registry.remoteAgents()).hasSize(1);
    assertThat(registry.remoteTools()).hasSize(1);
  }

  @Test
  void moduleWithoutBaseUrlOrManifestIsSkipped() {
    listReturns(module("solutions", null), module("staffing", "http://staffing:8091"));
    when(installService.activeManifest("staffing")).thenReturn(null);

    assertThat(registry.remoteTools()).isEmpty();
    assertThat(registry.remoteAgents()).isEmpty();
    assertThat(registry.catalogue()).hasSize(1); // builtins only
  }

  @Test
  void remoteContributionsAreCached() throws Exception {
    listReturns(module("solutions", "http://solutions:8090"));
    when(installService.activeManifest("solutions")).thenReturn(mapper.readTree(MANIFEST));

    registry.catalogue();
    registry.byModelName("solutions_projects_agent");

    verify(modulesService, times(1)).list(false);
    verify(installService, times(1)).activeManifest("solutions");
  }
}
