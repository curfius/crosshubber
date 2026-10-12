package com.crosshubber.talenthub.domain;

import java.util.List;

import org.springframework.stereotype.Service;

/**
 * Pure phase derivation (D3/D5): recomputes the RFP phase from its current phase and the stages of
 * ALL its candidate processes. Manual-terminal phases are sticky ({@code closed|rejected| archived}
 * never derive); {@code draft} only moves via the manager approve action. Priority:
 * onboarding/onboarded → pending_onboarding, then accepted, then offer outstanding, then any
 * process at all → sourcing (sticky once entered), else approved.
 */
@Service
public class PhaseDerivationService {

  private static final List<ProcessStage> ONBOARDING_WINNERS =
      List.of(ProcessStage.ONBOARDING, ProcessStage.ONBOARDED);

  /**
   * @param current the phase currently stored on the RFP row
   * @param processStages the stages of all candidate processes of the RFP (any stage, including
   *     terminal ones — stickiness relies on seeing them)
   * @return the phase the RFP should carry after the mutation that triggered this call
   */
  public RfpPhase derive(RfpPhase current, List<ProcessStage> processStages) {
    if (current == null) {
      throw new IllegalArgumentException("current phase is required");
    }
    if (current.isManualTerminal()) {
      return current;
    }
    if (current == RfpPhase.DRAFT) {
      return RfpPhase.DRAFT;
    }
    List<ProcessStage> stages = processStages == null ? List.of() : processStages;
    for (ProcessStage stage : stages) {
      if (ONBOARDING_WINNERS.contains(stage)) {
        return RfpPhase.PENDING_ONBOARDING;
      }
    }
    if (stages.contains(ProcessStage.ACCEPTED)) {
      return RfpPhase.ACCEPTED;
    }
    if (stages.contains(ProcessStage.OFFER)) {
      return RfpPhase.OFFER_EXTENDED;
    }
    if (!stages.isEmpty()) {
      return RfpPhase.SOURCING;
    }
    return RfpPhase.APPROVED;
  }
}
