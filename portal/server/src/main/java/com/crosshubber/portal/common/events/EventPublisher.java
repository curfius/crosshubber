package com.crosshubber.portal.common.events;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.crosshubber.portal.config.NatsConnectionConfig;

import io.nats.client.JetStream;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;

/**
 * Typed publish helper over the shared NATS connection (plan §6).
 *
 * <p>Publishes through JetStream so events are durable on {@code PORTAL_MESSAGES}. No-op + warn
 * when the feature is disabled (CI-safe / fail-soft). Must be called OUTSIDE transactions —
 * after-commit discipline belongs to the callers.
 */
@Component
public class EventPublisher {

  private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
  private static final long PUBLISH_TIMEOUT_SECONDS = 5;

  private final NatsConnectionConfig.NatsSupport nats;

  public EventPublisher(NatsConnectionConfig.NatsSupport nats) {
    this.nats = nats;
  }

  /** Whether the transport is live (configured AND connected). */
  public boolean isEnabled() {
    return nats.enabled() && nats.jetStream() != null;
  }

  /**
   * Publishes a JSON payload on a portal subject ({@code portal.msg.*} / {@code portal.task.*}).
   *
   * @throws EnvelopeValidationException when the subject is not a portal subject or NATS is on but
   *     the publish fails — callers decide between 422 and DLQ handling.
   */
  public PublishResult publish(String subject, String jsonPayload) {
    if (!isPortalSubject(subject)) {
      throw new EnvelopeValidationException(
          "subject must start with portal.msg. or portal.task.: " + subject);
    }
    if (!isEnabled()) {
      log.warn(
          "[events] NATS disabled — dropping publish to {} ({} bytes)",
          subject,
          jsonPayload.length());
      return PublishResult.DROPPED;
    }
    try {
      NatsConnectionConfig.ensureStream(nats.management());
      JetStream js = nats.jetStream();
      PublishAck ack =
          js.publish(
              subject,
              jsonPayload.getBytes(StandardCharsets.UTF_8),
              PublishOptions.builder()
                  .streamTimeout(Duration.ofSeconds(PUBLISH_TIMEOUT_SECONDS))
                  .build());
      log.debug("[events] published {} (seq {})", subject, ack.getSeqno());
      return new PublishResult(true, ack.getSeqno());
    } catch (EnvelopeValidationException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new EnvelopeValidationException(
          "nats publish failed for " + subject + ": " + e.getMessage());
    }
  }

  static boolean isPortalSubject(String subject) {
    return subject != null
        && (subject.startsWith(NatsConnectionConfig.SUBJECT_MSG_PREFIX)
            || subject.startsWith(NatsConnectionConfig.SUBJECT_TASK_PREFIX)
            || subject.startsWith(NatsConnectionConfig.SUBJECT_TASK_RESPONSE_PREFIX)
            || subject.startsWith(NatsConnectionConfig.SUBJECT_DLQ_PREFIX));
  }

  /** Publish outcome: JetStream sequence when delivered; {@code dropped} when disabled. */
  public record PublishResult(boolean published, long streamSeq) {

    static final PublishResult DROPPED = new PublishResult(false, -1);
  }
}
