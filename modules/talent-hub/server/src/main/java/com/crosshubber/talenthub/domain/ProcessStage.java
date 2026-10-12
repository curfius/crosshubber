package com.crosshubber.talenthub.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Candidate process stage (the per candidate × position sub-process; domain plan §3.2). Every
 * process carries its own stage + history. {@code cancelled} is <b>not</b> a normal transition — it
 * is only force-set in bulk when an RFP is closed/rejected (D3).
 */
public enum ProcessStage {
  POOLED,
  ASSESSED,
  SUBMITTED,
  INTERVIEWING,
  OFFER,
  ACCEPTED,
  ONBOARDING,
  ONBOARDED,
  REJECTED,
  WITHDRAWN,
  CANCELLED;

  private static final Map<ProcessStage, Set<ProcessStage>> TRANSITIONS =
      Map.ofEntries(
          Map.entry(POOLED, Set.of(ASSESSED, WITHDRAWN)),
          Map.entry(ASSESSED, Set.of(SUBMITTED, WITHDRAWN)),
          Map.entry(SUBMITTED, Set.of(INTERVIEWING, REJECTED, WITHDRAWN)),
          Map.entry(INTERVIEWING, Set.of(OFFER, REJECTED, WITHDRAWN)),
          Map.entry(OFFER, Set.of(ACCEPTED, REJECTED, WITHDRAWN)),
          Map.entry(ACCEPTED, Set.of(ONBOARDING, WITHDRAWN)),
          Map.entry(ONBOARDING, Set.of(ONBOARDED, WITHDRAWN)),
          Map.entry(ONBOARDED, Set.<ProcessStage>of()),
          Map.entry(REJECTED, Set.<ProcessStage>of()),
          Map.entry(WITHDRAWN, Set.<ProcessStage>of()),
          Map.entry(CANCELLED, Set.<ProcessStage>of()));

  /** Stages that are end states for the process (no further transitions). */
  public static final Set<ProcessStage> TERMINAL =
      Set.of(ONBOARDED, REJECTED, WITHDRAWN, CANCELLED);

  public boolean canTransitionTo(ProcessStage target) {
    return TRANSITIONS.get(this).contains(target);
  }

  public boolean isTerminal() {
    return TERMINAL.contains(this);
  }

  public static ProcessStage fromString(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("stage is required");
    }
    try {
      return ProcessStage.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("unknown stage: " + raw);
    }
  }

  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }
}
