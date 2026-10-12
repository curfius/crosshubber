package com.crosshubber.talenthub.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import com.crosshubber.talenthub.domain.RfpRepository;
import com.crosshubber.talenthub.domain.RfpWorkflowService;
import com.crosshubber.talenthub.security.AgentPrincipal;

/** RfpController (P1.4): manager gates on capture/approve, drafts shape. */
class RfpControllerTest {

  private RfpCaptureService captureService;
  private RfpWorkflowService workflowService;
  private RfpRepository rfpRepository;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    captureService = mock(RfpCaptureService.class);
    workflowService = mock(RfpWorkflowService.class);
    rfpRepository = mock(RfpRepository.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new RfpController(
                    captureService,
                    workflowService,
                    mock(com.crosshubber.talenthub.domain.RfpQueryService.class),
                    rfpRepository))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    asUser("staffing-manager");
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private void asUser(String... roles) {
    AgentPrincipal p = new AgentPrincipal("u1", "Dev Admin", List.of(roles));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                p,
                null,
                java.util.Arrays.stream(roles)
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList()));
  }

  private static RfpEntity draftEntity() {
    RfpEntity e = new RfpEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
    e.setClientId(UUID.randomUUID());
    e.setTitle("Java squad");
    e.setDeadline(Instant.now().plusSeconds(3600));
    return e;
  }

  private static RfpDtos.RfpDto draftDto() {
    RfpEntity e = draftEntity();
    return RfpDtos.toDto(e);
  }

  @Test
  void captureRejectsRecruiter() throws Exception {
    asUser("staffing-recruiter");

    mockMvc
        .perform(
            post("/api/rfps/capture")
                .contentType("application/json")
                .content("{\"description\":\"d\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("staffing-manager role required"));
  }

  @Test
  void captureReturns201() throws Exception {
    when(captureService.capture(any(), anyString())).thenReturn(draftDto());

    mockMvc
        .perform(
            post("/api/rfps/capture")
                .contentType("application/json")
                .content("{\"description\":\"d\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.title").value("Java squad"))
        .andExpect(jsonPath("$.phase").value("draft"));
  }

  @Test
  void approveRequiresManager() throws Exception {
    asUser("staffing-user");

    mockMvc
        .perform(post("/api/rfps/" + UUID.randomUUID() + "/approve"))
        .andExpect(status().isForbidden());
  }

  @Test
  void approveReturnsUpdatedRfp() throws Exception {
    RfpEntity approved = draftEntity();
    approved.setPhase(com.crosshubber.talenthub.domain.RfpPhase.APPROVED);
    when(workflowService.approve(any(), anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new RfpWorkflowService.WorkflowResult(approved, List.of()));

    mockMvc
        .perform(post("/api/rfps/" + UUID.randomUUID() + "/approve"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phase").value("approved"));
  }

  @Test
  void draftsAreListedWithPhaseShape() throws Exception {
    when(rfpRepository.findByPhaseOrderByCreatedAtDesc(
            com.crosshubber.talenthub.domain.RfpPhase.DRAFT))
        .thenReturn(List.of(draftEntity()));

    mockMvc
        .perform(get("/api/rfps/drafts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rfps[0].title").value("Java squad"))
        .andExpect(jsonPath("$.rfps[0].phase").value("draft"));
  }

  @Test
  void draftsDenyRecruiter() throws Exception {
    asUser("staffing-recruiter");
    when(rfpRepository.findByPhaseOrderByCreatedAtDesc(
            com.crosshubber.talenthub.domain.RfpPhase.DRAFT))
        .thenReturn(List.of());
    mockMvc
        .perform(get("/api/rfps/drafts"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("staffing-manager or staffing-admin role required"));
  }
}
