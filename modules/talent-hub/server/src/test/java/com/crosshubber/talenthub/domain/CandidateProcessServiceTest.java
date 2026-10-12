package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.talenthub.events.PortalEventPublisher;
import com.crosshubber.talenthub.security.ModuleRoles;

/** Process lifecycle (P2.3): open guard, transition validation, derive + events. */
class CandidateProcessServiceTest {

  private CandidateProcessRepository processRepository;
  private PositionRepository positionRepository;
  private CandidateProfileRepository candidateRepository;
  private RfpRepository rfpRepository;
  private RfpWorkflowService workflow;
  private PortalEventPublisher publisher;
  private CandidateProcessService service;

  private PositionEntity position;
  private CandidateProcessEntity process;

  @BeforeEach
  void setUp() {
    processRepository = mock(CandidateProcessRepository.class);
    positionRepository = mock(PositionRepository.class);
    candidateRepository = mock(CandidateProfileRepository.class);
    rfpRepository = mock(RfpRepository.class);
    workflow = mock(RfpWorkflowService.class);
    publisher = mock(PortalEventPublisher.class);
    // Default selector backed by the default settings (no group → "rules").
    com.crosshubber.talenthub.settings.ModuleSettingRepository settingRepo =
        mock(com.crosshubber.talenthub.settings.ModuleSettingRepository.class);
    when(settingRepo.findByKey(any(String.class))).thenReturn(Optional.empty());
    var props = new com.crosshubber.talenthub.config.TalentHubProperties();
    props.setCryptoKey("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    var settings =
        new com.crosshubber.talenthub.settings.ModuleSettingsService(
            settingRepo,
            new com.crosshubber.talenthub.settings.CryptoService(props),
            props,
            new tools.jackson.databind.json.JsonMapper());
    FitEngineSelector selector =
        new FitEngineSelector(
            List.of(
                new FitEngine() {
                  @Override
                  public String key() {
                    return "rules";
                  }

                  @Override
                  public FitVerdict assess(
                      CandidateProfileEntity candidate, PositionRequirements r, String actor) {
                    return FitVerdict.of(
                        "rules",
                        FitVerdict.dimension(FitVerdict.STRONG, "stub job", "8"),
                        FitVerdict.dimension(FitVerdict.STRONG, "stub env", null),
                        FitVerdict.dimension(FitVerdict.MAYBE, "stub value", null));
                  }
                }),
            settings);
    service =
        new CandidateProcessService(
            new TransactionTemplate(mock(PlatformTransactionManager.class)),
            processRepository,
            positionRepository,
            candidateRepository,
            rfpRepository,
            workflow,
            selector,
            mock(com.crosshubber.talenthub.connectors.ConnectorRegistry.class),
            publisher);
    position = new PositionEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(position, "id", UUID.randomUUID());
    position.setRfpId(UUID.randomUUID());
    position.setTitle("Senior Java");
    position.setStatus("open");
    process = new CandidateProcessEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(process, "id", UUID.randomUUID());
    process.setRfpId(position.getRfpId());
    process.setPositionId(position.getId());
    process.setCandidateId(UUID.randomUUID());
    process.setStage(ProcessStage.POOLED);
    when(positionRepository.findById(position.getId())).thenReturn(Optional.of(position));
    when(candidateRepository.findById(any())).thenReturn(Optional.of(new CandidateProfileEntity()));
    when(processRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(processRepository.findById(process.getId())).thenReturn(Optional.of(process));
    when(processRepository.existsByPositionIdAndCandidateIdAndStageIn(any(), any(), any()))
        .thenReturn(false);
    when(workflow.rederive(any(), anyString()))
        .thenReturn(new RfpWorkflowService.WorkflowResult(new RfpEntity(), List.of()));
  }

  @Test
  void openCreatesPooledProcessWithHistoryAndRederives() {
    CandidateProcessEntity fresh = new CandidateProcessEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(fresh, "id", UUID.randomUUID());
    fresh.setPositionId(position.getId());

    when(processRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    RfpDtos.ProcessDto dto =
        service.open(
            position.getId(),
            new CandidateProcessService.OpenProcessRequest(process.getCandidateId(), "hello"),
            "Recruiter");

    assertThat(dto.stage()).isEqualTo("pooled");
    assertThat(dto.stageHistory()).hasSize(1);
    assertThat(dto.stageHistory().get(0).stage()).isEqualTo("pooled");
    verify(workflow).rederive(position.getRfpId(), "Recruiter");
    verify(publisher)
        .publishMessage(
            eq("process.stage-changed"),
            anyString(),
            anyString(),
            eq("info"),
            argThat(
                r ->
                    r.equals(
                        List.of(ModuleRoles.MANAGER, ModuleRoles.RECRUITER, ModuleRoles.ADMIN))),
            any());
  }

  @Test
  void openBlocksDuplicateActiveProcess() {
    when(processRepository.existsByPositionIdAndCandidateIdAndStageIn(
            position.getId(), process.getCandidateId(), CandidateProcessService.ACTIVE_STAGES))
        .thenReturn(true);

    assertThatThrownBy(
            () ->
                service.open(
                    position.getId(),
                    new CandidateProcessService.OpenProcessRequest(process.getCandidateId(), null),
                    "Recruiter"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");

    verify(workflow, never()).rederive(any(), anyString());
  }

  @Test
  void openRequiresKnownPositionAndCandidate() {
    when(positionRepository.findById(position.getId())).thenReturn(Optional.empty());
    UUID candidateId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                service.open(
                    position.getId(),
                    new CandidateProcessService.OpenProcessRequest(candidateId, null),
                    "u"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("position");

    when(positionRepository.findById(position.getId())).thenReturn(Optional.of(position));
    when(candidateRepository.findById(candidateId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.open(
                    position.getId(),
                    new CandidateProcessService.OpenProcessRequest(candidateId, null),
                    "u"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("candidate");
  }

  @Test
  void transitionValidatesAndAppendsHistory() {
    CandidateProcessEntity pooled = new CandidateProcessEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(pooled, "id", UUID.randomUUID());
    pooled.setRfpId(process.getRfpId());
    pooled.setPositionId(position.getId());
    pooled.setCandidateId(UUID.randomUUID());
    pooled.setStage(ProcessStage.POOLED);
    when(processRepository.findById(pooled.getId())).thenReturn(Optional.of(pooled));

    RfpDtos.ProcessDto dto =
        service.transition(
            pooled.getId(),
            new CandidateProcessService.StageChangeRequest("assessed", "screened, looks strong"),
            "Recruiter");

    assertThat(dto.stage()).isEqualTo("assessed");
    assertThat(dto.stageHistory().get(0).stage()).isEqualTo("assessed");
    assertThat(dto.stageHistory().get(0).note()).isEqualTo("screened, looks strong");
  }

  @Test
  void transitionRejectsDisallowedMoves() {
    assertThatThrownBy(
            () ->
                service.transition(
                    process.getId(),
                    new CandidateProcessService.StageChangeRequest("offer", null),
                    "u"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot move from pooled to offer");

    assertThatThrownBy(
            () ->
                service.transition(
                    process.getId(),
                    new CandidateProcessService.StageChangeRequest("onboarded", null),
                    "u"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot move");
  }

  @Test
  void milestoneStagePublishesDedicatedSubject() {
    CandidateProcessEntity assessed = new CandidateProcessEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(assessed, "id", UUID.randomUUID());
    assessed.setRfpId(process.getRfpId());
    assessed.setPositionId(position.getId());
    assessed.setCandidateId(UUID.randomUUID());
    assessed.setStage(ProcessStage.ASSESSED);
    when(processRepository.findById(assessed.getId())).thenReturn(Optional.of(assessed));

    service.transition(
        assessed.getId(),
        new CandidateProcessService.StageChangeRequest("submitted", "batch send"),
        "Recruiter");

    verify(publisher)
        .publishMessage(
            eq("process.stage-changed"), anyString(), anyString(), eq("info"), any(), any());
    verify(publisher)
        .publishMessage(
            eq("process.submitted"), anyString(), anyString(), eq("success"), any(), any());
  }

  @Test
  void offerStageUsesTheOfferExtendedSubject() {
    CandidateProcessEntity interviewing = new CandidateProcessEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(
        interviewing, "id", UUID.randomUUID());
    interviewing.setRfpId(process.getRfpId());
    interviewing.setPositionId(position.getId());
    interviewing.setCandidateId(UUID.randomUUID());
    interviewing.setStage(ProcessStage.INTERVIEWING);
    when(processRepository.findById(interviewing.getId())).thenReturn(Optional.of(interviewing));

    service.transition(
        interviewing.getId(),
        new CandidateProcessService.StageChangeRequest("offer", "offer extended"),
        "Recruiter");

    verify(publisher)
        .publishMessage(
            eq("process.offer-extended"), anyString(), anyString(), eq("success"), any(), any());
  }

  @Test
  void terminalTransitionDoesNotPublishMilestone() {
    CandidateProcessEntity onboarding = new CandidateProcessEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(onboarding, "id", UUID.randomUUID());
    onboarding.setRfpId(process.getRfpId());
    onboarding.setStage(ProcessStage.ONBOARDING);
    when(processRepository.findById(onboarding.getId())).thenReturn(Optional.of(onboarding));

    service.transition(
        onboarding.getId(),
        new CandidateProcessService.StageChangeRequest("onboarded", null),
        "Recruiter");

    assertThat(onboarding.getStage()).isEqualTo(ProcessStage.ONBOARDED);
    verify(publisher)
        .publishMessage(
            eq("process.onboarded"), anyString(), anyString(), eq("success"), any(), any());
  }

  @Test
  void unknownStageTokenFailsCleanly() {
    assertThatThrownBy(
            () ->
                service.transition(
                    process.getId(),
                    new CandidateProcessService.StageChangeRequest("finished", null),
                    "u"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown stage");
  }

  @Test
  void assessMovesPooledProcessToAssessedWithStoredFit() {
    process.setStage(ProcessStage.POOLED);
    RfpEntity rfp = new RfpEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(rfp, "id", process.getRfpId());
    rfp.setPhase(RfpPhase.APPROVED);
    rfp.setRequirements(new java.util.HashMap<>(Map.of("skills", List.of("java"))));
    position.setRequirements(new java.util.HashMap<>(Map.of("seniority", "senior")));
    when(rfpRepository.findById(process.getRfpId())).thenReturn(Optional.of(rfp));

    RfpDtos.ProcessDto dto =
        service.assess(
            process.getId(), new CandidateProcessService.AssessRequest(null), "Recruiter");

    assertThat(dto.stage()).isEqualTo("assessed");
    Map<String, Object> fit = process.getFit();
    assertThat(fit).containsEntry("engine", "rules");
    assertThat(fit).containsEntry("overall", FitVerdict.MAYBE);
    assertThat(String.valueOf(fit.get("dimensions"))).contains("job", "environment", "value");
    assertThat(process.getStageHistory().get(0).get("note")).toString().contains("assessed");
    verify(workflow).rederive(process.getRfpId(), "Recruiter");
  }

  @Test
  void assessRejectsNonPooledProcesses() {
    process.setStage(ProcessStage.SUBMITTED);

    assertThatThrownBy(
            () ->
                service.assess(
                    process.getId(), new CandidateProcessService.AssessRequest(null), "u"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("only pooled");
  }

  @Test
  void assessFailsSoftWhenEngineUnavailable() {
    process.setStage(ProcessStage.POOLED);
    RfpEntity rfp = new RfpEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(rfp, "id", process.getRfpId());
    rfp.setPhase(RfpPhase.APPROVED);
    when(rfpRepository.findById(process.getRfpId())).thenReturn(Optional.of(rfp));

    // Override with an unknown key → selector fails fast → 4xx-ish surfaced, process stays pooled.
    assertThatThrownBy(
            () ->
                service.assess(
                    process.getId(),
                    new CandidateProcessService.AssessRequest("does-not-exist"),
                    "u"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown fit engine");
    assertThat(process.getStage()).isEqualTo(ProcessStage.POOLED);
  }
}
