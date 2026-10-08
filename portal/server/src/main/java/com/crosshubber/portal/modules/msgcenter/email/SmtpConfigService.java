package com.crosshubber.portal.modules.msgcenter.email;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.modules.settings.modules.ModuleSettingsService;
import com.crosshubber.portal.security.CryptoService;

/**
 * SMTP configuration for the message-center email mirror (Phase 7).
 *
 * <p>Stored in {@code module_settings} under the {@code msgcenter} key, group {@code email}: {@code
 * {host, port, from, tls, authUser, secretEnc}}. The password is AES-256-GCM encrypted
 * (CryptoService) and never echoed — reads carry only a presence flag. {@code PORTAL_SMTP_*}
 * environment variables seed the group insert-if-absent at boot (admin edits always win — same
 * semantics as the i18n label seeds). Blank host = channel off (fail-soft, mirrors
 * portal.nats-url).
 */
@Service
public class SmtpConfigService {

  private static final String MODULE_KEY = "msgcenter";
  private static final String GROUP = "email";

  private final ModuleSettingsService settingsService;
  private final CryptoService cryptoService;
  private final Environment env;

  public SmtpConfigService(
      ModuleSettingsService settingsService, CryptoService cryptoService, Environment env) {
    this.settingsService = settingsService;
    this.cryptoService = cryptoService;
    this.env = env;
  }

  /** Masked view for the settings card — the secret never leaves the server. */
  public record SmtpConfigView(
      String host,
      Integer port,
      String from,
      String tls,
      String authUser,
      boolean passwordSet,
      Instant updatedAt) {}

  public SmtpConfigView view() {
    Map<String, Object> group = group();
    if (group.isEmpty()) {
      return new SmtpConfigView(null, null, null, null, null, false, null);
    }
    return new SmtpConfigView(
        stringOf(group.get("host")),
        intOrNull(group.get("port")),
        stringOf(group.get("from")),
        stringOf(group.get("tls")),
        stringOf(group.get("authUser")),
        group.get("secretEnc") != null,
        instantOrNull(group.get("updatedAt")));
  }

  /** Live config for the sender; null when unconfigured. */
  public SmtpConfig live() {
    Map<String, Object> group = group();
    String host = stringOf(group.get("host"));
    if (host == null || host.isBlank()) {
      return null;
    }
    Integer port = intOrNull(group.get("port"));
    String secretEnc = stringOf(group.get("secretEnc"));
    return new SmtpConfig(
        host,
        port == null ? 25 : port,
        stringOf(group.get("from")),
        stringOf(group.get("tls")),
        stringOf(group.get("authUser")),
        secretEnc == null ? null : cryptoService.decryptApiKey(secretEnc));
  }

  /** Admin save — password accepted as plaintext, stored encrypted; null keeps the old one. */
  @Transactional
  public SmtpConfigView update(
      String host,
      Integer port,
      String from,
      String tls,
      String authUser,
      String password,
      String actorSub) {
    Map<String, Object> partial = new LinkedHashMap<>();
    if (host != null) partial.put("host", host);
    if (port != null) partial.put("port", port);
    if (from != null) partial.put("from", from);
    if (tls != null) partial.put("tls", tls);
    if (authUser != null) partial.put("authUser", authUser);
    if (password != null && !password.isBlank()) {
      partial.put("secretEnc", cryptoService.encryptApiKey(password));
    }
    if (!partial.isEmpty()) {
      partial.put("updatedBy", actorSub);
      partial.put("updatedAt", Instant.now().toString());
      settingsService.update(MODULE_KEY, Map.of(GROUP, partial));
    }
    return view();
  }

  /**
   * Boot seed: {@code PORTAL_SMTP_*} env applies only when the group is blank (insert-if-absent —
   * never overwrites admin edits). Fail-soft: seeding errors log and skip.
   */
  void seedFromEnv() {
    try {
      Map<String, Object> group = group();
      if (!group.isEmpty()) {
        return;
      }
      String host = env.getProperty("PORTAL_SMTP_HOST");
      if (host == null || host.isBlank()) {
        return;
      }
      Map<String, Object> partial = new LinkedHashMap<>();
      partial.put("host", host);
      String port = env.getProperty("PORTAL_SMTP_PORT");
      if (port != null && !port.isBlank()) {
        partial.put("port", Integer.parseInt(port.trim()));
      }
      String from = env.getProperty("PORTAL_SMTP_FROM");
      if (from != null && !from.isBlank()) {
        partial.put("from", from);
      }
      partial.put("tls", "none");
      partial.put("updatedAt", Instant.now().toString());
      settingsService.update(MODULE_KEY, Map.of(GROUP, partial));
    } catch (Exception e) {
      // DIAGNOSTIC (Phase 7 IT stabilization): the seed must never fail silently — log loudly,
      // keep boot intact.
      org.slf4j.LoggerFactory.getLogger(SmtpConfigService.class)
          .warn(
              "[smtp-seed] failed: {} :: host={}",
              e.getMessage(),
              env.getProperty("PORTAL_SMTP_HOST"));
    }
  }

  private Map<String, Object> group() {
    Map<String, Object> all = settingsService.get(MODULE_KEY);
    Object group = all.get(GROUP);
    return group instanceof Map<?, ?> map ? castToStringKeyed(map) : new LinkedHashMap<>();
  }

  private static Map<String, Object> castToStringKeyed(Map<?, ?> map) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : map.entrySet()) {
      out.put(String.valueOf(e.getKey()), e.getValue());
    }
    return out;
  }

  private static String stringOf(Object value) {
    return value instanceof String s && !s.isBlank() ? s : null;
  }

  private static Integer intOrNull(Object value) {
    if (value instanceof Number n) {
      return n.intValue();
    }
    if (value instanceof String s && s.matches("\\d+")) {
      return Integer.parseInt(s);
    }
    return null;
  }

  private static Instant instantOrNull(Object value) {
    if (value instanceof String s) {
      try {
        return Instant.parse(s);
      } catch (Exception e) {
        return null;
      }
    }
    return null;
  }
}
