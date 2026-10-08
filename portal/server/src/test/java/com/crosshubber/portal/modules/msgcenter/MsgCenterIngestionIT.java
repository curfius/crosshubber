package com.crosshubber.portal.modules.msgcenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.crosshubber.portal.common.events.EventPublisher;
import com.crosshubber.portal.modules.msgcenter.domain.McMessageRepository;
import com.crosshubber.portal.modules.msgcenter.ingest.MsgCenterIngestionService;
import com.crosshubber.portal.modules.msgcenter.publish.PublishAuthenticator;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.Nats;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.ConsumerConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-to-end ingestion over Testcontainers PostgreSQL + NATS (MSG_CENTER plan test section): valid
 * envelopes arrive exactly once (event_id dedupe), duplicates no-op, malformed envelopes land in
 * {@code portal.dlq.msgcenter}, and the unauthenticated API contract stays 401-JSON.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.driver-class-name=org.postgresql.Driver",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.properties.hibernate.default_schema=test",
      "spring.flyway.enabled=true",
      "spring.flyway.schemas=test",
      "portal.tenant-config-dir=target/test-classes/tenant-config"
    })
@ActiveProfiles("test")
@Testcontainers
class MsgCenterIngestionIT {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  @Container
  static final GenericContainer<?> NATS =
      new GenericContainer<>(DockerImageName.parse("nats:2.12-alpine"))
          .withCommand("-js")
          .withExposedPorts(4222);

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    String url = POSTGRES.getJdbcUrl();
    String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=test";
    registry.add("spring.datasource.url", () -> schemaUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add(
        "portal.nats-url", () -> "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
  }

  @LocalServerPort int port;

  @Autowired EventPublisher eventPublisher;

  @Autowired McMessageRepository messageRepo;

  @Autowired MsgCenterIngestionService ingestionService;

  @Autowired ObjectMapper mapper;

  private RestClient http;

  @Autowired
  void initClient(RestClient.Builder builder) {
    this.http = builder.baseUrl("http://localhost:" + port).build();
  }

  private String notificationEnvelope(String id, String subject) {
    ObjectNode root = mapper.createObjectNode();
    root.put("v", 1);
    root.put("type", "notification");
    root.put("moduleKey", "portal-dashboard");
    root.put("subject", subject);
    root.put("id", id);
    root.put("createdAt", "2026-10-06T10:15:00Z");
    root.putObject("audience").put("allUsers", true);
    root.putObject("title").put("en", "IT notification");
    root.putObject("body").put("en", "Integration test message");
    return root.toString();
  }

  private String natsUrl() {
    return "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
  }

  @Test
  void publishedEventArrivesExactlyOnce() {
    String id = UUID.randomUUID().toString();
    String subject =
        "portal.msg.portal-dashboard.it-once-" + UUID.randomUUID().toString().substring(0, 8);
    eventPublisher.publish(subject, notificationEnvelope(id, subject));

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(messageRepo.findByEventId(id)).isPresent());

    int rowsAfterFirst = messageRepo.findAll().size();
    eventPublisher.publish(subject, notificationEnvelope(id, subject)); // duplicate event_id
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(messageRepo.findAll().size()).isEqualTo(rowsAfterFirst));
  }

  @Test
  void storeIsIdempotentDirectly() {
    String id = UUID.randomUUID().toString();
    JsonNode envelope = mapper.readTree(notificationEnvelope(id, "direct"));
    assertThat(ingestionService.store(envelope, "portal.msg.portal-dashboard.direct", 1))
        .isNotNull();
    assertThat(ingestionService.store(envelope, "portal.msg.portal-dashboard.direct", 1)).isNull();
  }

  @Test
  void malformedEnvelopeLandsInDlq() {
    String dlqMarker;
    String subject;
    try (Connection probe = Nats.connect(natsUrl())) {
      JetStream js = probe.jetStream();
      subject =
          "portal.msg.portal-dashboard.it-bad-" + UUID.randomUUID().toString().substring(0, 8);
      // Deliberately malformed (v missing) → immediate DLQ, no poison-pill redeliveries
      js.publish(
          subject, "{\"moduleKey\": \"portal-dashboard\", \"type\": \"notification\"}".getBytes());

      ConsumerConfiguration conf =
          ConsumerConfiguration.builder()
              .durable("it-dlq-" + UUID.randomUUID().toString().substring(0, 8))
              .filterSubjects("portal.dlq.msgcenter")
              .build();
      var sub =
          js.subscribe(
              null,
              PullSubscribeOptions.builder().stream("PORTAL_MESSAGES").configuration(conf).build());
      List<String> matched = new ArrayList<>();
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> {
                var messages = sub.fetch(10, Duration.ofMillis(500));
                messages.forEach(
                    m -> {
                      matched.add(new String(m.getData(), java.nio.charset.StandardCharsets.UTF_8));
                      m.ack();
                    });
                assertThat(matched).isNotEmpty();
              });
      dlqMarker =
          matched.stream()
              .filter(body -> body.contains("originalSubject") && body.contains(subject))
              .findFirst()
              .orElse("");
    } catch (Exception e) {
      throw new AssertionError("NATS probe failed: " + e.getMessage(), e);
    }
    assertThat(dlqMarker).contains("\"kind\": \"ingest-failure\"").contains("invalid envelope");
  }

  @Test
  void unauthenticatedMessagesListIs401Json() {
    ResponseEntity<String> response =
        http.get()
            .uri("/api/msgcenter/messages")
            .retrieve()
            .onStatus(s -> true, (r, res) -> {})
            .toEntity(String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).contains("\"error\":\"unauthorized\"");
  }

  @Autowired PublishAuthenticator publishAuthenticator;

  @Test
  void httpPublishRejectsMissingSecretAndSpoofedModuleKey() {
    // valid credentials, spoofed sender → 403 (moduleKey != caller key)
    String callerKey = "sample-sender";
    String secret = hmacOf(callerKey);
    ResponseEntity<String> spoofed =
        http.post()
            .uri("/api/msgcenter/publish")
            .header("X-MsgCenter-Key", callerKey)
            .header("X-MsgCenter-Secret", secret)
            .header("Content-Type", "application/json")
            .body(validPublishEnvelope("portal-dashboard"))
            .retrieve()
            .onStatus(s -> true, (r, res) -> {})
            .toEntity(String.class);
    assertThat(spoofed.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    // missing credentials → 401
    ResponseEntity<String> anonymous =
        http.post()
            .uri("/api/msgcenter/publish")
            .header("Content-Type", "application/json")
            .body(validPublishEnvelope("sample-sender"))
            .retrieve()
            .onStatus(s -> true, (r, res) -> {})
            .toEntity(String.class);
    assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void httpPublishAcceptsMatchingCallerAndArrives() {
    // the caller key doubles as the envelope moduleKey and must exist in registry_modules —
    // the test tenant seeds builtin modules only, so the caller is a builtin module here
    String callerKey = "portal-dashboard";
    // the HMAC is computed from the test session secret exactly as the authenticator does
    String computed = hmacOf(callerKey);
    String marker = "http-publish-" + UUID.randomUUID().toString().substring(0, 8);
    String body = validPublishEnvelope(callerKey, marker);
    ResponseEntity<String> ok =
        http.post()
            .uri("/api/msgcenter/publish")
            .header("X-MsgCenter-Key", callerKey)
            .header("X-MsgCenter-Secret", computed)
            .header("Content-Type", "application/json")
            .body(body)
            .retrieve()
            .onStatus(s -> true, (r, res) -> {})
            .toEntity(String.class);
    assertThat(ok.getStatusCode().value()).isEqualTo(200);
    assertThat(ok.getBody()).contains("\"published\":true");

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(
                        messageRepo.findAll().stream()
                            .anyMatch(
                                m ->
                                    m.getTitleJson() != null
                                        && String.valueOf(m.getTitleJson().get("en"))
                                            .equals(marker)))
                    .isTrue());
  }

  /** The publish secret is HMAC(sessionSecret, callerKey) — computed for the IT's test tenant. */
  @Autowired org.springframework.core.env.Environment env;

  private String hmacOf(String callerKey) {
    String sessionSecret = env.getProperty("portal.session-secret");
    return PublishAuthenticator.expectedSecret(sessionSecret, callerKey);
  }

  private String validPublishEnvelope(String moduleKey) {
    return validPublishEnvelope(moduleKey, "HTTP publish");
  }

  private String validPublishEnvelope(String moduleKey, String titleText) {
    ObjectNode envelope = mapper.createObjectNode();
    envelope.put("v", 1);
    envelope.put("type", "notification");
    envelope.put("moduleKey", moduleKey);
    envelope.put("id", UUID.randomUUID().toString());
    envelope.put("createdAt", "2026-10-06T10:15:00Z");
    envelope.putObject("audience").put("allUsers", true);
    envelope.putObject("title").put("en", titleText);
    envelope.putObject("body").put("en", "Published over the HTTP path.");
    return envelope.toString();
  }

  @Autowired com.crosshubber.portal.modules.msgcenter.tasks.MsgCenterTaskService taskService;

  @Autowired com.crosshubber.portal.modules.msgcenter.domain.McTaskActivityRepository activityRepo;

  @Autowired com.crosshubber.portal.modules.msgcenter.tasks.AllowlistSubmitValidator shapeValidator;

  @Test
  void claimTakeoverFlowEndToEnd() {
    // 1. publish a claim-mode collect task addressed to allUsers
    String id = UUID.randomUUID().toString();
    String subject =
        "portal.task.portal-dashboard.it-claim-" + UUID.randomUUID().toString().substring(0, 8);
    ObjectNode task = mapper.createObjectNode();
    task.put("kind", "collect");
    task.put("completion", "any");
    task.put("completionEvent", "loan.requested");
    task.putObject("claim").put("enabled", true).put("mode", "single");
    ObjectNode field = task.putArray("fields").addObject();
    field.put("name", "days");
    field.put("required", true);
    field.putObject("schema").put("type", "integer").put("maximum", 30);
    ObjectNode envelope = mapper.createObjectNode();
    envelope.put("v", 1);
    envelope.put("type", "task");
    envelope.put("moduleKey", "portal-dashboard");
    envelope.put("id", id);
    envelope.put("createdAt", "2026-10-06T10:15:00Z");
    envelope.putObject("audience").put("allUsers", true);
    envelope.putObject("title").put("en", "Loaner request");
    envelope.putObject("body").put("en", "Someone please take this.");
    envelope.set("task", task);
    eventPublisher.publish(subject, envelope.toString());

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              var stored = messageRepo.findByEventId(id);
              assertThat(stored).isPresent();
              assertThat(stored.orElseThrow().getTaskJson()).isNotNull();
            });

    long messageId = messageRepo.findByEventId(id).orElseThrow().getId();

    // 2. Alice claims and saves a draft
    var claimed = taskService.claim(messageId, "alice", "Alice");
    assertThat(claimed.fromName()).isNull();
    taskService.saveDraft(messageId, "alice", "Alice", mapper.readTree("{\"days\": 2}"), null);

    // 3. Bob's claim races the claim state → 409
    ResponseStatusException clash =
        org.junit.jupiter.api.Assertions.assertThrows(
            ResponseStatusException.class, () -> taskService.claim(messageId, "bob", "Bob"));
    org.junit.jupiter.api.Assertions.assertEquals(409, clash.getStatusCode().value());

    // 4. Alice releases keeping the draft; Bob takes over and adopts it
    taskService.release(messageId, "alice", "Alice", false, false);
    taskService.claim(messageId, "bob", "Bob");
    var offered = taskService.previousDraftOf(messageRepo.findById(messageId).orElseThrow(), "bob");
    assertThat(offered.fromName()).isEqualTo("Alice");
    assertThat(offered.data()).containsEntry("days", 2);
    Map<String, Object> adopted = taskService.adoptDraft(messageId, "bob", "Bob");
    assertThat(adopted).containsEntry("days", 2);

    // 5. Bob submits → closes; audit timeline holds the whole arc
    var result =
        taskService.respond(
            messageId,
            "bob",
            "Bob",
            "submit",
            mapper.readTree("{\"days\": 2}"),
            null,
            shapeValidator);
    assertThat(result.closed()).isTrue();
    assertThat(messageRepo.findById(messageId).orElseThrow().getStatus()).isEqualTo("done");

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              List<String> actions =
                  activityRepo.findByMessageIdOrderByCreatedAtDescIdDesc(messageId).stream()
                      .map(r -> r.getAction())
                      .toList();
              assertThat(actions).contains("claim", "draft_save", "release", "adopt", "respond");
            });
  }

  @Autowired com.crosshubber.portal.modules.msgcenter.domain.MsgCenterQueryService queryService;

  /**
   * Inbox read-model regression (live bug 2026-10-06): the audience predicate previously spelled
   * the <code>?|</code> JSONB operator, whose bare <code>?</code> is a bind placeholder for the
   * Postgres JDBC driver → "No value specified for parameter N" 500s on every inbox/unread call.
   * These are the only tests that execute listOwn/unread/markRead against real SQL.
   */
  @Test
  void inboxReadModelRunsTheAudiencePredicate() {
    // valid published envelopes arrive (proven by other tests); await one, then read it back
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> assertThat(queryService.unread("nobody-1", List.of())).isGreaterThanOrEqualTo(0));

    String viewer = "viewer-1";
    long unread = queryService.unread(viewer, List.of());
    List<com.crosshubber.portal.modules.msgcenter.domain.MsgCenterQueryService.InboxItemDto> page =
        queryService.listOwn(viewer, List.of(), null, null, null, null, 10);
    assertThat(page.size()).isGreaterThanOrEqualTo(0);
    long unreadAfter = queryService.unread(viewer, List.of());
    assertThat(unreadAfter).isEqualTo(unread);

    // audience-gated visibility: a users[]-pinned message is visible to its recipient only,
    // and each channel (roles/groups/allUsers) parses without the driver placeholder bug
    String id = UUID.randomUUID().toString();
    ObjectNode envelope = mapper.createObjectNode();
    envelope.put("v", 1);
    envelope.put("type", "notification");
    envelope.put("moduleKey", "portal-dashboard");
    envelope.put("id", id);
    envelope.put("createdAt", "2026-10-06T10:15:00Z");
    envelope.putObject("audience").putArray("users").add(viewer);
    envelope.putObject("title").put("en", "Reader regression");
    envelope.putObject("body").put("en", "audience users[] pin");
    eventPublisher.publish(
        "portal.msg.portal-dashboard.reader-" + UUID.randomUUID().toString().substring(0, 8),
        envelope.toString());

    final long before = unreadAfter;
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> assertThat(queryService.unread(viewer, List.of())).isEqualTo(before + 1));

    var mine = queryService.listOwn(viewer, List.of(), null, null, null, null, 10);
    long targetId =
        mine.stream()
            .filter(i -> "Reader regression".equals(titleEn(i)))
            .findFirst()
            .orElseThrow()
            .id();
    queryService.markRead(viewer, List.of(), targetId);
    assertThat(queryService.unread(viewer, List.of())).isEqualTo(before);
    assertThrows404(() -> queryService.markRead("stranger", List.of(), targetId));
  }

  private static String titleEn(
      com.crosshubber.portal.modules.msgcenter.domain.MsgCenterQueryService.InboxItemDto item) {
    Object en = item.title() != null ? item.title().get("en") : null;
    return en instanceof String s ? s : null;
  }

  private static void assertThrows404(org.junit.jupiter.api.function.Executable call) {
    var thrown = org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class, call);
    org.junit.jupiter.api.Assertions.assertEquals(404, thrown.getStatusCode().value());
  }
}
