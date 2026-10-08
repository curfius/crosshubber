package com.crosshubber.portal.modules.msgcenter.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.crosshubber.portal.modules.msgcenter.domain.McMessageEntity;
import com.crosshubber.portal.modules.settings.modules.ModuleSettingsService;
import com.crosshubber.portal.modules.usersettings.scopes.UserSettingsService;
import com.crosshubber.portal.security.CryptoService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Email-channel delivery ITs (Phase 7) over Testcontainers PostgreSQL + Mailpit: the SMTP config
 * seeds via env, the mirror fires per-recipient through per-group flags and the global fallback
 * switch, rendering uses the recipient's language, and the stored secret never leaks. Assertions
 * ride Mailpit's REST API — the same tool as the dev UI (one mental model, no extra Java
 * dependency).
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
class MsgCenterEmailIT {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  @Container
  static final GenericContainer MAILPIT =
      new GenericContainer(DockerImageName.parse("axllent/mailpit")).withExposedPorts(1025, 8025);

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    String url = POSTGRES.getJdbcUrl();
    String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=test";
    registry.add("spring.datasource.url", () -> schemaUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    // insert-if-absent SMTP seed → Mailpit (no auth, plain)
    registry.add("PORTAL_SMTP_HOST", () -> MAILPIT.getHost());
    registry.add("PORTAL_SMTP_PORT", () -> String.valueOf(MAILPIT.getMappedPort(1025)));
    registry.add("PORTAL_SMTP_FROM", () -> "portal@crosshubber.test");
  }

  @Autowired SmtpConfigService smtpConfigService;

  @Autowired MsgCenterEmailMirror mirror;

  @Autowired MsgCenterEmailSender sender;

  @Autowired ModuleSettingsService moduleSettings;

  @Autowired UserSettingsService userSettings;

  @Autowired com.crosshubber.portal.modules.msgcenter.groups.MsgCenterGroupService groupService;

  @Autowired CryptoService cryptoService;

