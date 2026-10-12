package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Manual workflow actions + derived recompute: phase guards, close-cancels, history rows. */
class RfpWorkflowServiceTest {

  private RfpRepository rfpRepository;
  private CandidateProcessRepository processRepository;
  private RfpPhaseHistoryRepository historyRepository;
  private RfpWorkflowService service;
  private RfpEntity rfp;

  @BeforeEach
  void setUp() {
    rfpRepository = mock(RfpRepository.class);
    processRepository = mock(CandidateProcessRepository.class);
    historyRepository = mock(RfpPhaseHistoryRepository.class);
    service =
        new RfpWorkflowService(
            new TransactionTemplate(mock(PlatformTransactionManager.class)),
            rfpRepository,
            processRepository,
            historyRepository,
            new PhaseDerivationService(),
            org.mockito.Mockito.mock(com.crosshubber.talenthub.events.PortalEventPublisher.class));
    rfp = new RfpEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(rfp, "id", UUID.randomUUID());
    rfp.setClientId(UUID.randomUUID());
    rfp.setTitle("Java squad");
    when(rfpRepository.save(any(RfpEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    when(processRepository.save(any(CandidateProcessEntity.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(historyRepository.save(any(RfpPhaseHistoryEntity.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(rfpRepository.findById(any())).thenAnswer(inv -> Optional.of(rfp));
    when(processRepository.findByRfpIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
  }

  private CandidateProcessEntity process(ProcessStage stage) {
    CandidateProcessEntity p = new CandidateProcessEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
    p.setRfpId(rfp.getId());
    p.setPositionId(UUID.randomUUID());
    p.setCandidateId(UUID.randomUUID());
    p.setStage(stage);
    return p;
  }

  @Test
  void approveMovesDraftToApprovedAndWritesManualHistory() {
    rfp.setPhase(RfpPhase.DRAFT);

    RfpWorkflowService.WorkflowResult result = service.approve(rfp.getId(), "Dev Admin");

    assertThat(result.rfp().getPhase()).isEqualTo(RfpPhase.APPROVED);
    verify(historyRepository)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                row ->
                    row.getFromPhase() == RfpPhase.DRAFT
                        && row.getToPhase() == RfpPhase.APPROVED
                        && "manual".equals(row.getTrigger())
                        && "Dev Admin".equals(row.getActor())));
  }

  @Test
  void approveWithEditsAppliesReviewedDraftChanges() {
    rfp.setPhase(RfpPhase.DRAFT);
    rfp.setAnalysis(new java.util.LinkedHashMap<>(Map.of("status", "configured")));

    RfpWorkflowService.WorkflowResult result =
        service.approve(
            rfp.getId(),
            "Dev Admin",
            new RfpWorkflowService.ApproveEdits(
                "Reviewed title",
                List.of(Map.of("title", "Senior Java", "headcount", 2)),
                Map.of("skills", List.of("java"))));

    assertThat(result.rfp().getTitle()).isEqualTo("Reviewed title");
    assertThat(result.rfp().getPhase()).isEqualTo(RfpPhase.APPROVED);
    assertThat((List<?>) result.rfp().getAnalysis().get("positions"))
        .isEqualTo(List.of(Map.of("title", "Senior Java", "headcount", 2)));
    assertThat(result.rfp().getRequirements()).containsEntry("skills", List.of("java"));
  }

  @Test
  void approveFromNonDraftIsConflicting() {
    rfp.setPhase(RfpPhase.SOURCING);

    assertThatThrownBy(() -> service.approve(rfp.getId(), "Dev Admin"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
  }

  @Test
  void closeRequiresConfirmFlag() {
    rfp.setPhase(RfpPhase.SOURCING);

    assertThatThrownBy(() -> service.close(rfp.getId(), false, null, "Dev Admin"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("confirm=true");
  }

  @Test
  void closeCancelsActiveProcessesOnlyAndRecordsThem() {
    rfp.setPhase(RfpPhase.SOURCING);
    CandidateProcessEntity pooled = process(ProcessStage.POOLED);
    CandidateProcessEntity offer = process(ProcessStage.OFFER);
    CandidateProcessEntity onboarded = process(ProcessStage.ONBOARDED);
    CandidateProcessEntity withdrawn = process(ProcessStage.WITHDRAWN);
    when(processRepository.findByRfpIdOrderByCreatedAtAsc(rfp.getId()))
        .thenReturn(List.of(pooled, offer, onboarded, withdrawn));

    RfpWorkflowService.WorkflowResult result =
        service.close(rfp.getId(), true, "client paused hiring", "Dev Admin");

    assertThat(result.rfp().getPhase()).isEqualTo(RfpPhase.CLOSED);
    assertThat(result.cancelledProcessIds())
        .containsExactlyInAnyOrder(pooled.getId(), offer.getId());
    assertThat(pooled.getStage()).isEqualTo(ProcessStage.CANCELLED);
    assertThat(offer.getStage()).isEqualTo(ProcessStage.CANCELLED);
    assertThat(onboarded.getStage()).isEqualTo(ProcessStage.ONBOARDED);
    assertThat(withdrawn.getStage()).isEqualTo(ProcessStage.WITHDRAWN);
    assertThat(pooled.getStageHistory()).hasSize(1);
    assertThat(pooled.getStageHistory().get(0).get("note")).isEqualTo("cancelled by rfp closed");
    verify(historyRepository)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                row ->
                    row.getToPhase() == RfpPhase.CLOSED
                        && "client paused hiring".equals(row.getNote())));
  }

  @Test
  void rejectRequiresReasonAndBlocksTerminalPhases() {
    rfp.setPhase(RfpPhase.SOURCING);
    assertThatThrownBy(() -> service.reject(rfp.getId(), "  ", "Dev Admin"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason");

    rfp.setPhase(RfpPhase.REJECTED);
    assertThatThrownBy(() -> service.reject(rfp.getId(), "again", "Dev Admin"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
  }

  @Test
  void archiveOnlyFromClosedOrRejected() {
    rfp.setPhase(RfpPhase.SOURCING);
    assertThatThrownBy(() -> service.archive(rfp.getId(), "Dev Admin"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");

    rfp.setPhase(RfpPhase.CLOSED);
    RfpWorkflowService.WorkflowResult result = service.archive(rfp.getId(), "Dev Admin");
    assertThat(result.rfp().getPhase()).isEqualTo(RfpPhase.ARCHIVED);
  }

  @Test
  void rederiveAppliesDerivedPhaseAndWritesDerivedHistory() {
    rfp.setPhase(RfpPhase.SOURCING);
    when(processRepository.findByRfpIdOrderByCreatedAtAsc(rfp.getId()))
        .thenReturn(Stream.of(ProcessStage.OFFER).map(this::process).toList());

    RfpWorkflowService.WorkflowResult result = service.rederive(rfp.getId(), "Dev Admin");

    assertThat(result.rfp().getPhase()).isEqualTo(RfpPhase.OFFER_EXTENDED);
    verify(historyRepository)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                row ->
                    row.getFromPhase() == RfpPhase.SOURCING
                        && row.getToPhase() == RfpPhase.OFFER_EXTENDED
                        && "derived".equals(row.getTrigger())));
  }

  @Test
  void rederiveWithoutChangeWritesNoHistory() {
    rfp.setPhase(RfpPhase.SOURCING);
    when(processRepository.findByRfpIdOrderByCreatedAtAsc(rfp.getId()))
        .thenReturn(Stream.of(ProcessStage.WITHDRAWN).map(this::process).toList());

    RfpWorkflowService.WorkflowResult result = service.rederive(rfp.getId(), "Dev Admin");

    // Sourcing is sticky (any process exists) → no change → no history row.
    assertThat(result.rfp().getPhase()).isEqualTo(RfpPhase.SOURCING);
    verify(historyRepository, times(0)).save(any(RfpPhaseHistoryEntity.class));
  }

  @Test
  void rederiveFromApprovedWithFirstProcessEntersSourcing() {
    rfp.setPhase(RfpPhase.APPROVED);
    when(processRepository.findByRfpIdOrderByCreatedAtAsc(rfp.getId()))
        .thenReturn(Stream.of(ProcessStage.POOLED).map(this::process).toList());

    RfpWorkflowService.WorkflowResult result = service.rederive(rfp.getId(), "Dev Admin");

    assertThat(result.rfp().getPhase()).isEqualTo(RfpPhase.SOURCING);
    verify(historyRepository, times(1)).save(any(RfpPhaseHistoryEntity.class));
  }

  @Test
  void unknownRfpIsNotFound() {
    when(rfpRepository.findById(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.approve(UUID.randomUUID(), "Dev Admin"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
  }
}
