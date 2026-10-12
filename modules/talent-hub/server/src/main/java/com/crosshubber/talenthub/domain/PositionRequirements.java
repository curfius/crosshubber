package com.crosshubber.talenthub.domain;

import java.util.Map;

/** Position-side requirement fields the engines can read (from position jsonb, or RFP defaults). */
public record PositionRequirements(Map<String, Object> raw) {

  public static PositionRequirements from(Map<String, Object> requirements) {
    return new PositionRequirements(requirements == null ? Map.of() : requirements);
  }

  public Object field(String key) {
    return raw.get(key);
  }

  public boolean hasAny(String... keys) {
    for (String key : keys) {
      if (raw.get(key) != null) {
        return true;
      }
    }
    return false;
  }
}
