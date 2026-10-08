package com.crosshubber.portal.modules.msgcenter.ingest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.crosshubber.portal.common.events.EnvelopeValidationException;
import com.crosshubber.portal.common.events.EnvelopeValidator;
import com.crosshubber.portal.common.events.EventPublisher;
import com.crosshubber.portal.config.NatsConnectionConfig;
import com.crosshubber.portal.config.NatsConnectionConfig.NatsSupport;
import com.crosshubber.portal.modules.registry.modules.ModulesService;

import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.Subscription;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import tools.jackson.databind.ObjectMapper;

/**
 * Durable pull-consumer loop over {@code PORTAL_MESSAGES} (plan §6 ingest slice).
 *
 * <p>Runs on a virtual thread after boot. Per message: validate → transactional store (event_id
 * dedupe) → ack after commit; validation failures land in the DLQ immediately; transient failures
 * redeliver up to the consumer's {@code max-deliver}, where they terminate in the DLQ — no
 * poison-pill.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class MsgCenterIngestionHandler implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(MsgCenterIngestionHandler.class);

  static final String DURABLE = "portal-msgcenter";
  private static final int BATCH = 10;
  private static final Duration FETCH_WAIT = Duration.ofSeconds(5);
  private static final long MAX_DELIVER = 5;

  private final NatsSupport nats;
  private final EnvelopeValidator envelopeValidator;
  private final MsgCenterIngestionService ingestionService;
  private final ModulesService modulesService;
  private final EventPublisher eventPublisher;
  private final com.crosshubber.portal.modules.msgcenter.email.MsgCenterEmailMirror emailMirror;
  private final ObjectMapper mapper;
  private final AtomicReference<Subscription> subscriptionRef = new AtomicReference<>();
  private volatile boolean running = true;

  public MsgCenterIngestionHandler(
      NatsSupport nats,
      EnvelopeValidator envelopeValidator,
      MsgCenterIngestionService ingestionService,
      ModulesService modulesService,
      EventPublisher eventPublisher,
      com.crosshubber.portal.modules.msgcenter.email.MsgCenterEmailMirror emailMirror,
      ObjectMapper mapper) {
    this.nats = nats;
    this.envelopeValidator = envelopeValidator;
    this.ingestionService = ingestionService;
    this.modulesService = modulesService;
    this.eventPublisher = eventPublisher;
    this.emailMirror = emailMirror;
    this.mapper = mapper;
  }

  @Override
  public void run(ApplicationArguments args) {
    // Post-boot fail-fast ordering: the reconciler runs first (default order); this loop then
    // starts with the stream + durable consumer ensured. Never blocks portal startup — idle
    // waiting happens on the consumer thread.
    if (!nats.enabled() || nats.jetStream() == null) {
      log.info("[msgcenter] NATS disabled — ingestion loop not started");
      return;
    }
    ThreadFactory factory = Executors.defaultThreadFactory();
    Thread thread = factory.newThread(this::loop);
    thread.setName("msgcenter-ingest");
    thread.start();
    log.info("[msgcenter] ingestion loop started (durable={}, batch={})", DURABLE, BATCH);
  }

  private void loop() {
    while (running) {
      try {
        Subscription subscription = subscriptionRef.get();
        if (subscription == null) {
          subscription = createSubscription();
          subscriptionRef.set(subscription);
        }
        List<Message> batch =
            ((io.nats.client.JetStreamSubscription) subscription).fetch(BATCH, FETCH_WAIT);
        for (Message message : batch) {
          handle(message);
        }
      } catch (Exception e) {
        log.warn("[msgcenter] ingest loop error (retrying): {}", e.getMessage());
        subscriptionRef.set(null); // recreate — connection/consumer may be gone
        sleep(Duration.ofSeconds(2));
      }
    }
  }

  private io.nats.client.JetStreamSubscription createSubscription()
      throws java.io.IOException, io.nats.client.JetStreamApiException {
    JetStream jetStream = nats.jetStream();
    JetStreamManagement management = nats.management();
    if (management != null) {
      NatsConnectionConfig.ensureStream(management);
      management.addOrUpdateConsumer(
          NatsConnectionConfig.STREAM,
          ConsumerConfiguration.builder()
              .durable(DURABLE)
              .filterSubjects(
                  NatsConnectionConfig.SUBJECT_MSG_PREFIX + ">",
                  NatsConnectionConfig.SUBJECT_TASK_PREFIX + ">")
              .ackPolicy(AckPolicy.Explicit)
              .deliverPolicy(DeliverPolicy.All)
              .maxDeliver(MAX_DELIVER)
              .build());
    }
    PullSubscribeOptions options =
        PullSubscribeOptions.builder().stream(NatsConnectionConfig.STREAM)
            .durable(DURABLE)
            .bind(true)
            .build();
    io.nats.client.JetStreamSubscription subscription = jetStream.subscribe(null, options);
    log.info("[msgcenter] durable consumer {} bound", DURABLE);
    return subscription;
  }

  private void handle(Message message) {
    String subject = message.getSubject();
    try {
      var envelope =
          mapper.readTree(new String(message.getData(), java.nio.charset.StandardCharsets.UTF_8));
      envelopeValidator.validate(envelope);
      String moduleKey =
          envelope.get("moduleKey") != null ? envelope.get("moduleKey").asString() : null;
      if (!modulesService.exists(moduleKey)) {
        toDlq(message, "unknown moduleKey '" + moduleKey + "'");
        message.ack();
        return;
      }
      var stored = ingestionService.store(envelope, subject, message.metaData().streamSequence());
      message.ack(); // after commit — the store method's return is the commit boundary
      if (stored != null) {
        emailMirror.onArrival(stored);
      }
    } catch (EnvelopeValidationException e) {
      toDlq(message, e.getMessage());
      message.ack();
    } catch (Exception e) {
      long attempts = message.metaData() != null ? message.metaData().deliveredCount() : 0;
      if (attempts >= MAX_DELIVER) {
        toDlq(message, "final failure after " + attempts + " deliveries: " + e.getMessage());
        message.ack();
      } else {
        log.debug(
            "[msgcenter] transient ingest failure on {} (attempt {}): {}",
            subject,
            attempts,
            e.getMessage());
        message.nak();
      }
    }
  }

  /**
   * Publishes a copy + reason to {@code portal.dlq.msgcenter}; inbound message is acked by caller.
   */
  private void toDlq(Message message, String reason) {
    long streamSeq = message.metaData() != null ? message.metaData().streamSequence() : -1;
    log.warn("[msgcenter] DLQ: {} (stream seq {}) — {}", message.getSubject(), streamSeq, reason);
    String dlq =
        "{\"v\": 1, \"kind\": \"ingest-failure\", \"originalSubject\": \""
            + message.getSubject()
            + "\", \"streamSeq\": "
            + streamSeq
            + ", \"reason\": "
            + mapper.writeValueAsString(reason)
            + ", \"payload\": "
            + new String(message.getData(), java.nio.charset.StandardCharsets.UTF_8)
            + "}";
    if (eventPublisher.isEnabled()) {
      eventPublisher.publish(NatsConnectionConfig.SUBJECT_DLQ, dlq);
    }
  }

  private void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
