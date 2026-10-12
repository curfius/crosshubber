package com.crosshubber.talenthub.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** Strict-v3 envelope shape: known keys only, audience/title/severity/subject/link contracts. */
class EventEnvelopeFactoryTest {

  private final EventEnvelopeFactory factory = new EventEnvelopeFactory(new JsonMapper());

  @Test
  void messageBuildsTheFullStrictV3Shape() {
    var envelope =
        factory.message(
            "rfp.captured",
            "New RFP captured",
            "Acme asked for a Java squad.",
            "info",
            List.of("staffing-recruiter", "staffing-admin"),
            "/rfps");

    assertThat(envelope.get("v").asInt()).isEqualTo(1);
    assertThat(envelope.get("type").asString()).isEqualTo("message");
    assertThat(envelope.get("moduleKey").asString()).isEqualTo("talent-hub");
    assertThat(envelope.get("id").asString()).matches("[0-9a-f-]{36}");
    assertThat(envelope.get("createdAt").asString()).isNotBlank();
    assertThat(envelope.get("subject").asString()).isEqualTo("portal.msg.talent-hub.rfp.captured");
    assertThat(envelope.get("severity").asString()).isEqualTo("info");
    assertThat(envelope.get("audience").get("roles").size()).isEqualTo(2);
    assertThat(envelope.get("audience").get("roles").get(0).asString())
        .isEqualTo("staffing-recruiter");
    assertThat(envelope.get("title").get("en").asString()).isEqualTo("New RFP captured");
    assertThat(envelope.get("body").get("en").asString()).isEqualTo("Acme asked for a Java squad.");
    assertThat(envelope.get("link").get("moduleKey").asString()).isEqualTo("talent-hub");
    assertThat(envelope.get("link").get("path").asString()).isEqualTo("/rfps");
  }

  @Test
  void bodyAndLinkAreOmittedWhenNull() {
    var envelope =
        factory.message(
            "rfp.approved", "RFP approved", null, null, List.of("staffing-admin"), null);

    assertThat(envelope.has("body")).isFalse();
    assertThat(envelope.has("link")).isFalse();
    assertThat(envelope.has("severity")).isFalse();
  }

  @Test
  void everyEmittedKeyIsAKnownEnvelopeKey() {
    var envelope =
        factory.message(
            "rfp.phase-changed",
            "Phase changed",
            "Now sourcing.",
            "info",
            List.of("staffing-manager"),
            "/rfps");
    factory.assertKnownKeys(envelope);

    var keys = new java.util.ArrayList<String>();
    envelope.properties().forEach(entry -> keys.add(entry.getKey()));
    assertThat(keys).isSubsetOf(EventEnvelopeFactory.ALLOWED_KEYS);
  }

  @Test
  void malformedInputsAreRejected() {
    assertThatThrownBy(() -> factory.message("x", "  ", null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> factory.message("x", "t".repeat(501), null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> factory.message("x", "t", null, "loud", null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
