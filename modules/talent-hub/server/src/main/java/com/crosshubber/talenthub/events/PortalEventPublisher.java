package com.crosshubber.talenthub.events;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.crosshubber.talenthub.config.TalentHubProperties;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Publishes module events to the portal's msgcenter HTTP ingest ({@code POST
 * /api/msgcenter/publish}, HMAC-authenticated like sample-sender). Strict-v3 envelopes come from
 * {@link EventEnvelopeFactory}; the portal enforces a 30 publishes/min rate bucket per caller key.
 * Fail-soft: any failure (401/422/429/503/transport) is logged and swallowed — publishing must
 * never fail the business operation. Must be called OUTSIDE any DB transaction (AGENTS invariant).
 */
@Service
public class PortalEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(PortalEventPublisher.class);
  private static final String ALGORITHM = "HmacSHA256";
  private static final String CALLER_KEY = EventEnvelopeFactory.MODULE_KEY;

  private final TalentHubProperties props;
  private final EventEnvelopeFactory envelopeFactory;
  private final RestClient.Builder restClientBuilder;
  private final ObjectMapper objectMapper;

  public PortalEventPublisher(
      TalentHubProperties props,
      EventEnvelopeFactory envelopeFactory,
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper) {
    this.props = props;
    this.envelopeFactory = envelopeFactory;
    this.restClientBuilder = restClientBuilder;
    this.objectMapper = objectMapper;
  }

  /**
   * The publish secret contract (portal {@code PublishAuthenticator.expectedSecret}): HMAC-SHA256
   * over the caller key, keyed with the session secret, base64url without padding.
   */
  public static String publishSecret(String sessionSecret, String callerKey) {
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

  /**
   * Publishes a message envelope; never throws.
   *
   * @return the stream sequence reported by the portal, or null when publishing failed
   */
  public Long publishMessage(
      String event,
      String title,
      String body,
      String severity,
      List<String> roles,
      String linkPath) {
    try {
      ObjectNode envelope = envelopeFactory.message(event, title, body, severity, roles, linkPath);
      envelopeFactory.assertKnownKeys(envelope);
      String secret = publishSecret(props.getPortal().getSessionSecret(), CALLER_KEY);
      String response =
          restClientBuilder
              .build()
              .post()
              .uri(props.getPortal().getUrl() + "/api/msgcenter/publish")
              .header("X-MsgCenter-Key", CALLER_KEY)
              .header("X-MsgCenter-Secret", secret)
              .contentType(MediaType.APPLICATION_JSON)
              .body(envelope.toString())
              .retrieve()
              .body(String.class);
      if (response == null) {
        log.warn("[events] publish of {} returned no body", event);
        return null;
      }
      var node = objectMapper.readTree(response);
      Long seq = node.path("streamSeq").isNumber() ? node.path("streamSeq").asLong() : null;
      log.info("[events] published {} (streamSeq {})", event, seq);
      return seq;
    } catch (org.springframework.web.client.HttpStatusCodeException e) {
      // 401/403 (credentials), 422 (envelope), 429 (rate bucket), 5xx (transport) — all fail-soft.
      log.warn(
          "[events] publish of {} rejected with {}: {}",
          event,
          e.getStatusCode().value(),
          e.getMessage());
      return null;
    } catch (Exception e) {
      log.warn("[events] publish of {} failed soft: {}", event, e.getMessage());
      return null;
    }
  }
}
