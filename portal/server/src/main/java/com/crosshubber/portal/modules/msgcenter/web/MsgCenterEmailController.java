package com.crosshubber.portal.modules.msgcenter.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.common.SecurityUtils;
import com.crosshubber.portal.modules.msgcenter.email.MsgCenterEmailSender;
import com.crosshubber.portal.modules.msgcenter.email.SmtpConfigService;
import com.crosshubber.portal.modules.msgcenter.email.SmtpConfigService.SmtpConfigView;
import com.crosshubber.portal.modules.usersettings.scopes.UserSettingsService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Email-channel endpoints (Phase 7):
 *
 * <ul>
 *   <li>admin SMTP settings card: masked GET / encrypting PUT / send-test (portal-msgcenter-edit)
 *   <li>self-service: email address + global fallback switch (any authenticated user)
 * </ul>
 *
 * Group email flags live on the membership endpoints (MsgCenterGroupController).
 */
@RestController
public class MsgCenterEmailController {

  private final SmtpConfigService smtpConfigService;
  private final MsgCenterEmailSender sender;
  private final UserSettingsService userSettingsService;
  private final ObjectMapper mapper;

  public MsgCenterEmailController(
      SmtpConfigService smtpConfigService,
      MsgCenterEmailSender sender,
      UserSettingsService userSettingsService,
      ObjectMapper mapper) {
    this.smtpConfigService = smtpConfigService;
    this.sender = sender;
    this.userSettingsService = userSettingsService;
    this.mapper = mapper;
  }

  /** Masked SMTP settings view — the secret never leaves the server. */
  @GetMapping("/api/msgcenter/admin/settings/email")
  @PreAuthorize("hasRole('portal-msgcenter-edit')")
  public Map<String, Object> view() {
    return Map.of("email", smtpConfigService.view());
  }

  /** Save SMTP settings; password=null keeps the stored one. */
  @PutMapping("/api/msgcenter/admin/settings/email")
  @PreAuthorize("hasRole('portal-msgcenter-edit')")
  public Map<String, Object> update(@RequestBody JsonNode body) {
    String host = stringOf(body, "host");
    if (host != null && !host.matches("^[A-Za-z0-9.\\-]{1,253}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid SMTP host");
    }
    Integer port = body.has("port") && body.get("port").isInt() ? body.get("port").asInt() : null;
    if (port != null && (port < 1 || port > 65535)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "port must be 1..65535");
    }
    String from = stringOf(body, "from");
    if (from != null && !from.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid from address");
    }
    SmtpConfigView updated =
        smtpConfigService.update(
            host,
            port,
            from,
            stringOf(body, "tls"),
            stringOf(body, "authUser"),
            stringOf(body, "password"),
            SecurityUtils.currentUserSub());
    return Map.of("email", updated);
  }

  /** Send-test probe — bypasses the daily cap; targets the given address (default: from). */
  @PostMapping("/api/msgcenter/admin/settings/email/test")
  @PreAuthorize("hasRole('portal-msgcenter-edit')")
  public Map<String, Object> sendTest(@RequestBody(required = false) JsonNode body) {
    var config = smtpConfigService.live();
    if (config == null || !config.enabled()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "email channel not configured");
    }
    String to = body != null && stringOf(body, "to") != null ? stringOf(body, "to") : config.from();
    if (to == null || !to.contains("@")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a target address is required");
    }
    boolean sent = sender.sendTest(config, to);
    if (!sent) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "SMTP send failed (see portal logs)");
    }
    return Map.of("sent", true, "to", to);
  }

  /** My mirror preferences: self-supplied address + global fallback switch. */
  @GetMapping("/api/msgcenter/my-email")
  public Map<String, Object> myEmail() {
    Map<String, Object> prefs =
        userSettingsService.get(SecurityUtils.currentUserSub(), "msgcenter");
    return Map.of(
        "email",
        prefs.getOrDefault("email", "") == null ? "" : String.valueOf(prefs.get("email")),
        "emailFallback",
        Boolean.TRUE.equals(prefs.get("emailFallback")));
  }

  /** Set my mirror prefs (address may be cleared with an empty string). */
  @PutMapping("/api/msgcenter/my-email")
  public Map<String, Object> updateMyEmail(@RequestBody JsonNode body) {
    String address = stringOf(body, "email");
    if (address != null
        && !address.isBlank()
        && !address.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid email address");
    }
    boolean fallback = body.get("emailFallback") != null && body.get("emailFallback").asBoolean();
    Map<String, Object> partial = new java.util.LinkedHashMap<>();
    if (address != null) {
      partial.put("email", address.isBlank() ? null : address.trim());
    }
    partial.put("emailFallback", fallback);
    var merged = userSettingsService.update(SecurityUtils.currentUserSub(), "msgcenter", partial);
    return Map.of(
        "email", String.valueOf(merged.get("email") == null ? "" : merged.get("email")),
        "emailFallback", Boolean.TRUE.equals(merged.get("emailFallback")));
  }

  private static String stringOf(JsonNode body, String key) {
    JsonNode node = body == null ? null : body.get(key);
    return node != null && node.isString() ? node.asString() : null;
  }
}
