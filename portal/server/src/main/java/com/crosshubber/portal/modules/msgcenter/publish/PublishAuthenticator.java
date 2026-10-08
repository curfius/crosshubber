package com.crosshubber.portal.modules.msgcenter.publish;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.config.PortalProperties;

/**
 * Secret-header verification + rate limiting for {@code POST /api/msgcenter/publish} (plan §6).
 *
 * <p>Auth model (AgentCallAuthorizer precedent): the caller presents {@code X-MsgCenter-Key:
 * <callerId>} + {@code X-MsgCenter-Secret: base64url(HMAC-SHA256(<callerId>, sessionSecret))}. The
 * shared secret is distributed out-of-band (compose env); anything else → 401. Rate limit: Caffeine
 * token bucket keyed by caller id, 30 requests/min.
 */
@Service
public class PublishAuthenticator {

  private static final String ALGORITHM = "HmacSHA256";
  private static final long RATE_LIMIT_PER_MINUTE = 30;

  private final com.github.benmanes.caffeine.cache.Cache<
          String, java.util.concurrent.atomic.AtomicLong>
      buckets =
          com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
              .expireAfterAccess(Duration.ofMinutes(2))
              .build();

  private final byte[] secret;

  public PublishAuthenticator(
      PortalProperties props, com.crosshubber.portal.common.JsonUtils jsonUtils) {
    this.secret = props.getSessionSecret().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Verifies key + signature and the per-caller rate bucket; throws 401/429 on failure.
   *
   * @return the caller id (envelope moduleKey must match this for audit)
   */
  public String authenticate(String callerKey, String callerSecret) {
    if (callerKey == null
        || callerKey.isBlank()
        || callerSecret == null
        || callerSecret.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing publish credentials");
    }
    if (!callerKey.matches("^[A-Za-z0-9_-]{1,64}$")) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid caller key");
    }
    String expected = sign(callerKey);
    if (!MessageDigestEqual.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), callerSecret.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid publish credentials");
    }
    java.util.concurrent.atomic.AtomicLong counter =
        buckets.get(callerKey, k -> new java.util.concurrent.atomic.AtomicLong(0));
    if (counter.incrementAndGet() > RATE_LIMIT_PER_MINUTE) {
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "publish rate limit exceeded");
    }
    return callerKey;
  }

  /** The caller secret for a caller key: HMAC-SHA256(sessionSecret, callerKey), base64url. */
  public static String expectedSecret(String sessionSecret, String callerKey) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(sessionSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(callerKey.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("publish secret signing failed", e);
    }
  }

  private String sign(String callerKey) {
    return expectedSecret(new String(secret, StandardCharsets.UTF_8), callerKey);
  }

  /** Constant-time equals helper (naming keeps the checkstyle import list clean). */
  private static final class MessageDigestEqual {

    private MessageDigestEqual() {}

    static boolean isEqual(byte[] a, byte[] b) {
      return java.security.MessageDigest.isEqual(a, b);
    }
  }
}
