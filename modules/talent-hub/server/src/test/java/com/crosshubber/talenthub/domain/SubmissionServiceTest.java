package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.talenthub.events.PortalEventPublisher;

/** Submission flow (P2.4): assessed-only batch, events per process, response once. */
class SubmissionServiceTest {

  private SubmissionRepository submissionRepository;
  private CandidateProcessRepository processRepository;
  private RfpWorkflowService workflow;
  private PortalEventPublisher publisher;
  private SubmissionService service;

  private UUID rfpId;
  private CandidateProcessEntity assessed;
  private CandidateProcessEntity pooled;

  @BeforeEach
  void setUp() {
    submissionRepository = mock(SubmissionRepository.class);
    processRepository = mock(CandidateProcessRepository.class);
    workflow = mock(RfpWorkflowService.class);
    publisher = mock(PortalEventPublisher.class);
    service =
        new SubmissionService(
            new TransactionTemplate(mock(PlatformTransactionManager.class)),
            submissionRepository,
            processRepository,
            workflow,
            publisher);
    rfpId = UUID.randomUUID();
    assessed = process(ProcessStage.ASSESSED);
    pooled = process(ProcessStage.POOLED);
    when(submissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(processRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  private CandidateProcessEntity process(ProcessStage stage) {
    CandidateProcessEntity p = new CandidateProcessEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
    p.setRfpId(rfpId);
    p.setPositionId(UUID.randomUUID());
    p.setCandidateId(UUID.randomUUID());
    p.setStage(stage);
    when(processRepository.findById(p.getId())).thenReturn(Optional.of(p));
    return p;
  }

  @Test
  void batchSendMovesAssessedProcessesToSubmitted() {
    RfpDtos.SubmissionDto created =
        service.create(
            rfpId,
            new SubmissionService.CreateSubmissionRequest(
                List.of(assessed.getId()), "Java squad for Acme"),
            "Recruiter");

    assertThat(created.processIds()).containsExactly(assessed.getId());
    assertThat(assessed.getStage()).isEqualTo(ProcessStage.SUBMITTED);
    assertThat(assessed.getStageHistory().get(0).get("note"))
        .toString()
        .contains("submitted to client");
    verify(workflow).rederive(rfpId, "Recruiter");
    verify(publisher)
        .publishMessage(
            eq("process.submitted"), anyString(), anyString(), eq("success"), any(), any());
  }

  @Test
  void batchRejectsNonAssessedProcessesWithoutSideEffects() {
    assertThatThrownBy(
            () ->
                service.create(
                    rfpId,
                    new SubmissionService.CreateSubmissionRequest(
                        List.of(assessed.getId(), pooled.getId()), null),
                    "Recruiter"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not ready for submission");

    assertThat(pooled.getStage()).isEqualTo(ProcessStage.POOLED);
    verify(submissionRepository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void batchRejectsForeignAndMissingProcesses() {
    CandidateProcessEntity foreign = process(ProcessStage.ASSESSED);
    org.springframework.test.util.ReflectionTestUtils.setField(foreign, "rfpId", UUID.randomUUID());

    assertThatThrownBy(
            () ->
                service.create(
                    rfpId,
                    new SubmissionService.CreateSubmissionRequest(List.of(foreign.getId()), null),
                    "Recruiter"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not belong");

    assertThatThrownBy(
            () ->
                service.create(
                    rfpId,
                    new SubmissionService.CreateSubmissionRequest(List.of(UUID.randomUUID()), null),
                    "Recruiter"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("process not found");
  }

  @Test
  void emptyAndDuplicateBatchesAreRejected() {
    assertThatThrownBy(
            () ->
                service.create(
                    rfpId,
                    new SubmissionService.CreateSubmissionRequest(List.of(), null),
                    "Recruiter"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                service.create(
                    rfpId,
                    new SubmissionService.CreateSubmissionRequest(
                        List.of(assessed.getId(), assessed.getId()), null),
                    "Recruiter"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void responseRecordsOutcomeOnce() {
    SubmissionEntity submission = new SubmissionEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(submission, "id", UUID.randomUUID());
    submission.setRfpId(rfpId);
    when(submissionRepository.findById(submission.getId())).thenReturn(Optional.of(submission));

    RfpDtos.SubmissionDto responded =
        service.respond(
            submission.getId(),
            new SubmissionService.SubmissionResponseRequest("accepted", "starts next month"),
            "Recruiter");

    assertThat(responded.response()).containsEntry("outcome", "accepted");
    assertThat(responded.respondedAt()).isNotNull();

    when(submissionRepository.findById(submission.getId()))
        .thenAnswer(inv -> Optional.of(submission));

    assertThatThrownBy(
            () ->
                service.respond(
                    submission.getId(),
                    new SubmissionService.SubmissionResponseRequest("rejected", null),
                    "Recruiter"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
  }
}
