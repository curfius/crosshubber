package com.crosshubber.solutions.domain;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Project delivery workflow (AI_MODULES_PLAN H2): linear spine with terminal {@link #LOST} and
 * {@link #CLOSED} branches. Transitions and stage-gate fields are validated server-side; every
 * accepted transition writes an immutable {@code stage_event} row.
 */
public enum Stage {
  LEAD,
  QUALIFIED,
  PROPOSAL,
  SENT,
  NEGOTIATION,
  WON,
  LOST,
  IMPLEMENTATION,
  DELIVERY,
  CLOSED;

  private static final Map<Stage, Set<Stage>> TRANSITIONS =
      Map.of(
          LEAD, Set.of(QUALIFIED, LOST),
          QUALIFIED, Set.of(PROPOSAL, LOST),
          PROPOSAL, Set.of(SENT, LOST),
          SENT, Set.of(NEGOTIATION, WON, LOST),
          NEGOTIATION, Set.of(WON, LOST, SENT),
          WON, Set.of(IMPLEMENTATION),
          LOST, Set.of(),
          IMPLEMENTATION, Set.of(DELIVERY),
          DELIVERY, Set.of(CLOSED),
          CLOSED, Set.of());

  /** Stage-gate data required in {@code stageData} to enter the stage. */
  private static final Map<Stage, List<String>> REQUIRED_FIELDS =
      Map.ofEntries(
          Map.entry(QUALIFIED, List.of("goDecision", "pursuitOwner", "estimate", "decisionDate")),
          Map.entry(PROPOSAL, List.of("scope", "team", "pricing", "timeline")),
          Map.entry(SENT, List.of("sentDate", "version", "channel")),
          Map.entry(NEGOTIATION, List.of("version", "clientFeedback", "nextStep")),
          Map.entry(WON, List.of("contractRef", "budget", "startDate")),
          Map.entry(LOST, List.of("reason", "lessons")),
          Map.entry(IMPLEMENTATION, List.of("kickoffDate", "milestones", "budgetBurn", "health")),
          Map.entry(DELIVERY, List.of("uatDate", "acceptanceCriteria", "signOff")),
          Map.entry(CLOSED, List.of("handoverRef", "retrospective")));

  /** Health vocabulary while in IMPLEMENTATION. */
  public static final Set<String> HEALTH_VALUES = Set.of("on-track", "at-risk", "delayed");

  public boolean canTransitionTo(Stage target) {
    return TRANSITIONS.get(this).contains(target);
  }

  public List<String> requiredFields() {
    return REQUIRED_FIELDS.getOrDefault(this, List.of());
  }

  /** Gate-field check: which required fields are missing/blank in the given stage data. */
  public static List<String> missingGateFields(Map<String, Object> stageData, Stage target) {
    List<String> missing = new java.util.ArrayList<>();
    for (String field : target.requiredFields()) {
      Object value = stageData == null ? null : stageData.get(field);
      if (value == null
          || (value instanceof String s && s.isBlank())
          || (value instanceof List<?> list && list.isEmpty())) {
        missing.add(field);
      }
    }
    return List.copyOf(missing);
  }

  public boolean isTerminal() {
    return TRANSITIONS.get(this).isEmpty();
  }

  public static Stage fromString(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("stage is required");
    }
    try {
      return Stage.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("unknown stage: " + raw);
    }
  }

  public String value() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
