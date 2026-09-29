package com.crosshubber.staffing.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.crosshubber.staffing.domain.MatchingService;
import com.crosshubber.staffing.domain.RfpEntity;
import com.crosshubber.staffing.domain.RfpStatus;
import com.crosshubber.staffing.domain.StaffingService;
import com.crosshubber.staffing.security.AgentPrincipal;

import tools.jackson.databind.json.JsonMapper;

/** Tool endpoint contract tests: envelope shape, dispatch results, mutating tool. */
class AgentToolsControllerTest {

  private StaffingService staffingService;
  private MockMvc mockMvc;
  private RfpEntity rfp;

  @BeforeEach
  void setUp() {
    staffingService = mock(StaffingService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new AgentToolsController(staffingService, new JsonMapper()))
            .build();
    rfp = new RfpEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(rfp, "id", UUID.randomUUID());
    rfp.setClient("Acme");
    rfp.setTitle("Java squad");
    rfp.setKind("rfp");
    rfp.setStatus(RfpStatus.ANALYZING);
    rfp.setDeadline(Instant.now().plusSeconds(86400));
    rfp.getRequirements().put("skills", List.of("java", "spring"));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new AgentPrincipal("u1", "Dev Admin", List.of("staffing-user")),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_staffing-user"))));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void listRfpsReturnsPayloadDirectly() throws Exception {
    when(staffingService.listRfps(isNullStatus())).thenReturn(List.of(rfp));

    mockMvc
        .perform(
            post("/agent/tools/list_rfps")
                .contentType("application/json")
                .content("{\"tool\":\"list_rfps\",\"arguments\":{}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rfps[0].title").value("Java squad"))
        .andExpect(jsonPath("$.rfps[0].status").value("analyzing"));
  }

  private static RfpStatus isNullStatus() {
    return org.mockito.ArgumentMatchers.isNull();
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
  void matchCandidatesUsesRfpRequirements() throws Exception {
    when(staffingService.getRfp(any())).thenReturn(rfp);
    when(staffingService.matchCandidates(any(), eq(3)))
        .thenReturn(
            List.of(
                new MatchingService.ScoredCandidate(UUID.randomUUID(), "Ana", 9, "skills: java"),
                new MatchingService.ScoredCandidate(
                    UUID.randomUUID(), "Bruno", 2, "no direct requirement matches")));

    mockMvc
        .perform(
            post("/agent/tools/match_candidates")
                .contentType("application/json")
                .content(
                    "{\"tool\":\"match_candidates\",\"arguments\":{\"rfpId\":\""
                        + rfp.getId()
                        + "\",\"topN\":3}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matches[0].name").value("Ana"))
        .andExpect(jsonPath("$.matches[0].score").value(9))
        .andExpect(jsonPath("$.matches[1].rationale").value("no direct requirement matches"));
  }

  @Test
  void createMatchRunPersistsAndReturnsResults() throws Exception {
    var run = new com.crosshubber.staffing.domain.MatchRunEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
    run.setRfpId(rfp.getId());
    run.setStatus(com.crosshubber.staffing.domain.MatchRunEntity.RunStatus.DONE);
    run.setResults(List.of(Map.of("name", "Ana", "score", 9)));
    when(staffingService.createMatchRun(any(), eq(5), any())).thenReturn(run);

    mockMvc
        .perform(
            post("/agent/tools/create_match_run")
                .contentType("application/json")
                .content(
                    "{\"tool\":\"create_match_run\",\"arguments\":{\"rfpId\":\""
                        + rfp.getId()
                        + "\",\"topN\":5}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"))
        .andExpect(jsonPath("$.results[0].name").value("Ana"));
  }

  @Test
  void missingArgumentsSurfaceAsErrorPayload() throws Exception {
    mockMvc
        .perform(
            post("/agent/tools/get_rfp")
                .contentType("application/json")
                .content("{\"tool\":\"get_rfp\",\"arguments\":{}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  void manifestMatchesImplementedToolNames() {
    assertThat(AgentToolsController.toolNames())
        .containsExactly(
            "list_rfps", "get_rfp", "search_cvs", "match_candidates", "create_match_run");
  }
}
