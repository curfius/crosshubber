package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** ProcessStage: transition table, terminal set, cancelled unreachable via normal transitions. */
class ProcessStageTest {

  @Test
  void allElevenStagesRoundTripThroughValue() {
    for (ProcessStage stage : ProcessStage.values()) {
      assertThat(ProcessStage.fromString(stage.value())).isEqualTo(stage);
      assertThat(stage.value()).isEqualTo(stage.name().toLowerCase(java.util.Locale.ROOT));
    }
    assertThat(ProcessStage.values()).hasSize(11);
  }

  @Test
  void happyPathChainTransitionsValidly() {
    assertThat(ProcessStage.POOLED.canTransitionTo(ProcessStage.ASSESSED)).isTrue();
    assertThat(ProcessStage.ASSESSED.canTransitionTo(ProcessStage.SUBMITTED)).isTrue();
    assertThat(ProcessStage.SUBMITTED.canTransitionTo(ProcessStage.INTERVIEWING)).isTrue();
    assertThat(ProcessStage.INTERVIEWING.canTransitionTo(ProcessStage.OFFER)).isTrue();
    assertThat(ProcessStage.OFFER.canTransitionTo(ProcessStage.ACCEPTED)).isTrue();
    assertThat(ProcessStage.ACCEPTED.canTransitionTo(ProcessStage.ONBOARDING)).isTrue();
    assertThat(ProcessStage.ONBOARDING.canTransitionTo(ProcessStage.ONBOARDED)).isTrue();
  }

  @Test
  void rejectionAndWithdrawalAreReachableFromTheRightStages() {
    // REJECTED (client side) only once submitted.
    assertThat(ProcessStage.SUBMITTED.canTransitionTo(ProcessStage.REJECTED)).isTrue();
    assertThat(ProcessStage.INTERVIEWING.canTransitionTo(ProcessStage.REJECTED)).isTrue();
    assertThat(ProcessStage.OFFER.canTransitionTo(ProcessStage.REJECTED)).isTrue();
    assertThat(ProcessStage.POOLED.canTransitionTo(ProcessStage.REJECTED)).isFalse();
    assertThat(ProcessStage.ASSESSED.canTransitionTo(ProcessStage.REJECTED)).isFalse();

    // WITHDRAWN: candidate exits at any point (except terminal states).
    for (ProcessStage stage : ProcessStage.values()) {
      if (stage.isTerminal() || stage == ProcessStage.CANCELLED) {
        continue;
      }
      assertThat(stage.canTransitionTo(ProcessStage.WITHDRAWN)).as(stage.name()).isTrue();
    }
  }

  @Test
  void terminalSetIsExactAndClosedForBusiness() {
    assertThat(ProcessStage.TERMINAL)
        .containsExactlyInAnyOrder(
            ProcessStage.ONBOARDED,
            ProcessStage.REJECTED,
            ProcessStage.WITHDRAWN,
            ProcessStage.CANCELLED);
    for (ProcessStage stage : ProcessStage.TERMINAL) {
      assertThat(stage.canTransitionTo(ProcessStage.POOLED))
          .as("terminal " + stage + " must not transition")
          .isFalse();
    }
  }

  @Test
  void cancelledIsNotReachableViaNormalTransitions() {
    // Only the close/reject bulk-cancel path sets CANCELLED (D3) — never a normal transition.
    for (ProcessStage stage : ProcessStage.values()) {
      assertThat(stage.canTransitionTo(ProcessStage.CANCELLED)).as(stage.name()).isFalse();
    }
  }

  @Test
  void fromStringTrimsCaseInsensitivelyAndRejectsGarbage() {
    assertThat(ProcessStage.fromString(" Offer ")).isEqualTo(ProcessStage.OFFER);
    assertThatThrownBy(() -> ProcessStage.fromString("nope"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ProcessStage.fromString(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
