package com.crosshubber.staffing.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** RFP lifecycle: linear spine with {@code archived} reachable from every active stage. */
public enum RfpStatus {
  NEW,
  ANALYZING,
  MATCHED,
  SHORTLISTED,
  SUBMITTED,
  ARCHIVED;

  private static final Map<RfpStatus, Set<RfpStatus>> TRANSITIONS =
      Map.of(
          NEW, Set.of(ANALYZING, ARCHIVED),
          ANALYZING, Set.of(MATCHED, ARCHIVED),
          MATCHED, Set.of(SHORTLISTED, ARCHIVED),
          SHORTLISTED, Set.of(SUBMITTED, ARCHIVED),
          SUBMITTED, Set.of(ARCHIVED),
          ARCHIVED, Set.of());

  public boolean canTransitionTo(RfpStatus target) {
    return TRANSITIONS.get(this).contains(target);
  }

  public boolean isTerminal() {
    return TRANSITIONS.get(this).isEmpty();
  }

  public static RfpStatus fromString(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("status is required");
    }
    try {
      return RfpStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("unknown status: " + raw);
    }
  }

  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }
}
