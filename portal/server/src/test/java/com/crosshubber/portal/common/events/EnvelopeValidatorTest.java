package com.crosshubber.portal.common.events;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class EnvelopeValidatorTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final EnvelopeValidator validator = new EnvelopeValidator();

  private ObjectNode notification() {
    return (ObjectNode) mapper.readTree(safeJson());
  }

  private String safeJson() {
    return """
        {
          "v": 1,
          "type": "notification",
          "moduleKey": "solutions",
          "id": "0b9e6c1e-1111-4111-8111-111111111111",
          "createdAt": "2026-10-06T10:15:00Z",
          "audience": {
            "users": ["sub-1"], "roles": ["portal-approver"], "groups": ["back-office"],
            "allUsers": false
          },
          "title": { "en": "Timesheet due", "de": "Zeiterfassung faellig" },
          "body": {
            "en": "Intro",
            "sections": [ { "title": {"en": "Details"}, "text": {"en": "More"} } ]
          },
          "severity": "info",
          "threadId": "corr-1",
          "link": { "moduleKey": "solutions", "path": "/approvals/42" }
        }
        """;
  }

  private ObjectNode collectTask() {
    return (ObjectNode)
        mapper.readTree(
            """
            {
              "v": 1,
              "type": "task",
              "moduleKey": "sample-sender",
              "id": "0b9e6c1e-2222-4222-8222-222222222222",
              "createdAt": "2026-10-06T10:15:00Z",
              "audience": { "users": ["sub-1", "sub-2"], "allUsers": false },
              "title": { "en": "Daily hours" },
              "body": { "en": "Please report your hours." },
              "task": {
                "kind": "collect",
                "completion": "each",
                "completionEvent": "hours.submitted",
                "expiresAt": "2026-10-10T17:00:00Z",
                "sections": [ { "title": {"en": "Hours"}, "description": {"en": "Per project"},
                               "fields": ["hours", "comment"] } ],
                "fields": [
                  { "name": "hours", "required": true,
                    "schema": { "type": "number", "minimum": 0, "maximum": 24 } },
                  { "name": "comment", "multiline": true,
                    "schema": { "type": "string", "maxLength": 500 } }
                ]
              }
            }
            """);
  }

  @Test
  void acceptsValidNotification() {
    assertDoesNotThrow(() -> validator.validate(notification()));
  }

  @Test
  void acceptsValidCollectTask() {
    assertDoesNotThrow(() -> validator.validate(collectTask()));
  }

  private ObjectNode taskNode(ObjectNode envelope) {
    return (ObjectNode) envelope.get("task");
  }

  @Test
  void rejectsUnknownEnvelopeKeys() {
    ObjectNode bad = notification();
    bad.put("sneaky", true);
    EnvelopeValidationException e =
        assertThrows(EnvelopeValidationException.class, () -> validator.validate(bad));
    assertTrue(e.getMessage().contains("unknown envelope key 'sneaky'"));
  }

  @Test
  void rejectsWrongVersionAndType() {
    ObjectNode bad = notification();
    bad.put("v", 2);
    assertThrows(EnvelopeValidationException.class, () -> validator.validate(bad));
    ObjectNode badType = notification();
    badType.put("type", "push");
    assertThrows(EnvelopeValidationException.class, () -> validator.validate(badType));
  }

  @Test
  void rejectsEmptyAudience() {
    ObjectNode bad = notification();
    ObjectNode audience = (ObjectNode) bad.get("audience");
    audience.putArray("users");
    audience.putArray("roles");
    audience.putArray("groups");
    audience.put("allUsers", false);
    EnvelopeValidationException e =
        assertThrows(EnvelopeValidationException.class, () -> validator.validate(bad));
    assertTrue(e.getMessage().contains("at least one channel"));
  }

  @Test
  void rejectsTitleWithoutEn() {
    ObjectNode bad = notification();
    ObjectNode title = (ObjectNode) bad.get("title");
    title.remove("en");
    EnvelopeValidationException e =
        assertThrows(EnvelopeValidationException.class, () -> validator.validate(bad));
    assertTrue(e.getMessage().contains("title.en"));
  }

  @Test
  void rejectsTaskOutsideTaskType() {
    ObjectNode bad = notification();
    bad.putObject("task").put("kind", "approval");
    EnvelopeValidationException e =
        assertThrows(EnvelopeValidationException.class, () -> validator.validate(bad));
    assertTrue(e.getMessage().contains("only allowed for type=task"));
  }

  @Test
  void rejectsCollectWithoutCompletionEvent() {
    ObjectNode bad = collectTask();
    taskNode(bad).remove("completionEvent");
    EnvelopeValidationException e =
        assertThrows(EnvelopeValidationException.class, () -> validator.validate(bad));
    assertTrue(e.getMessage().contains("completionEvent is required"));
  }

  @Test
  void rejectsBadCompletionEventToken() {
    ObjectNode bad = collectTask();
    taskNode(bad).put("completionEvent", "Hours-Submitted!");
    EnvelopeValidationException e =
        assertThrows(
            EnvelopeValidationException.class,
            () -> validator.validate(mapper.readTree(bad.toString())));
    assertTrue(e.getMessage().contains("completionEvent must match"));
  }

  @Test
  void rejectsSectionReferenceToUnknownField() {
    ObjectNode bad = collectTask();
    JsonNode section = bad.get("task").get("sections").get(0);
    ArrayNode refs = (ArrayNode) section.get("fields");
    refs.remove(1);
    refs.add("nope");
    EnvelopeValidationException e =
        assertThrows(EnvelopeValidationException.class, () -> validator.validate(bad));
    assertTrue(e.getMessage().contains("unknown field 'nope'"));
  }

  @Test
  void rejectsDisallowedSchemaKeyword() {
    ObjectNode bad = collectTask();
    ObjectNode schema = (ObjectNode) bad.get("task").get("fields").get(0).get("schema");
    schema.putArray("allOf");
    EnvelopeValidationException e =
        assertThrows(EnvelopeValidationException.class, () -> validator.validate(bad));
    assertTrue(e.getMessage().contains("allOf"));
  }

  @Test
  void acceptsClaimWithAnyAndRejectsClaimWithEach() {
    ObjectNode ok = collectTask();
    taskNode(ok).put("completion", "any");
    ObjectNode claimOk = taskNode(ok).putObject("claim");
    claimOk.put("enabled", true);
    claimOk.put("mode", "single");
    assertDoesNotThrow(() -> validator.validate(ok));

    ObjectNode clash = collectTask();
    ObjectNode claim = taskNode(clash).putObject("claim");
    claim.put("enabled", true);
    claim.put("mode", "single");
    EnvelopeValidationException e =
        assertThrows(EnvelopeValidationException.class, () -> validator.validate(clash));
    assertTrue(e.getMessage().contains("completion=any"));
  }

  @Test
  void rejectsTemplateCombinedWithInlineFields() {
    ObjectNode bad = collectTask();
    ObjectNode template = taskNode(bad).putObject("template");
    template.put("key", "hours-report");
    template.put("version", 2);
    EnvelopeValidationException e =
        assertThrows(EnvelopeValidationException.class, () -> validator.validate(bad));
    assertTrue(e.getMessage().contains("template"));
  }

  @Test
  void rejectsTemplateWithoutPinnedVersion() {
    ObjectNode bad = collectTask();
    ObjectNode taskNode = taskNode(bad);
    taskNode.put("completion", "any");
    taskNode.remove("completionEvent");
    taskNode.remove("sections");
    taskNode.remove("fields");
    ObjectNode template = taskNode.putObject("template");
    template.put("key", "hours-report");
    EnvelopeValidationException e =
        assertThrows(EnvelopeValidationException.class, () -> validator.validate(bad));
    assertTrue(e.getMessage().contains("version"));
  }

  @Test
  void capsAreEnforced() {
    ObjectNode bad = notification();
    ArrayNode users = (ArrayNode) bad.get("audience").get("users");
    for (int i = 0; i < 201; i++) {
      users.add("u" + i);
    }
    EnvelopeValidationException e =
        assertThrows(EnvelopeValidationException.class, () -> validator.validate(bad));
    assertTrue(e.getMessage().contains("users"));
  }

  @Test
  void rejectsBadLinkAndSenderColor() {
    ObjectNode badLink = notification();
    ((ObjectNode) badLink.get("link")).put("path", "approvals/42");
    assertThrows(EnvelopeValidationException.class, () -> validator.validate(badLink));

    ObjectNode badColor = notification();
    badColor.putObject("sender").put("name", "T").put("color", "blue");
    EnvelopeValidationException e =
        assertThrows(EnvelopeValidationException.class, () -> validator.validate(badColor));
    assertTrue(e.getMessage().contains("sender.color"));
  }

  @Test
  void rejectsNonIsoCreated() {
    ObjectNode bad = notification();
    bad.put("createdAt", "2026-10-06 10:15");
    EnvelopeValidationException e =
        assertThrows(EnvelopeValidationException.class, () -> validator.validate(bad));
    assertTrue(e.getMessage().contains("createdAt"));
  }

  @Test
  void i18nMapHelperRejectsNonLanguageKeys() {
    List<String> errors = new ArrayList<>();
    EnvelopeValidator.checkI18nMap(
        mapper.readTree("{\"en\": \"ok\", \"not lang\": \"x\"}"), 100, "w", errors);
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("not lang"));
  }
}
