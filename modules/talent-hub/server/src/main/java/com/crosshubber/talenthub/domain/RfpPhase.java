package com.crosshubber.talenthub.domain;

import java.util.Locale;
import java.util.Set;

/**
 * RFP macro phase (D3): {@code approved|sourcing|offer_extended|accepted|pending_onboarding} are
 * <b>derived</b> from candidate processes (recomputed after every process mutation); {@code
 * closed|rejected|archived} are <b>manual</b> terminal phases; {@code draft} only moves via the
 * manager approve action.
 */
public enum RfpPhase {
  DRAFT,
  APPROVED,
  SOURCING,
  OFFER_EXTENDED,
  ACCEPTED,
  PENDING_ONBOARDING,
  CLOSED,
  REJECTED,
  ARCHIVED;

  /** Manual-terminal phases — sticky until archive. */
  public static final Set<RfpPhase> MANUAL_TERMINAL = Set.of(CLOSED, REJECTED, ARCHIVED);

  /** Phases recomputed by the {@code PhaseDerivationService} from process stages. */
  public static final Set<RfpPhase> DERIVED =
      Set.of(APPROVED, SOURCING, OFFER_EXTENDED, ACCEPTED, PENDING_ONBOARDING);

  public boolean isManualTerminal() {
    return MANUAL_TERMINAL.contains(this);
  }

  public boolean isDerived() {
    return DERIVED.contains(this);
  }

  public static RfpPhase fromString(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("phase is required");
    }
    try {
      return RfpPhase.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("unknown phase: " + raw);
    }
  }

  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }
}
