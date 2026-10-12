package com.crosshubber.talenthub.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.talenthub.events.PortalEventPublisher;
import com.crosshubber.talenthub.security.ModuleRoles;

/**
 * RFP workflow orchestration (P0.4): manual manager actions (approve/close/reject/archive) and the
 * derived-phase recompute. TX discipline (AGENTS invariant): DB-bound work runs in explicit {@link
 * TransactionTemplate} scopes; event publishing (P1) and LLM calls (P1.3) orchestrate strictly
 * OUTSIDE those scopes — blocking I/O never holds a Hikari connection.
 */
@Service
public class RfpWorkflowService {

  public record WorkflowResult(RfpEntity rfp, List<UUID> cancelledProcessIds) {}

  /** Manager's reviewed edits to the AI draft, applied inside the approve transaction. */
  public record ApproveEdits(
      String title, List<Map<String, Object>> positions, Map<String, Object> requirements) {}

  private final TransactionTemplate tx;
  private final RfpRepository rfpRepository;
  private final CandidateProcessRepository processRepository;
  private final RfpPhaseHistoryRepository historyRepository;
  private final PhaseDerivationService derivation;
  private final PortalEventPublisher publisher;

  public RfpWorkflowService(
      TransactionTemplate tx,
      RfpRepository rfpRepository,
      CandidateProcessRepository processRepository,
      RfpPhaseHistoryRepository historyRepository,
      PhaseDerivationService derivation,
      PortalEventPublisher publisher) {
    this.tx = tx;
    this.rfpRepository = rfpRepository;
    this.processRepository = processRepository;
    this.historyRepository = historyRepository;
    this.derivation = derivation;
    this.publisher = publisher;
  }

  // ── Manual manager actions (role enforcement lives in the controller; P0.5) ──

  /** DRAFT → APPROVED; the only transition that moves a draft. */
  public WorkflowResult approve(UUID rfpId, String actor) {
    return approve(rfpId, actor, null);
  }

  /** Approve with the manager's reviewed edits (no silent auto-approve — edits are explicit). */
  public WorkflowResult approve(UUID rfpId, String actor, ApproveEdits edits) {
    WorkflowResult result =
        tx.execute(
            status -> {
              RfpEntity rfp = loadRfp(rfpId);
              requirePhase(
                  rfp,
                  RfpPhase.DRAFT,
                  "only draft rfps can be approved (current phase: "
                      + rfp.getPhase().value()
                      + ")");
              if (edits != null) {
                if (edits.title() != null && !edits.title().isBlank()) {
                  rfp.setTitle(edits.title().trim());
                }
                if (edits.positions() != null) {
                  java.util.Map<String, Object> analysis =
                      rfp.getAnalysis() == null
                          ? new LinkedHashMap<>()
                          : new LinkedHashMap<>(rfp.getAnalysis());
                  analysis.put("positions", edits.positions());
                  rfp.setAnalysis(analysis);
                }
                if (edits.requirements() != null) {
                  rfp.setRequirements(edits.requirements());
                }
              }
              rfp.setPhase(RfpPhase.APPROVED);
              RfpEntity saved = rfpRepository.save(rfp);
              appendHistory(saved, RfpPhase.DRAFT, RfpPhase.APPROVED, "manual", actor, null);
              return new WorkflowResult(saved, List.of());
            });
    // OUTSIDE TX: publish (fail-soft) — recruiter+admin per the P1 audience rules.
    publisher.publishMessage(
        "rfp.approved",
        "RFP approved: " + result.rfp().getTitle(),
        null,
        "success",
        List.of(ModuleRoles.RECRUITER, ModuleRoles.ADMIN),
        null);
    return result;
  }

  /** Any non-terminal phase → CLOSED; cancels all unfinished candidate processes (D3). */
  public WorkflowResult close(UUID rfpId, boolean confirm, String reason, String actor) {
    if (!confirm) {
      throw new IllegalArgumentException("close requires confirm=true");
    }
    return terminalAction(rfpId, RfpPhase.CLOSED, reason, actor);
  }

