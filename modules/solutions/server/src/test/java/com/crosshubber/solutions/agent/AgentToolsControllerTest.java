package com.crosshubber.solutions.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.crosshubber.solutions.domain.ClientEntity;
import com.crosshubber.solutions.domain.ProjectEntity;
import com.crosshubber.solutions.domain.ProjectService;
import com.crosshubber.solutions.domain.Stage;
import com.crosshubber.solutions.security.AgentPrincipal;
import com.crosshubber.solutions.settings.ModuleSettingsService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Tool endpoint contract tests: envelope shape, dispatch results, fail-closed agent tasks. */
class AgentToolsControllerTest {

  private ProjectService projectService;
  private LlmClient llm;
  private ModuleSettingsService settings;
  private MockMvc mockMvc;
  private ProjectEntity project;

  @BeforeEach
  void setUp() {
    projectService = mock(ProjectService.class);
    llm = mock(LlmClient.class);
    settings = mock(ModuleSettingsService.class);
    when(settings.isToolEnabled(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    when(settings.agentEnabled()).thenReturn(true);
    when(settings.systemPrompt()).thenReturn(ModuleSettingsService.DEFAULT_SYSTEM_PROMPT);
    ObjectMapper mapper = new JsonMapper();
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new AgentToolsController(projectService, settings, mapper),
                new AgentTasksController(projectService, llm, settings, mapper))
            .build();
    ClientEntity client = new ClientEntity();
    client.setName("Acme");
    project = new ProjectEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(project, "id", UUID.randomUUID());
    project.setClient(client);
    project.setName("ERP rollout");
    project.setStage(Stage.IMPLEMENTATION);
    project.setHealth("at-risk");
    // AgentPrincipals.current() reads the security context (populated by the agent-call-token
    // filter in production); tests set it directly — standalone MockMvc has no filter chain.
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new AgentPrincipal("u1", "Dev Admin", List.of("solutions-user")),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_solutions-user"))));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private static UsernamePasswordAuthenticationToken principal(String... roles) {
    AgentPrincipal p = new AgentPrincipal("u1", "Dev Admin", List.of(roles));
    return new UsernamePasswordAuthenticationToken(
        p,
        null,
        java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
  }

  @Test
  void listProjectsReturnsPayloadDirectly() throws Exception {
    when(projectService.listProjects(isNull(), isNull(), isNull())).thenReturn(List.of(project));

    mockMvc
        .perform(
            post("/agent/tools/list_projects")
                .contentType("application/json")
                .content("{\"tool\":\"list_projects\",\"arguments\":{}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projects[0].name").value("ERP rollout"))
        .andExpect(jsonPath("$.projects[0].stage").value("implementation"));
  }

  @Test
  void unknownToolReturnsErrorPayload() throws Exception {
    mockMvc
        .perform(
            post("/agent/tools/nope")
                .contentType("application/json")
                .content("{\"tool\":\"nope\",\"arguments\":{}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.error").value("unknown tool: nope"));
  }

  @Test
  void disabledToolReturnsErrorPayload() throws Exception {
    when(settings.isToolEnabled("list_projects")).thenReturn(false);

    mockMvc
        .perform(
            post("/agent/tools/list_projects")
                .contentType("application/json")
                .content("{\"tool\":\"list_projects\",\"arguments\":{}}"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.error")
                .value("tool \"list_projects\" is disabled in the solutions module settings"));
  }

  @Test
  void tasksFailWhenAgentDisabledBySettings() throws Exception {
    when(settings.agentEnabled()).thenReturn(false);

    mockMvc
        .perform(post("/agent/tasks").contentType("application/json").content("{\"task\":\"s\"}"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.output").value("agent is disabled in the solutions module settings"));
  }

  @Test
  void updateProjectStageAppliesTransition() throws Exception {
    when(projectService.transition(any(), eq("delivery"), any(), any(), any()))
        .thenAnswer(inv -> project);

    mockMvc
        .perform(
            post("/agent/tools/update_project_stage")
                .contentType("application/json")
                .content(
                    "{\"tool\":\"update_project_stage\",\"arguments\":"
                        + "{\"projectId\":\""
                        + project.getId()
                        + "\",\"toStage\":\"delivery\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"))
        .andExpect(jsonPath("$.project.stage").value("implementation"));
  }

  @Test
  void missingArgumentsSurfaceAsErrorPayload() throws Exception {
    mockMvc
        .perform(
            post("/agent/tools/update_project_stage")
                .contentType("application/json")
                .content("{\"tool\":\"update_project_stage\",\"arguments\":{}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  void tasksFailsClosedWhenLlmNotConfigured() throws Exception {
    Mockito.doReturn(false).when(llm).isConfigured();

    mockMvc
        .perform(
            post("/agent/tasks").contentType("application/json").content("{\"task\":\"status?\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("failed"))
        .andExpect(jsonPath("$.output").value("llm is not configured on the solutions module"));
  }

  @Test
  void tasksWithoutTaskFail() throws Exception {
    mockMvc
        .perform(post("/agent/tasks").contentType("application/json").content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("failed"));
  }

  @Test
  void manifestMatchesImplementedToolNames() {
    assertThat(AgentToolsController.toolNames())
        .containsExactly(
            "list_projects",
            "get_project",
            "get_stage_history",
            "update_project_stage",
            "search_project_docs");
  }
}
