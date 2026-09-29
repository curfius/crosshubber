package com.crosshubber.portal.modules.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.security.PortalUser;

/**
 * Issues short-lived signed tokens that authenticate portal-originated agent calls to remote module
 * backends (AI_MODULES_PLAN P5).
 *
 * <p>The portal authenticates users via session cookies — there is no bearer token to forward.
 * Instead, before calling a module's agent endpoint the portal mints an HMAC-SHA256-signed token
 * ({@code base64url(payloadJson) + "." + base64url(hmac)}) carrying the caller's identity ({@code
 * sub}, {@code name}, {@code roles}) and a 5-minute expiry, keyed with the portal session secret.
 * Module backends validate the signature and expiry, then enforce role checks locally. The key is
 * shared with modules out-of-band (compose env), never in code or manifests.
 */
@Service
public class AgentCallAuthorizer {

  private static final long TTL_SECONDS = 300;
  private static final String ALGORITHM = "HmacSHA256";

  /** Agent-call token payload. */
  public record AgentCallClaims(
      String iss, String sub, String name, List<String> roles, long exp) {}

  private final PortalProperties props;
  private final JsonUtils jsonUtils;

  public AgentCallAuthorizer(PortalProperties props, JsonUtils jsonUtils) {
    this.props = props;
    this.jsonUtils = jsonUtils;
  }

  /** Issues a signed agent-call token for the given user. */
  public String issue(PortalUser user) {
    AgentCallClaims claims =
        new AgentCallClaims(
            "portal",
            user.sub(),
            user.name(),
            user.roles() == null ? List.of() : user.roles(),
            Instant.now().getEpochSecond() + TTL_SECONDS);
    String payload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(jsonUtils.write(claims).getBytes(StandardCharsets.UTF_8));
    return payload + "." + sign(payload);
  }

  /**
   * Validates a token signature and expiry (module-side reference implementation lives in the
   * modules; the portal uses this for tests and future self-calls).
   *
   * @return the claims, or null when the token is invalid/expired
   */
  public AgentCallClaims verify(String token) {
    if (token == null) {
      return null;
    }
    int sep = token.lastIndexOf('.');
    if (sep <= 0) {
      return null;
    }
    String payload = token.substring(0, sep);
    String signature = token.substring(sep + 1);
    if (!MessageDigest.isEqual(
        signature.getBytes(StandardCharsets.UTF_8),
        sign(payload).getBytes(StandardCharsets.UTF_8))) {
      return null;
    }
    try {
      String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
      var node = jsonUtils.parseTreeOrNull(json);
      if (node == null) {
        return null;
      }
      List<String> roles = new ArrayList<>();
      if (node.path("roles").isArray()) {
        node.path("roles").forEach(r -> roles.add(r.asString()));
      }
      AgentCallClaims claims =
          new AgentCallClaims(
              node.path("iss").asString(null),
              node.path("sub").asString(null),
              node.path("name").asString(null),
              List.copyOf(roles),
              node.path("exp").asLong(0));
      if (!"portal".equals(claims.iss())
          || claims.exp() < Instant.now().getEpochSecond()
          || claims.sub() == null) {
        return null;
      }
      return claims;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private String sign(String payload) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      byte[] key = props.getSessionSecret().getBytes(StandardCharsets.UTF_8);
      mac.init(new SecretKeySpec(key, ALGORITHM));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("agent call token signing failed", e);
    }
  }
}
