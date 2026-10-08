package com.crosshubber.portal.modules.msgcenter.publish;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

/**
 * Third-party HTTP publish endpoint (plan §6): secret-header auth (HMAC over the caller key), rate
 * limited, envelope-validated, moduleKey-checked. Builtin modules skip this path entirely and
 * publish via the shared {@code EventPublisher} bean.
 */
@RestController
public class MsgCenterPublishController {

  private final PublishAuthenticator authenticator;
  private final MsgCenterPublishService publishService;

  public MsgCenterPublishController(
      PublishAuthenticator authenticator, MsgCenterPublishService publishService) {
    this.authenticator = authenticator;
    this.publishService = publishService;
  }

  @PostMapping("/api/msgcenter/publish")
  public Map<String, Object> publish(
      @RequestHeader(value = "X-MsgCenter-Key", required = false) String callerKey,
      @RequestHeader(value = "X-MsgCenter-Secret", required = false) String callerSecret,
      @RequestBody JsonNode envelope) {
    String verifiedKey = authenticator.authenticate(callerKey, callerSecret);
    // envelope moduleKey must match the verified caller identity (prevents spoofed senders)
    String moduleKey = MsgCenterPublishService.moduleKeyOf(envelope);
    if (moduleKey == null || !moduleKey.equals(verifiedKey)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN, "moduleKey must match the caller key");
    }
    long seq = publishService.publish(verifiedKey, envelope);
    return Map.of("published", true, "streamSeq", seq);
  }
}
