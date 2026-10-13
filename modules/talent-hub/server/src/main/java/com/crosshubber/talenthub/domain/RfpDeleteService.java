package com.crosshubber.talenthub.domain;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Draft deletion: removes a {@code DRAFT} RFP together with its child rows (positions, candidate
 * processes, submissions, match runs, phase history). Non-draft phases are rejected — approved and
 * later RFPs leave through the close/reject/archive workflow instead. Uploaded document files are
 * left on the volume (refs are content-addressed per upload and may be shared).
 */
@Service
public class RfpDeleteService {

  private final TransactionTemplate tx;
  private final RfpRepository rfpRepository;
  private final PositionRepository positionRepository;
  private final CandidateProcessRepository processRepository;
  private final SubmissionRepository submissionRepository;
  private final RfpPhaseHistoryRepository historyRepository;
  private final MatchRunRepository matchRunRepository;

  public RfpDeleteService(
      TransactionTemplate tx,
      RfpRepository rfpRepository,
      PositionRepository positionRepository,
      CandidateProcessRepository processRepository,
      SubmissionRepository submissionRepository,
      RfpPhaseHistoryRepository historyRepository,
      MatchRunRepository matchRunRepository) {
    this.tx = tx;
    this.rfpRepository = rfpRepository;
    this.positionRepository = positionRepository;
    this.processRepository = processRepository;
    this.submissionRepository = submissionRepository;
    this.historyRepository = historyRepository;
    this.matchRunRepository = matchRunRepository;
  }

  /**
   * Deletes a draft RFP and its child rows in one transaction.
   *
   * @param rfpId the RFP to delete
   * @return the deleted RFP id
   * @throws ResponseStatusException 404 when unknown, 409 when not a draft
   */
  public UUID deleteDraft(UUID rfpId) {
    if (rfpId == null) {
      throw new IllegalArgumentException("rfpId is required");
    }
    return tx.execute(
        status -> {
          RfpEntity rfp =
              rfpRepository
                  .findById(rfpId)
                  .orElseThrow(
                      () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "rfp not found"));
          if (rfp.getPhase() != RfpPhase.DRAFT) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "only draft rfps can be deleted (current phase: " + rfp.getPhase().value() + ")");
          }
          for (CandidateProcessEntity process :
              processRepository.findByRfpIdOrderByCreatedAtAsc(rfpId)) {
            processRepository.delete(process);
          }
          for (PositionEntity position :
              positionRepository.findByRfpIdOrderByOrderIndexAsc(rfpId)) {
            positionRepository.delete(position);
          }
          for (SubmissionEntity submission :
              submissionRepository.findByRfpIdOrderBySentAtDesc(rfpId)) {
            submissionRepository.delete(submission);
          }
          for (RfpPhaseHistoryEntity history : historyRepository.findByRfpIdOrderByAtAsc(rfpId)) {
            historyRepository.delete(history);
          }
          for (MatchRunEntity run : matchRunRepository.findByRfpIdOrderByCreatedAtDesc(rfpId)) {
            matchRunRepository.delete(run);
          }
          rfpRepository.delete(rfp);
          return rfpId;
        });
  }
}
