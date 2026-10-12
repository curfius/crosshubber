package com.crosshubber.talenthub.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.crosshubber.talenthub.domain.RfpCaptureService;
import com.crosshubber.talenthub.domain.RfpDtos;
import com.crosshubber.talenthub.domain.RfpEntity;
import com.crosshubber.talenthub.domain.RfpPhase;
import com.crosshubber.talenthub.domain.RfpWorkflowService;
import com.crosshubber.talenthub.security.AgentPrincipal;
import com.crosshubber.talenthub.settings.ModuleSettingsService;

import tools.jackson.databind.json.JsonMapper;

/** Tool endpoint contract tests: envelope shape, dispatch results, manifest sync. */
class AgentToolsControllerTest {

  private ModuleSettingsService settings;
  private RfpCaptureService captureService;
  private RfpWorkflowService workflowService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    settings = mock(ModuleSettingsService.class);
    captureService = mock(RfpCaptureService.class);
    workflowService = mock(RfpWorkflowService.class);
    when(settings.isToolEnabled(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new AgentToolsController(
                    settings,
                    captureService,
                    workflowService,
                    mock(com.crosshubber.talenthub.domain.RfpQueryService.class),
                    mock(com.crosshubber.talenthub.domain.CandidateProcessService.class),
                    mock(com.crosshubber.talenthub.domain.CandidateService.class),
                    mock(com.crosshubber.talenthub.domain.MatchRunService.class),
                    mock(com.crosshubber.talenthub.domain.AnalyticsService.class),
                    new JsonMapper()))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new AgentPrincipal("u1", "Dev Admin", List.of("staffing-manager")),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_staffing-manager"))));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
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
    when(settings.isToolEnabled("create_rfp")).thenReturn(false);

    mockMvc
        .perform(
            post("/agent/tools/create_rfp")
                .contentType("application/json")
                .content("{\"tool\":\"create_rfp\",\"arguments\":{}}"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.error")
                .value("tool \"create_rfp\" is disabled in the talent-hub module settings"));
  }

  @Test
  void createRfpReturnsTheDraftShape() throws Exception {
    RfpEntity draft = new RfpEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(draft, "id", UUID.randomUUID());
    draft.setClientId(UUID.randomUUID());
    draft.setTitle("Java squad");
    when(captureService.capture(any(), anyString())).thenReturn(RfpDtos.toDto(draft));

    mockMvc
        .perform(
            post("/agent/tools/create_rfp")
                .contentType("application/json")
                .content(
                    "{\"tool\":\"create_rfp\",\"arguments\":{\"description\":\"Need java\","
                        + "\"newClient\":{\"name\":\"Acme\"}}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Java squad"))
        .andExpect(jsonPath("$.phase").value("draft"))
        .andExpect(jsonPath("$.source").value("manual"));
  }

  @Test
  void approveRfpReturnsTheUpdatedPhase() throws Exception {
    RfpEntity approved = new RfpEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(approved, "id", UUID.randomUUID());
    approved.setClientId(UUID.randomUUID());
    approved.setTitle("Java squad");
    approved.setPhase(RfpPhase.APPROVED);
    when(workflowService.approve(any(), anyString()))
        .thenReturn(new RfpWorkflowService.WorkflowResult(approved, List.of()));

    mockMvc
        .perform(
            post("/agent/tools/approve_rfp")
                .contentType("application/json")
                .content(
                    "{\"tool\":\"approve_rfp\",\"arguments\":{\"rfpId\":\""
                        + approved.getId()
                        + "\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phase").value("approved"));
  }

  @Test
  void missingArgumentsSurfaceAsErrorPayload() throws Exception {
    mockMvc
        .perform(
            post("/agent/tools/approve_rfp")
                .contentType("application/json")
                .content("{\"tool\":\"approve_rfp\",\"arguments\":{}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  void manifestMatchesImplementedToolNames() {
    assertThat(AgentToolsController.toolNames())
        .containsExactly(
            "list_rfps",
            "get_rfp",
            "create_rfp",
            "approve_rfp",
            "list_candidate_processes",
            "search_candidates",
            "open_candidate_process",
            "advance_process_stage",
            "close_rfp",
            "assess_fit",
            "match_candidates",
            "create_match_run",
            "get_rfp_analytics");
  }
}