  /** Any non-terminal phase → REJECTED; reason required (D4: engagement declined). */
  public WorkflowResult reject(UUID rfpId, String reason, String actor) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reject requires a reason");
    }
    return terminalAction(rfpId, RfpPhase.REJECTED, reason, actor);
  }

  /** CLOSED | REJECTED → ARCHIVED (terminal). */
  public WorkflowResult archive(UUID rfpId, String actor) {
    return tx.execute(
        status -> {
          RfpEntity rfp = loadRfp(rfpId);
          if (rfp.getPhase() != RfpPhase.CLOSED && rfp.getPhase() != RfpPhase.REJECTED) {
            throw conflict(
                "only closed or rejected rfps can be archived (current phase: "
                    + rfp.getPhase().value()
                    + ")");
          }
          RfpPhase from = rfp.getPhase();
          rfp.setPhase(RfpPhase.ARCHIVED);
          RfpEntity saved = rfpRepository.save(rfp);
          appendHistory(saved, from, RfpPhase.ARCHIVED, "manual", actor, null);
          return new WorkflowResult(saved, List.of());
        });
  }

  // ── Derived recompute (called after every candidate-process mutation; P2) ──

  /**
   * Recomputes the phase from all process stages (D3/D5); appends history + publishes on change.
   */
  public WorkflowResult rederive(UUID rfpId, String actor) {
    WorkflowResult result =
        tx.execute(
            status -> {
              RfpEntity rfp = loadRfp(rfpId);
              List<ProcessStage> stages =
                  processRepository.findByRfpIdOrderByCreatedAtAsc(rfpId).stream()
                      .map(CandidateProcessEntity::getStage)
                      .toList();
              RfpPhase derived = derivation.derive(rfp.getPhase(), stages);
              if (derived == rfp.getPhase()) {
                return new WorkflowResult(rfp, List.of());
              }
              RfpPhase from = rfp.getPhase();
              rfp.setPhase(derived);
              RfpEntity saved = rfpRepository.save(rfp);
              appendHistory(saved, from, derived, "derived", actor, null);
              return new WorkflowResult(saved, List.of());
            });
    if (!result.rfp().getPhase().isDerived()) {
      return result;
    }
    // OUTSIDE TX: derived phase change event (fail-soft), audience manager+recruiter+admin.
    publisher.publishMessage(
        "rfp.phase-changed",
        "RFP phase now " + result.rfp().getPhase().value(),
        null,
        "info",
        List.of(ModuleRoles.MANAGER, ModuleRoles.RECRUITER, ModuleRoles.ADMIN),
        null);
    return result;
  }

  // ── Internals ────────────────────────────────────────────────────────

  private WorkflowResult terminalAction(UUID rfpId, RfpPhase toPhase, String reason, String actor) {
    WorkflowResult result =
        tx.execute(
            status -> {
              RfpEntity rfp = loadRfp(rfpId);
              if (rfp.getPhase().isManualTerminal()) {
                throw conflict(
                    "rfp is already "
                        + rfp.getPhase().value()
                        + " (manual phases are sticky until archive)");
              }
              RfpPhase from = rfp.getPhase();
              rfp.setPhase(toPhase);
              RfpEntity saved = rfpRepository.save(rfp);
              appendHistory(saved, from, toPhase, "manual", actor, reason);
              List<UUID> cancelled =
                  cancelActiveProcesses(saved, actor, "cancelled by rfp " + toPhase.value());
              return new WorkflowResult(saved, cancelled);
            });
    // OUTSIDE TX: rfp.closed / rfp.rejected events (P2.5), audience manager+recruiter+admin.
    List<String> audience = List.of(ModuleRoles.MANAGER, ModuleRoles.RECRUITER, ModuleRoles.ADMIN);
    publisher.publishMessage(
        toPhase == RfpPhase.CLOSED ? "rfp.closed" : "rfp.rejected",
        toPhase == RfpPhase.CLOSED ? "RFP closed" : "RFP rejected",
        reason == null ? null : "reason: " + reason,
        "warning",
        audience,
        null);
    return result;
  }

  private List<UUID> cancelActiveProcesses(RfpEntity rfp, String actor, String note) {
    List<UUID> cancelled = new ArrayList<>();
    for (CandidateProcessEntity process :
        processRepository.findByRfpIdOrderByCreatedAtAsc(rfp.getId())) {
      if (process.getStage().isTerminal()) {
        continue;
      }
      process.setStage(ProcessStage.CANCELLED);
      process.getStageHistory().add(stageHistoryEntry(ProcessStage.CANCELLED, actor, note));
      processRepository.save(process);
      cancelled.add(process.getId());
    }
    return cancelled;
  }

  private Map<String, Object> stageHistoryEntry(ProcessStage stage, String by, String note) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("stage", stage.value());
    entry.put("at", java.time.Instant.now().toString());
    entry.put("by", by);
    entry.put("note", note);
    return entry;
  }

  private void appendHistory(
      RfpEntity rfp,
      RfpPhase fromPhase,
      RfpPhase toPhase,
      String trigger,
      String actor,
      String note) {
    RfpPhaseHistoryEntity row = new RfpPhaseHistoryEntity();
    row.setRfpId(rfp.getId());
    row.setFromPhase(fromPhase);
    row.setToPhase(toPhase);
    row.setTrigger(trigger);
    row.setActor(actor);
    row.setNote(note);
    historyRepository.save(row);
  }

  private RfpEntity loadRfp(UUID rfpId) {
    if (rfpId == null) {
      throw new IllegalArgumentException("rfpId is required");
    }
    return rfpRepository
        .findById(rfpId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "rfp not found"));
  }

  private static void requirePhase(RfpEntity rfp, RfpPhase expected, String message) {
    if (rfp.getPhase() != expected) {
      throw conflict(message);
    }
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
