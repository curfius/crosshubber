package com.crosshubber.portal.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/**
 * Node-compatible date formatting — always exactly 3 fraction digits UTC. Mirrors Node's {@code
 * JSON.stringify(new Date())} which produces {@code 2026-09-02T10:15:30.000Z}.
 */
public final class NodeDates {

  private NodeDates() {}

  private static final DateTimeFormatter FORMATTER =
      new DateTimeFormatterBuilder().appendInstant(3).toFormatter().withZone(ZoneOffset.UTC);

  /** Formats an Instant to Node-compatible ISO-8601 with exactly 3 fraction digits. */
  public static String format(Instant instant) {
    if (instant == null) {
      return null;
    }
    return FORMATTER.format(instant);
  }
}
