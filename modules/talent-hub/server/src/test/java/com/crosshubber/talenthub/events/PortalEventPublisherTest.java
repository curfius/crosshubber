package com.crosshubber.talenthub.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.crosshubber.talenthub.config.TalentHubProperties;

/** HMAC publish-secret derivation (portal PublishAuthenticator contract) + fail-soft HTTP. */
class PortalEventPublisherTest {

  private static final String SESSION_SECRET = "test-session-secret-32-chars-minimum!!";

  @Test
  void publishSecretMatchesThePortalContractExactly() throws Exception {
    String derived = PortalEventPublisher.publishSecret(SESSION_SECRET, "talent-hub");

    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SESSION_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String reference =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac.doFinal("talent-hub".getBytes(StandardCharsets.UTF_8)));

    assertThat(derived).isEqualTo(reference);
  }

  @Test
  void transportFailureIsSwallowedNotThrown() {
    TalentHubProperties props = new TalentHubProperties();
    props.getPortal().setUrl("http://127.0.0.1:1");
    PortalEventPublisher publisher =
        new PortalEventPublisher(
            props,
            new EventEnvelopeFactory(new tools.jackson.databind.json.JsonMapper()),
            org.springframework.web.client.RestClient.builder(),
            new tools.jackson.databind.json.JsonMapper());

    assertThatCode(
            () ->
                publisher.publishMessage(
                    "rfp.captured", "t", "b", "info", java.util.List.of("staffing-admin"), null))
        .doesNotThrowAnyException();
  }

  @Test
  void envelopeRejectionContractMirrorsThePortal() {
    // The portal rejects envelopes with unknown keys (422) — the factory self-check must catch
    // any drift before an HTTP round-trip happens.
    var factory = new EventEnvelopeFactory(new tools.jackson.databind.json.JsonMapper());
    var envelope = factory.message("rfp.captured", "t", null, null, java.util.List.of(), null);
    envelope.put("notAKey", "x");

    assertThatCode(() -> factory.assertKnownKeys(envelope))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("notAKey");
  }
}
