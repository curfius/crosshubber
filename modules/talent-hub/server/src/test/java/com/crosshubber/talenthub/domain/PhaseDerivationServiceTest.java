package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Table-driven derivation: every phase, regressions, manual stickiness (P0.7 target). */
class PhaseDerivationServiceTest {

  private final PhaseDerivationService service = new PhaseDerivationService();

  private RfpPhase derive(RfpPhase current, ProcessStage... stages) {
    return service.derive(current, List.of(stages));
  }

  @Test
  void manualTerminalPhasesAreStickyUnderAnyProcesses() {
    for (RfpPhase terminal : RfpPhase.MANUAL_TERMINAL) {
      assertThat(derive(terminal, ProcessStage.POOLED, ProcessStage.OFFER))
          .as(terminal.value())
          .isEqualTo(terminal);
      assertThat(derive(terminal)).as(terminal.value()).isEqualTo(terminal);
    }
  }

  @Test
  void draftNeverDerives() {
    assertThat(derive(RfpPhase.DRAFT, ProcessStage.POOLED, ProcessStage.OFFER))
        .isEqualTo(RfpPhase.DRAFT);
    assertThat(derive(RfpPhase.DRAFT)).isEqualTo(RfpPhase.DRAFT);
  }

  @Test
  void approvedWithoutProcessesStaysApproved() {
    assertThat(derive(RfpPhase.APPROVED)).isEqualTo(RfpPhase.APPROVED);
  }

  @Test
  void firstProcessEntersSourcingRegardlessOfStage() {
    assertThat(derive(RfpPhase.APPROVED, ProcessStage.POOLED)).isEqualTo(RfpPhase.SOURCING);
    assertThat(derive(RfpPhase.APPROVED, ProcessStage.WITHDRAWN)).isEqualTo(RfpPhase.SOURCING);
  }

  @Test
  void sourcingIsStickyOnceAnyProcessExists() {
    assertThat(derive(RfpPhase.SOURCING, ProcessStage.WITHDRAWN)).isEqualTo(RfpPhase.SOURCING);
    assertThat(derive(RfpPhase.OFFER_EXTENDED, ProcessStage.REJECTED, ProcessStage.WITHDRAWN))
        .isEqualTo(RfpPhase.SOURCING);
    assertThat(derive(RfpPhase.ACCEPTED, ProcessStage.WITHDRAWN)).isEqualTo(RfpPhase.SOURCING);
  }

  @Test
  void outstandingOfferDerivesOfferExtended() {
    assertThat(derive(RfpPhase.APPROVED, ProcessStage.OFFER)).isEqualTo(RfpPhase.OFFER_EXTENDED);
    assertThat(derive(RfpPhase.SOURCING, ProcessStage.POOLED, ProcessStage.OFFER))
        .isEqualTo(RfpPhase.OFFER_EXTENDED);
  }

  @Test
  void acceptedProcessDerivesAccepted() {
    assertThat(derive(RfpPhase.SOURCING, ProcessStage.ACCEPTED)).isEqualTo(RfpPhase.ACCEPTED);
  }

  @Test
  void onboardingOrOnboardedDerivesPendingOnboarding() {
    assertThat(derive(RfpPhase.ACCEPTED, ProcessStage.ONBOARDING))
        .isEqualTo(RfpPhase.PENDING_ONBOARDING);
    assertThat(derive(RfpPhase.ACCEPTED, ProcessStage.ONBOARDED))
        .isEqualTo(RfpPhase.PENDING_ONBOARDING);
  }

  @Test
  void winnerPriorityOnboardingOverAcceptedOverOffer() {
    assertThat(derive(RfpPhase.APPROVED, ProcessStage.ONBOARDING, ProcessStage.ACCEPTED))
        .isEqualTo(RfpPhase.PENDING_ONBOARDING);
    assertThat(derive(RfpPhase.APPROVED, ProcessStage.ACCEPTED, ProcessStage.OFFER))
        .isEqualTo(RfpPhase.ACCEPTED);
    assertThat(derive(RfpPhase.APPROVED, ProcessStage.OFFER, ProcessStage.POOLED))
        .isEqualTo(RfpPhase.OFFER_EXTENDED);
  }

  @Test
  void allOffersRejectedRegressesToSourcing() {
    assertThat(derive(RfpPhase.OFFER_EXTENDED, ProcessStage.REJECTED)).isEqualTo(RfpPhase.SOURCING);
  }

  @Test
  void nullCurrentPhaseFailsFast() {
    assertThatThrownBy(() -> service.derive(null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
