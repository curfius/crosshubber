package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** RfpPhase: value round-trip, manual/derived set membership, parse errors. */
class RfpPhaseTest {

  @Test
  void allNinePhasesRoundTripThroughValue() {
    for (RfpPhase phase : RfpPhase.values()) {
      assertThat(RfpPhase.fromString(phase.value())).isEqualTo(phase);
      assertThat(phase.value()).isEqualTo(phase.name().toLowerCase(java.util.Locale.ROOT));
    }
    assertThat(RfpPhase.values()).hasSize(9);
  }

  @Test
  void manualTerminalIsExactlyClosedRejectedArchived() {
    assertThat(RfpPhase.MANUAL_TERMINAL)
        .containsExactlyInAnyOrder(RfpPhase.CLOSED, RfpPhase.REJECTED, RfpPhase.ARCHIVED);
    assertThat(RfpPhase.CLOSED.isManualTerminal()).isTrue();
    assertThat(RfpPhase.ARCHIVED.isManualTerminal()).isTrue();
    assertThat(RfpPhase.SOURCING.isManualTerminal()).isFalse();
    assertThat(RfpPhase.DRAFT.isManualTerminal()).isFalse();
  }

  @Test
  void derivedSetIsExactlyTheFiveProcessDrivenPhases() {
    assertThat(RfpPhase.DERIVED)
        .containsExactlyInAnyOrder(
            RfpPhase.APPROVED,
            RfpPhase.SOURCING,
            RfpPhase.OFFER_EXTENDED,
            RfpPhase.ACCEPTED,
            RfpPhase.PENDING_ONBOARDING);
    assertThat(RfpPhase.DRAFT.isDerived()).isFalse();
  }

  @Test
  void fromStringTrimsCaseInsensitivelyAndRejectsGarbage() {
    assertThat(RfpPhase.fromString("  Offer_Extended ")).isEqualTo(RfpPhase.OFFER_EXTENDED);
    assertThatThrownBy(() -> RfpPhase.fromString("nope"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RfpPhase.fromString(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RfpPhase.fromString(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptedAndPendingOnboardingAreDistinctPhases() {
    // D-recorded decision: ACCEPTED = ≥1 accepted process; PENDING_ONBOARDING = accepted
    // processes not yet onboarded. They are separate values.
    assertThat(Set.of(RfpPhase.ACCEPTED, RfpPhase.PENDING_ONBOARDING)).hasSize(2);
  }
}
