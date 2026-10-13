package com.crosshubber.clienthub.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pure builder for strict-v3 msgcenter envelopes (portal {@code EnvelopeValidator} contract).
 * Every emitted key must be a known envelope key (unknown keys reject the whole envelope 422).
 * Audience: actor-relevant {@code portal-client-hub-*} roles.
 */
@Service
public class EventEnvelopeFactory {

  public static final String MODULE_KEY = "client-hub";

  static final List<String> ALLOWED_KEYS =
      List.of(
          "v", "type", "moduleKey", "sender", "subject", "id", "createdAt",
          "audience", "title", "body", "severity", "threadId", "link", "task");

  private final ObjectMapper mapper;

  public EventEnvelopeFactory(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Builds a message envelope.
   *
   * @param event event suffix (e.g. {@code account.created});
   *     subject = portal.msg.client-hub.&lt;event&gt;
   * @param title English title (max 500 chars)
   * @param body English body, nullable (max 4000 chars)
   * @param severity info|success|warning|error, nullable
   * @param roles audience roles (portal-client-hub-*)
   * @param linkPath in-module deep link, nullable ({@code /...})
   */
  public ObjectNode message(
      String event,
      String title,
      String body,
      String severity,
      List<String> roles,
      String linkPath) {
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("title is required");
    }
    if (title.length() > 500) {
      throw new IllegalArgumentException("title must be at most 500 chars");
    }
    if (severity != null && !List.of("info", "success", "warning", "error").contains(severity)) {
      throw new IllegalArgumentException("severity must be info|success|warning|error");
    }
    if (body != null && body.length() > 4000) {
      throw new IllegalArgumentException("body must be at most 4000 chars");
    }

    ObjectNode envelope = mapper.createObjectNode();
    envelope.put("v", 1);
    envelope.put("type", "message");
    envelope.put("moduleKey", MODULE_KEY);
    envelope.put("id", UUID.randomUUID().toString());
    envelope.put("createdAt", Instant.now().toString());
    envelope.put("subject", "portal.msg." + MODULE_KEY + "." + event);
    if (severity != null) {
      envelope.put("severity", severity);
    }
    ObjectNode audience = envelope.putObject("audience");
    var rolesArray = audience.putArray("roles");
    for (String role : roles == null ? List.<String>of() : roles) {
      rolesArray.add(role);
    }
    ObjectNode titleNode = envelope.putObject("title");
    titleNode.put("en", title);
    if (body != null && !body.isBlank()) {
      ObjectNode bodyNode = envelope.putObject("body");
      bodyNode.put("en", body);
    }
    if (linkPath != null && !linkPath.isBlank()) {
      ObjectNode link = envelope.putObject("link");
      link.put("moduleKey", MODULE_KEY);
      link.put("path", linkPath);
    }
    return envelope;
  }

  /** Structural self-check: all emitted keys must be known envelope keys (strict-v3). */
  public void assertKnownKeys(ObjectNode envelope) {
    List<String> unknown = new java.util.ArrayList<>();
    envelope
        .properties()
        .forEach(
            entry -> {
              if (!ALLOWED_KEYS.contains(entry.getKey())) {
                unknown.add(entry.getKey());
              }
            });
    if (!unknown.isEmpty()) {
      throw new IllegalStateException("envelope has unknown keys: " + unknown);
    }
  }
}