  @Autowired ObjectMapper mapper;

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /** True once Mailpit's REST API answers (container readiness gate for SMTP sends). */
  private static boolean mailpitReady() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "http://"
                          + MAILPIT.getHost()
                          + ":"
                          + MAILPIT.getMappedPort(8025)
                          + "/api/v1/messages"))
              .timeout(Duration.ofSeconds(3))
              .GET()
              .build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200;
    } catch (Exception e) {
      return false;
    }
  }

  @org.junit.jupiter.api.BeforeAll
  static void awaitMailpitReady() {
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(300))
        .until(MsgCenterEmailIT::mailpitReady);
  }

  /**
   * Mailpit REST assertion helper: messages addressed to one recipient. Client-side To filter —
   * Mailpit's {@code to:} search proved unreliable through HTTP query params.
   */
  private int mailpitCount(String to) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "http://"
                          + MAILPIT.getHost()
                          + ":"
                          + MAILPIT.getMappedPort(8025)
                          + "/api/v1/messages?limit=250"))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode node = mapper.readTree(response.body());
      int count = 0;
      for (JsonNode message : node.path("messages")) {
        for (JsonNode recipient : message.path("To")) {
          if (to.equals(recipient.path("Address").asString())) {
            count++;
            break;
          }
        }
      }
      return count;
    } catch (Exception e) {
      return -1;
    }
  }

  private String lastSubjectFor(String to) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "http://"
                          + MAILPIT.getHost()
                          + ":"
                          + MAILPIT.getMappedPort(8025)
                          + "/api/v1/messages?limit=250"))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode messages = mapper.readTree(response.body()).path("messages");
      for (JsonNode message : messages) {
        for (JsonNode recipient : message.path("To")) {
          if (to.equals(recipient.path("Address").asString())) {
            return message.path("Subject").asString();
          }
        }
      }
      return "";
    } catch (Exception e) {
      return "";
    }
  }

  private McMessageEntity arrival(String audienceJson) {
    McMessageEntity message = new McMessageEntity();
    setEntityId(message, Math.abs(UUID.randomUUID().getMostSignificantBits()));
    message.setMsgType("notification");
    message.setModuleKey("portal-dashboard");
    message.setAudienceJson(mapper.readValue(audienceJson, Map.class));
    message.setTitleJson(Map.of("en", "Mirror check", "pt-PT", "Verificação espelho"));
    message.setBodyJson(Map.of("en", "Arrival mirror body.", "pt-PT", "Corpo na chegada."));
    message.setStatus("open");
    return message;
  }

  @Test
  void envSeedConfiguresMailpitAndSendTestReachesIt() {
    SmtpConfig config = smtpConfigService.live();
    assertThat(config).isNotNull();
    assertThat(config.enabled()).isTrue();
    // the host must be resolvable — send delivery is the real assertion
    assertThat(config.host()).isNotBlank();

    // end-to-end: the SMTP connection actually delivers into Mailpit
    assertThat(sender.sendTest(config, "probe@crosshubber.test")).isTrue();
    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                Assertions.assertThat(mailpitCount("probe@crosshubber.test"))
                    .isGreaterThanOrEqualTo(1));
  }

  private Integer portalSmtpPort() {
    String raw = String.valueOf(MAILPIT.getMappedPort(1025)); // same source the seed supplier reads
    return Integer.parseInt(raw);
  }

  @Test
  void groupFlagGatesMirrorAndFallbackGatesGeneralChannel() {
    // group with two members; only u1's email flag is on
    groupService.create("back-office", "Back Office", "open", "admin-1");
    groupService.addMember("back-office", "u-flagged", "admin-1", true);
    groupService.addMember("back-office", "u-quiet", "admin-1", true);
    groupService.setEmailFlag("back-office", "u-flagged", true);

    // addresses: flagged member + a non-member with the global fallback switch
    userSettings.update("u-flagged", "msgcenter", Map.of("email", "flagged@crosshubber.test"));
    userSettings.update("u-quiet", "msgcenter", Map.of("email", "quiet@crosshubber.test"));
    userSettings.update(
        "u-fallback",
        "msgcenter",
        Map.of("email", "fallback@crosshubber.test", "emailFallback", true));

    // 1. group-addressed arrival → flagged member only
    mirror.onArrival(arrival("{\"groups\": [\"back-office\"]}"));
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(mailpitCount("flagged@crosshubber.test")).isGreaterThanOrEqualTo(1));

    // 2. allUsers arrival → only the fallback subscriber (group members are NOT auto-included)
    mirror.onArrival(arrival("{\"allUsers\": true}"));
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(mailpitCount("fallback@crosshubber.test")).isGreaterThanOrEqualTo(1));
    assertThat(mailpitCount("quiet@crosshubber.test")).isZero();
  }

  @Test
  void recipientLanguageWinsOverEn() {
    groupService.create("lingua", "Língua", "open", "admin-1");
    groupService.addMember("lingua", "pt-user", "admin-1", true);
    groupService.setEmailFlag("lingua", "pt-user", true);
    userSettings.update("pt-user", "msgcenter", Map.of("email", "pt@crosshubber.test"));
    userSettings.update("pt-user", "general", Map.of("language", "pt-PT"));

    mirror.onArrival(arrival("{\"groups\": [\"lingua\"]}"));
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(mailpitCount("pt@crosshubber.test")).isGreaterThanOrEqualTo(1));
    assertThat(lastSubjectFor("pt@crosshubber.test")).contains("Verificação espelho");
  }

  @Test
  void secretNeverEchoedOrStoredPlaintext() {
    // NOTE: writes a deliberately bogus SMTP config (host "mailpit" is unresolvable from the
    // test JVM) — restore the live seed values at the end so JUnit's random method order can
    // never poison the other tests' deliveries.
    try {
      smtpConfigService.update(
          "mailpit", 1025, "portal@crosshubber.test", "none", "user", "super-secret-42", "u-admin");
      com.crosshubber.portal.modules.msgcenter.email.SmtpConfigService.SmtpConfigView view =
          smtpConfigService.view();
      assertThat(view.passwordSet()).isTrue();
      String viewJson = mapper.valueToTree(view).toString();
      assertThat(viewJson).doesNotContain("super-secret-42").doesNotContain("secretEnc");

      String stored = String.valueOf(moduleSettings.get("msgcenter").get("email"));
      assertThat(stored).doesNotContain("super-secret-42");

      // round-trip: the live config decrypts correctly for the sender
      assertThat(smtpConfigService.live().secret()).isEqualTo("super-secret-42");
      assertThat(cryptoService.maskApiKey("super-secret-42")).doesNotContain("super-secret-42");
    } finally {
      smtpConfigService.update(
          MAILPIT.getHost(),
          MAILPIT.getMappedPort(1025),
          "portal@crosshubber.test",
          "none",
          null,
          null,
          "u-restorer");
    }
  }

  private static void setEntityId(Object entity, long id) {
    try {
      var field = entity.getClass().getDeclaredField("id");
      field.setAccessible(true);
      field.set(entity, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
