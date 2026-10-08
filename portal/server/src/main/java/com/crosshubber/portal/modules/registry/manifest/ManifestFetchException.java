package com.crosshubber.portal.modules.registry.manifest;

import java.util.Locale;

/**
 * Classified manifest-fetch failure: a stable {@link Code} the registry UI maps to localized text,
 * the URL that was attempted, and a short English detail (surfaced verbatim as secondary
 * information next to the localized message).
 */
public class ManifestFetchException extends Exception {

  /** Machine-readable cause; the wire value is lowercase-hyphenated ({@code upstream-status}). */
  public enum Code {
    INVALID_URL,
    BLOCKED,
    UNKNOWN_HOST,
    UNREACHABLE,
    TIMEOUT,
    UPSTREAM_STATUS,
    TOO_LARGE,
    NOT_JSON,
    FAILED;

    /** Value written into the {@code "code"} field of the error envelope. */
    public String value() {
      return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
  }

  private final Code code;
  private final String url;
  private final String detail;

  public ManifestFetchException(Code code, String url, String detail, Throwable cause) {
    super("could not fetch " + url + ": " + normalize(detail), cause);
    this.code = code;
    this.url = url;
    this.detail = normalize(detail);
  }

  public Code code() {
    return code;
  }

  public String url() {
    return url;
  }

  public String detail() {
    return detail;
  }

  private static String normalize(String detail) {
    return detail == null || detail.isBlank() ? "fetch failed" : detail;
  }
}
