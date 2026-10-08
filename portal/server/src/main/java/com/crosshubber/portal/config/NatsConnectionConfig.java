package com.crosshubber.portal.config;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;

/**
 * Shared NATS support for the message center: one connection + JetStream contexts, created eagerly
 * at boot.
 *
 * <p>Fail-soft rules: when {@code portal.nats-url} is blank the feature is off (mirrors the
 * kc-admin pattern); when the URL is set but the broker is unreachable at boot the portal still
 * starts with the feature disabled until restart. JNats I/O is blocking — it must never run inside
 * {@code @Transactional} (Hikari pool = 5 rule).
 */
@Configuration
public class NatsConnectionConfig {

  private static final Logger log = LoggerFactory.getLogger(NatsConnectionConfig.class);

  /** Portal stream: notifications/messages + tasks, bounded retention (message center). */
  public static final String STREAM = "PORTAL_MESSAGES";

  public static final String SUBJECT_MSG_PREFIX = "portal.msg.";
  public static final String SUBJECT_TASK_PREFIX = "portal.task.";

  /**
   * Task-response subjects live OUTSIDE portal.task.> so the durable msgcenter consumer (filtered
   * portal.msg.> + portal.task.>) never ingests its own completion events.
   */
  public static final String SUBJECT_TASK_RESPONSE_PREFIX = "portal.taskresponse.";

  public static final String SUBJECT_DLQ_PREFIX = "portal.dlq.";
  public static final String SUBJECT_DLQ = SUBJECT_DLQ_PREFIX + "msgcenter";

  /**
   * Non-null always: {@code enabled=false} when NATS is unconfigured or unreachable. The nested
   * handles are null in that case; every consumer must check {@link #enabled()} first.
   */
  public static final class NatsSupport implements AutoCloseable {

    private final boolean enabled;
    private final Connection connection;
    private final JetStream jetStream;
    private final JetStreamManagement management;

    /** Public for tests: build a disabled support directly. */
    public NatsSupport(
        boolean enabled,
        Connection connection,
        JetStream jetStream,
        JetStreamManagement management) {
      this.enabled = enabled;
      this.connection = connection;
      this.jetStream = jetStream;
      this.management = management;
    }

    public boolean enabled() {
      return enabled;
    }

    public Connection connection() {
      return connection;
    }

    public JetStream jetStream() {
      return jetStream;
    }

    public JetStreamManagement management() {
      return management;
    }

    @Override
    public void close() {
      if (connection != null) {
        try {
          connection.close();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  @Bean(destroyMethod = "close")
  NatsSupport natsSupport(PortalProperties properties) {
    String url = properties.getNatsUrl();
    if (url == null || url.isBlank()) {
      log.info("[nats] portal.nats-url blank — message center feature disabled");
      return new NatsSupport(false, null, null, null);
    }
    try {
      Options options =
          new Options.Builder()
              .server(url)
              .connectionTimeout(Duration.ofSeconds(10))
              .reconnectWait(Duration.ofSeconds(2))
              .maxReconnects(-1)
              .build();
      Connection connection = Nats.connect(options);
      JetStream js = connection.jetStream();
      JetStreamManagement jsm = connection.jetStreamManagement();
      log.info("[nats] connected to {} — stream {} ready", url, STREAM);
      return new NatsSupport(true, connection, js, jsm);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.warn(
          "[nats] connect to {} failed ({}), feature disabled until restart", url, e.getMessage());
      return new NatsSupport(false, null, null, null);
    }
  }

  /**
   * Ensures the stream exists AND its subject list matches the contract (idempotent — safe on every
   * boot; patches subject drift on pre-existing streams). Best-effort: failures are logged and
   * retried on the next call.
   */
  public static boolean ensureStream(JetStreamManagement management) {
    if (management == null) {
      return false;
    }
    List<String> desired =
        List.of(
            SUBJECT_MSG_PREFIX + ">",
            SUBJECT_TASK_PREFIX + ">",
            SUBJECT_TASK_RESPONSE_PREFIX + ">",
            "portal.dlq.>");
    try {
      StreamInfo info = management.getStreamInfo(STREAM);
      if (info != null
          && new java.util.HashSet<>(info.getConfiguration().getSubjects()).containsAll(desired)) {
        return true;
      }
      StreamConfiguration config =
          StreamConfiguration.builder()
              .name(STREAM)
              .subjects(desired)
              .storageType(StorageType.File)
              .retentionPolicy(RetentionPolicy.Limits)
              .maxAge(Duration.ofDays(8))
              .maximumMessageSize(1024 * 1024)
              .build();
      if (info != null) {
        management.updateStream(config);
        log.info("[nats] stream {} subjects updated", STREAM);
      } else {
        management.addStream(config);
        log.info("[nats] stream {} created", STREAM);
      }
      return true;
    } catch (Exception e) {
      log.warn("[nats] stream {} ensure failed: {}", STREAM, e.getMessage());
      return false;
    }
  }
}
