package com.crosshubber.portal.modules.msgcenter.email;

/**
 * Live SMTP config resolved from {@code module_settings} (Phase 7). Immutable snapshot; the secret
 * is decrypted at assembly and never logged or echoed.
 */
public record SmtpConfig(
    String host, int port, String from, String tls, String authUser, String secret) {

  /** Channel gate — blank host = off (fail-soft, mirrors portal.nats-url). */
  public boolean enabled() {
    return host != null && !host.isBlank();
  }
}
