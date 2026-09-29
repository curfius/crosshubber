package com.crosshubber.solutions.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Workflow transition matrix + stage-gate validation (AI_MODULES_PLAN H2). */
class StageTest {

  @Test
  void linearSpineIsEnforced() {
    assertThat(Stage.LEAD.canTransitionTo(Stage.QUALIFIED)).isTrue();
    assertThat(Stage.QUALIFIED.canTransitionTo(Stage.PROPOSAL)).isTrue();
    assertThat(Stage.PROPOSAL.canTransitionTo(Stage.SENT)).isTrue();
    assertThat(Stage.SENT.canTransitionTo(Stage.NEGOTIATION)).isTrue();
    assertThat(Stage.SENT.canTransitionTo(Stage.WON)).isTrue();
    assertThat(Stage.NEGOTIATION.canTransitionTo(Stage.WON)).isTrue();
    assertThat(Stage.WON.canTransitionTo(Stage.IMPLEMENTATION)).isTrue();
    assertThat(Stage.IMPLEMENTATION.canTransitionTo(Stage.DELIVERY)).isTrue();
    assertThat(Stage.DELIVERY.canTransitionTo(Stage.CLOSED)).isTrue();
  }

  @Test
  void skippedStagesAreRejected() {
    assertThat(Stage.LEAD.canTransitionTo(Stage.PROPOSAL)).isFalse();
    assertThat(Stage.LEAD.canTransitionTo(Stage.WON)).isFalse();
    assertThat(Stage.PROPOSAL.canTransitionTo(Stage.IMPLEMENTATION)).isFalse();
    assertThat(Stage.WON.canTransitionTo(Stage.LOST)).isFalse();
  }

  @Test
  void lostIsReachableFromPursuitStagesOnly() {
    assertThat(Stage.LEAD.canTransitionTo(Stage.LOST)).isTrue();
    assertThat(Stage.QUALIFIED.canTransitionTo(Stage.LOST)).isTrue();
    assertThat(Stage.PROPOSAL.canTransitionTo(Stage.LOST)).isTrue();
    assertThat(Stage.SENT.canTransitionTo(Stage.LOST)).isTrue();
    assertThat(Stage.NEGOTIATION.canTransitionTo(Stage.LOST)).isTrue();
    assertThat(Stage.WON.canTransitionTo(Stage.LOST)).isFalse();
    assertThat(Stage.IMPLEMENTATION.canTransitionTo(Stage.LOST)).isFalse();
  }

  @Test
  void negotiationCanResend() {
    assertThat(Stage.NEGOTIATION.canTransitionTo(Stage.SENT)).isTrue();
  }

  @Test
  void terminalStagesHaveNoOutgoingTransitions() {
    for (Stage stage : Stage.values()) {
      if (stage.isTerminal()) {
        assertThat(stage).isIn(Stage.LOST, Stage.CLOSED);
        assertThat(stage.canTransitionTo(Stage.QUALIFIED)).isFalse();
        assertThat(stage.canTransitionTo(Stage.LOST)).isFalse();
        assertThat(stage.canTransitionTo(Stage.CLOSED)).isFalse();
      }
    }
  }

  @Test
  void stageGatesDeclareRequiredFields() {
    assertThat(Stage.QUALIFIED.requiredFields())
        .containsExactly("goDecision", "pursuitOwner", "estimate", "decisionDate");
    assertThat(Stage.LEAD.requiredFields()).isEmpty();
    assertThat(Stage.CLOSED.requiredFields()).containsExactly("handoverRef", "retrospective");
  }

  @Test
  void parsesHyphenatedAndMixedCaseValues() {
    assertThat(Stage.fromString("lead")).isEqualTo(Stage.LEAD);
    assertThat(Stage.fromString("on-track".replace("on-track", "WON"))).isEqualTo(Stage.WON);
    assertThatThrownBy(() -> Stage.fromString("bogus"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stage.fromString(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void gateValidationHelperDetectsMissingFields() {
    var missing =
        Stage.missingGateFields(Map.of("goDecision", "go", "pursuitOwner", " "), Stage.QUALIFIED);

    assertThat(missing).containsExactly("pursuitOwner", "estimate", "decisionDate");
    assertThat(Stage.missingGateFields(null, Stage.CLOSED))
        .containsExactly("handoverRef", "retrospective");
  }
}
