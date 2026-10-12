package com.crosshubber.talenthub.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.talenthub.events.PortalEventPublisher;
import com.crosshubber.talenthub.security.ModuleRoles;

/**
 * Candidate-process lifecycle (P2.3): open under a position (one active per candidate × position)
 * and validated stage transitions. Every mutation appends stage history, re-derives the RFP phase
 * (D3/D5) and publishes events OUTSIDE the TX scopes. Milestones: process.submitted,
 * process.offer-extended, process.accepted, process.onboarded.
 */
@Service
public class CandidateProcessService {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(CandidateProcessService.class);

  /** Stages that count as live for the one-active-process guard (domain plan §3.2). */
  static final Set<ProcessStage> ACTIVE_STAGES =
      Set.of(
          ProcessStage.POOLED,
          ProcessStage.ASSESSED,
          ProcessStage.SUBMITTED,
          ProcessStage.INTERVIEWING,
          ProcessStage.OFFER,
          ProcessStage.ACCEPTED,
          ProcessStage.ONBOARDING);

  public record OpenProcessRequest(UUID candidateId, String note) {}

  public record StageChangeRequest(String toStage, String note) {}

  public record AssessRequest(String engine) {}

  private final TransactionTemplate tx;
  private final CandidateProcessRepository processRepository;
  private final PositionRepository positionRepository;
  private final CandidateProfileRepository candidateRepository;
  private final RfpRepository rfpRepository;
  private final RfpWorkflowService workflow;
  private final FitEngineSelector engineSelector;
  private final com.crosshubber.talenthub.connectors.ConnectorRegistry connectorRegistry;
  private final PortalEventPublisher publisher;

  public CandidateProcessService(
      TransactionTemplate tx,
      CandidateProcessRepository processRepository,
      PositionRepository positionRepository,
      CandidateProfileRepository candidateRepository,
      RfpRepository rfpRepository,
      RfpWorkflowService workflow,
      FitEngineSelector engineSelector,
      com.crosshubber.talenthub.connectors.ConnectorRegistry connectorRegistry,
      PortalEventPublisher publisher) {
    this.tx = tx;
    this.processRepository = processRepository;
    this.positionRepository = positionRepository;
    this.candidateRepository = candidateRepository;
    this.rfpRepository = rfpRepository;
    this.workflow = workflow;
    this.engineSelector = engineSelector;
    this.connectorRegistry = connectorRegistry;
    this.publisher = publisher;
  }

