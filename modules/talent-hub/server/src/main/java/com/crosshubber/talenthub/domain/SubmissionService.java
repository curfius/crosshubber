package com.crosshubber.talenthub.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.talenthub.events.PortalEventPublisher;
import com.crosshubber.talenthub.security.ModuleRoles;

/**
 * Submissions to the client (P2.4): a batch send moves ready (assessed) processes to submitted; the
 * client response is recorded on the submission. Events fire outside the TX scopes.
 */
@Service
public class SubmissionService {

  public record CreateSubmissionRequest(List<UUID> processIds, String note) {}

  public record SubmissionResponseRequest(String outcome, String note) {}

  private final TransactionTemplate tx;
  private final SubmissionRepository submissionRepository;
  private final CandidateProcessRepository processRepository;
  private final RfpWorkflowService workflow;
  private final PortalEventPublisher publisher;

  public SubmissionService(
      TransactionTemplate tx,
      SubmissionRepository submissionRepository,
      CandidateProcessRepository processRepository,
      RfpWorkflowService workflow,
      PortalEventPublisher publisher) {
    this.tx = tx;
    this.submissionRepository = submissionRepository;
    this.processRepository = processRepository;
    this.workflow = workflow;
    this.publisher = publisher;
  }

  /** Validates the batch, creates the submission row and sends the assessed processes. */
  public RfpDtos.SubmissionDto create(UUID rfpId, CreateSubmissionRequest request, String actor) {
    if (request == null
        || request.processIds() == null
        || request.processIds().isEmpty()
        || new java.util.HashSet<>(request.processIds()).size() != request.processIds().size()) {
      throw new IllegalArgumentException("processIds must be a non-empty unique list");
    }
    if (request.note() != null && request.note().length() > 2000) {
      throw new IllegalArgumentException("note must be at most 2000 chars");
    }
    TxOutcome outcome =
        tx.execute(
            status -> {
              List<CandidateProcessEntity> processes = new ArrayList<>();
              List<String> problems = new ArrayList<>();
              for (UUID processId : request.processIds()) {
                CandidateProcessEntity process = loadProcessForRfp(rfpId, processId);
                if (process.getStage() != ProcessStage.ASSESSED) {
                  problems.add(
                      processId + " is " + process.getStage().value() + " (assessed required)");
                } else {
                  processes.add(process);
                }
              }
              if (!problems.isEmpty()) {
                throw new IllegalArgumentException(
                    "not ready for submission: " + String.join("; ", problems));
              }
              SubmissionEntity submission = new SubmissionEntity();
              submission.setRfpId(rfpId);
              submission.setProcessIds(new ArrayList<>(request.processIds()));
              submission.setNote(request.note());
              submission.setSentBy(actor == null || actor.isBlank() ? "unknown" : actor);
              submissionRepository.save(submission);
              for (CandidateProcessEntity process : processes) {
                process.setStage(ProcessStage.SUBMITTED);
                process
                    .getStageHistory()
                    .add(
                        stageEntry(
                            ProcessStage.SUBMITTED,
                            actor,
                            "submitted to client (submission " + submission.getId() + ")"));
                processRepository.save(process);
              }
              return new TxOutcome(submission, processes);
            });
    workflow.rederive(rfpId, actor == null ? "unknown" : actor);
    for (CandidateProcessEntity process : outcome.processes()) {
      publishStage(process, ProcessStage.SUBMITTED);
    }
    return RfpDtos.toDto(outcome.submission());
  }

  /** Records the client's response on the submission. */
  public RfpDtos.SubmissionDto respond(
      UUID submissionId, SubmissionResponseRequest request, String actor) {
    if (request == null || request.outcome() == null || request.outcome().isBlank()) {
      throw new IllegalArgumentException("outcome is required");
    }
    SubmissionEntity saved =
        tx.execute(
            status -> {
              SubmissionEntity submission =
                  submissionRepository
                      .findById(submissionId)
                      .orElseThrow(
                          () ->
                              new ResponseStatusException(
                                  HttpStatus.NOT_FOUND, "submission not found"));
              if (submission.getRespondedAt() != null) {
                throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "submission already has a response");
              }
              Map<String, Object> response = new LinkedHashMap<>();
              response.put("outcome", request.outcome());
              response.put("note", request.note());
              response.put("at", Instant.now().toString());
              submission.setResponse(response);
              submission.setRespondedAt(Instant.now());
              submissionRepository.save(submission);
              return submission;
            });
    return RfpDtos.toDto(saved);
  }

  /** Lists the submissions of an RFP, newest first (P2.1 read surface for detail already does). */
  public Optional<RfpDtos.SubmissionDto> get(UUID submissionId) {
    return submissionRepository.findById(submissionId).map(RfpDtos::toDto);
  }

  private CandidateProcessEntity loadProcessForRfp(UUID rfpId, UUID processId) {
    CandidateProcessEntity process =
        processRepository
            .findById(processId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "process not found: " + processId));
    if (!rfpId.equals(process.getRfpId())) {
      throw new IllegalArgumentException(processId + " does not belong to rfp " + rfpId);
    }
    return process;
  }

  private Map<String, Object> stageEntry(ProcessStage stage, String by, String note) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("stage", stage.value());
    entry.put("at", Instant.now().toString());
    entry.put("by", by);
    entry.put("note", note);
    return entry;
  }

  private void publishStage(CandidateProcessEntity process, ProcessStage to) {
    String body =
        "rfp " + process.getRfpId() + " · position " + process.getPositionId() + " → " + to.value();
    List<String> audience = List.of(ModuleRoles.MANAGER, ModuleRoles.RECRUITER, ModuleRoles.ADMIN);
    publisher.publishMessage(
        "process.stage-changed", "stage changed:submitted", body, "info", audience, null);
    publisher.publishMessage(
        "process.submitted", "Milestone: submitted", body, "success", audience, null);
  }

  private record TxOutcome(SubmissionEntity submission, List<CandidateProcessEntity> processes) {}
}
