package com.crosshubber.portal.common;

import java.util.List;

/** Scalar coercion helpers for loosely-typed JSON input. */
public final class Texts {

  /** The value as a String, or null when absent/not a string. */
  public static String string(Object value) {
    return value instanceof String s ? s : null;
  }

  public static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  public static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  /** The value when non-blank, otherwise null — optional DTO field mapping. */
  public static String blankToNull(String value) {
    return notBlank(value) ? value : null;
  }

  /**
   * Comma-joins a JSON array (or passes a raw string through) for storage in flattened text columns
   * — used for entry point roles and sandbox tokens.
   */
  public static String joinComma(Object value) {
    if (value instanceof List<?> list) {
      return list.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(null);
    }
    return value instanceof String s ? s : null;
  }

  private Texts() {}
}