  /** Opens a process (stage pooled) for one candidate × position. */
  public RfpDtos.ProcessDto open(UUID positionId, OpenProcessRequest request, String actor) {
    if (request == null || request.candidateId() == null) {
      throw new IllegalArgumentException("candidateId is required");
    }
    TxOutcome opened =
        tx.execute(
            status -> {
              PositionEntity position =
                  positionRepository
                      .findById(positionId)
                      .orElseThrow(
                          () ->
                              new ResponseStatusException(
                                  HttpStatus.NOT_FOUND, "position not found"));
              candidateRepository
                  .findById(request.candidateId())
                  .orElseThrow(
                      () ->
                          new ResponseStatusException(HttpStatus.NOT_FOUND, "candidate not found"));
              if (processRepository.existsByPositionIdAndCandidateIdAndStageIn(
                  positionId, request.candidateId(), ACTIVE_STAGES)) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "candidate already has an active process for this position");
              }
              CandidateProcessEntity process = new CandidateProcessEntity();
              process.setRfpId(position.getRfpId());
              process.setPositionId(position.getId());
              process.setCandidateId(request.candidateId());
              process.setStage(ProcessStage.POOLED);
              process.setNotes(request.note());
              process.setCreatedBy(actor == null || actor.isBlank() ? "unknown" : actor);
              process
                  .getStageHistory()
                  .add(
                      historyEntry(
                          ProcessStage.POOLED,
                          actor == null || actor.isBlank() ? "unknown" : actor,
                          request.note() == null ? "process opened" : request.note()));
              processRepository.save(process);
              return new TxOutcome(position.getRfpId(), process, ProcessStage.POOLED);
            });
    workflow.rederive(opened.rfpId(), actor);
    publishStageChanged(opened.process(), ProcessStage.POOLED, "process opened:pooled");
    return RfpDtos.toDto(opened.process());
  }

  /**
   * Runs the configured (or overridden) fit engine on a POOLED process and moves it to assessed
   * (P3.2). Engine call runs OUTSIDE the TX; only a successful assessment transitions the stage.
   * Failures surface as 502 and the process stays pooled.
   */
  public RfpDtos.ProcessDto assess(UUID processId, AssessRequest request, String actor) {
    AssessContext prepared =
        tx.execute(
            status -> {
              CandidateProcessEntity process =
                  processRepository
                      .findById(processId)
                      .orElseThrow(
                          () ->
                              new ResponseStatusException(
                                  HttpStatus.NOT_FOUND, "process not found"));
              if (process.getStage() != ProcessStage.POOLED) {
                throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "only pooled processes can be assessed (current: "
                        + process.getStage().value()
                        + ")");
              }
              PositionEntity position =
                  positionRepository
                      .findById(process.getPositionId())
                      .orElseThrow(
                          () ->
                              new ResponseStatusException(
                                  HttpStatus.NOT_FOUND, "position not found"));
              RfpEntity rfp =
                  rfpRepository
                      .findById(process.getRfpId())
                      .orElseThrow(
                          () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "rfp not found"));
              Map<String, Object> merged = new LinkedHashMap<>(rfp.getRequirements());
              merged.putAll(position.getRequirements());
              CandidateProfileEntity candidate =
                  candidateRepository
                      .findById(process.getCandidateId())
                      .orElseThrow(
                          () ->
                              new ResponseStatusException(
                                  HttpStatus.NOT_FOUND, "candidate not found"));
              return new AssessContext(process, candidate, PositionRequirements.from(merged));
            });
    FitEngine engine = engineSelector.select(requestEngineName(request));
    FitVerdict verdict;
    try {
      verdict = engine.assess(prepared.candidate(), prepared.requirements(), actor);
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "assessment failed: " + (e.getMessage() == null ? "engine error" : e.getMessage()));
    }
    TxOutcome stored =
        tx.execute(
            status -> {
              CandidateProcessEntity process = prepared.process();
              process.setFit(
                  new LinkedHashMap<>(
                      Map.of(
                          "dimensions",
                          verdict.dimensions(),
                          "overall",
                          verdict.overall(),
                          "engine",
                          verdict.engine(),
                          "at",
                          verdict.at())));
              process.setStage(ProcessStage.ASSESSED);
              process
                  .getStageHistory()
                  .add(
                      historyEntry(
                          ProcessStage.ASSESSED, actor, "assessed via " + verdict.engine()));
              processRepository.save(process);
              return new TxOutcome(process.getRfpId(), process, ProcessStage.ASSESSED);
            });
    workflow.rederive(stored.rfpId(), actor);
    publishStageChanged(
        stored.process(), ProcessStage.ASSESSED, "assessed (fit " + verdict.overall() + ")");
    return RfpDtos.toDto(stored.process());
  }

  private static String requestEngineName(AssessRequest request) {
    return request == null ? null : request.engine();
  }

  private record AssessContext(
      CandidateProcessEntity process,
      CandidateProfileEntity candidate,
      PositionRequirements requirements) {}

  /** Validated stage transition with history note. */
  public RfpDtos.ProcessDto transition(UUID processId, StageChangeRequest request, String actor) {
    if (request == null || request.toStage() == null || request.toStage().isBlank()) {
      throw new IllegalArgumentException("toStage is required");
    }
    ProcessStage toStage;
    try {
      toStage = ProcessStage.fromString(request.toStage());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("unknown stage: " + request.toStage());
    }
    TxOutcome outcome =
        tx.execute(
            status -> {
              CandidateProcessEntity process =
                  processRepository
                      .findById(processId)
                      .orElseThrow(
                          () ->
                              new ResponseStatusException(
                                  HttpStatus.NOT_FOUND, "process not found"));
              if (!process.getStage().canTransitionTo(toStage)) {
                throw new IllegalArgumentException(
                    "cannot move from "
                        + process.getStage().value()
                        + " to "
                        + toStage.value()
                        + " (transition not allowed)");
              }
              ProcessStage from = process.getStage();
              process.setStage(toStage);
              process
                  .getStageHistory()
                  .add(
                      historyEntry(
                          toStage, actor, request.note() == null ? from.value() : request.note()));
              processRepository.save(process);
              return new TxOutcome(process.getRfpId(), process, toStage);
            });
    workflow.rederive(outcome.rfpId(), actor);
    publishStageChanged(
        outcome.process(), outcome.stage(), "stage changed:" + outcome.stage().value());
    if (outcome.stage() == ProcessStage.ONBOARDED) {
      notifyHris(outcome.process(), actor);
    }
    return RfpDtos.toDto(outcome.process());
  }

  /** P4.1: HRIS handshake on onboarded — outside the TX, fail-soft, emulators log to the outbox. */
  private void notifyHris(CandidateProcessEntity process, String actor) {
    try {
      com.crosshubber.talenthub.connectors.ModuleConnector moduleConnector =
          connectorRegistry.resolve(com.crosshubber.talenthub.connectors.ConnectorCategory.HRIS);
      if (moduleConnector instanceof com.crosshubber.talenthub.connectors.HrisConnector hris) {
        String employeeId =
            hris.onboard(process.getId().toString(), "candidate " + process.getCandidateId());
        log.info(
            "[onboarding] hris {} registered employee {} (actor {})",
            moduleConnector.kind(),
            employeeId,
            actor);
      }
    } catch (Exception e) {
      log.warn("[onboarding] hris call failed soft: {}", e.getMessage());
    }
  }

  private record TxOutcome(UUID rfpId, CandidateProcessEntity process, ProcessStage stage) {}

  private Map<String, Object> historyEntry(ProcessStage stage, String by, String note) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("stage", stage.value());
    entry.put("at", Instant.now().toString());
    entry.put("by", by);
    entry.put("note", note);
    return entry;
  }

  private static String milestoneSubject(ProcessStage stage) {
    return switch (stage) {
      case SUBMITTED -> "process.submitted";
      case OFFER -> "process.offer-extended";
      case ACCEPTED -> "process.accepted";
      case ONBOARDED -> "process.onboarded";
      default -> null;
    };
  }

  private void publishStageChanged(CandidateProcessEntity process, ProcessStage to, String label) {
    List<String> audience = List.of(ModuleRoles.MANAGER, ModuleRoles.RECRUITER, ModuleRoles.ADMIN);
    String body =
        "rfp " + process.getRfpId() + " · position " + process.getPositionId() + " → " + to.value();
    publisher.publishMessage("process.stage-changed", label, body, "info", audience, null);
    String milestone = milestoneSubject(to);
    if (milestone != null) {
      publisher.publishMessage(
          milestone, "Milestone: " + to.value(), body, "success", audience, null);
    }
  }
}
