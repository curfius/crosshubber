package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

/** Draft deletion: phase guard, child cleanup, not-found mapping. */
class RfpDeleteServiceTest {

  private RfpRepository rfpRepository;
  private PositionRepository positionRepository;
  private CandidateProcessRepository processRepository;
  private SubmissionRepository submissionRepository;
  private RfpPhaseHistoryRepository historyRepository;
  private MatchRunRepository matchRunRepository;
  private RfpDeleteService service;
  private RfpEntity rfp;

  @BeforeEach
  void setUp() {
    rfpRepository = mock(RfpRepository.class);
    positionRepository = mock(PositionRepository.class);
    processRepository = mock(CandidateProcessRepository.class);
    submissionRepository = mock(SubmissionRepository.class);
    historyRepository = mock(RfpPhaseHistoryRepository.class);
    matchRunRepository = mock(MatchRunRepository.class);
    service =
        new RfpDeleteService(
            new TransactionTemplate(mock(PlatformTransactionManager.class)),
            rfpRepository,
            positionRepository,
            processRepository,
            submissionRepository,
            historyRepository,
            matchRunRepository);
    rfp = new RfpEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(rfp, "id", UUID.randomUUID());
    rfp.setClientId(UUID.randomUUID());
    rfp.setTitle("Java squad");
    rfp.setPhase(RfpPhase.DRAFT);
    when(rfpRepository.findById(any())).thenAnswer(inv -> Optional.of(rfp));
    when(processRepository.findByRfpIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    when(positionRepository.findByRfpIdOrderByOrderIndexAsc(any())).thenReturn(List.of());
    when(submissionRepository.findByRfpIdOrderBySentAtDesc(any())).thenReturn(List.of());
    when(historyRepository.findByRfpIdOrderByAtAsc(any())).thenReturn(List.of());
    when(matchRunRepository.findByRfpIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
  }

  @Test
  void deletesDraftWithAllChildRows() {
    CandidateProcessEntity process = new CandidateProcessEntity();
    PositionEntity position = new PositionEntity();
    SubmissionEntity submission = new SubmissionEntity();
    RfpPhaseHistoryEntity history = new RfpPhaseHistoryEntity();
    MatchRunEntity run = new MatchRunEntity();
    when(processRepository.findByRfpIdOrderByCreatedAtAsc(any())).thenReturn(List.of(process));
    when(positionRepository.findByRfpIdOrderByOrderIndexAsc(any())).thenReturn(List.of(position));
    when(submissionRepository.findByRfpIdOrderBySentAtDesc(any())).thenReturn(List.of(submission));
    when(historyRepository.findByRfpIdOrderByAtAsc(any())).thenReturn(List.of(history));
    when(matchRunRepository.findByRfpIdOrderByCreatedAtDesc(any())).thenReturn(List.of(run));

    UUID deleted = service.deleteDraft(rfp.getId());

    assertThat(deleted).isEqualTo(rfp.getId());
    verify(processRepository).delete(process);
    verify(positionRepository).delete(position);
    verify(submissionRepository).delete(submission);
    verify(historyRepository).delete(history);
    verify(matchRunRepository).delete(run);
    verify(rfpRepository).delete(rfp);
  }

  @Test
  void rejectsNonDraftWithoutDeleting() {
    rfp.setPhase(RfpPhase.APPROVED);

    assertThatThrownBy(() -> service.deleteDraft(rfp.getId()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("only draft rfps can be deleted");

    verify(rfpRepository, never()).delete(any());
    verify(positionRepository, never()).delete(any());
  }

  @Test
  void mapsUnknownIdToNotFound() {
    when(rfpRepository.findById(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.deleteDraft(UUID.randomUUID()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("rfp not found");
  }
}
