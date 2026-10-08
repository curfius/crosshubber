package com.crosshubber.portal.modules.msgcenter.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.crosshubber.portal.modules.msgcenter.domain.McMessageEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Server side of the schema-equivalence contract (plan checkpoints 3/4): both this suite and the
 * browser suite (portal/ui msg-center/task-form-model.spec.ts) read the SAME fixture, {@code
 * msgcenter/schema-contract.json}, and must agree on accept/reject for every case. A verdict
 * divergence here means the browser and the portal disagree on what a valid form is — the exact
 * failure mode the four-checkpoint design guards against.
 */
class SubmitDataValidatorContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final List<FieldRow> fields;
  private final List<CaseRow> cases;

  SubmitDataValidatorContractTest() {
    JsonNode root;
    try (var in = getClass().getResourceAsStream("/msgcenter/schema-contract.json")) {
      root = MAPPER.readTree(in);
    } catch (Exception e) {
      throw new IllegalStateException("schema-contract fixture unreadable", e);
    }
    fields = new ArrayList<>();
    for (JsonNode f : root.path("fields")) {
      fields.add(
          new FieldRow(
              f.path("name").asString(),
              f.hasNonNull("required") ? f.path("required").asBoolean() : null,
              MAPPER.convertValue(
                  f.path("schema"),
                  MAPPER
                      .getTypeFactory()
                      .constructMapType(
                          java.util.LinkedHashMap.class, String.class, Object.class))));
    }
    cases = new ArrayList<>();
    for (JsonNode c : root.path("cases")) {
      cases.add(
          new CaseRow(
              c.path("field").asString(),
              MAPPER.convertValue(c.path("value"), Object.class),
              c.path("verdict").asString()));
    }
  }

  private record FieldRow(String name, Boolean required, Map<String, Object> schema) {}

  private record CaseRow(String field, Object value, String verdict) {}

  private McMessageEntity messageWithFields() {
    McMessageEntity message = new McMessageEntity();
    List<Object> rows = new ArrayList<>();
    for (FieldRow field : fields) {
      Map<String, Object> row = new java.util.LinkedHashMap<>();
      row.put("name", field.name());
      if (field.required() != null) {
        row.put("required", field.required());
      }
      row.put("schema", field.schema());
      rows.add(row);
    }
    message.setTaskJson(Map.of("fields", rows));
    return message;
  }

  @Test
  void serverVerdictsMatchTheSharedFixture() {
    var validator = new AllowlistSubmitValidator();
    McMessageEntity message = messageWithFields();
    // the fixture's required field (comment) is always satisfied so single-field cases stay
    // focused on their own schema rules; the dedicated requiredProbe covers absence
    int checked = 0;
    for (CaseRow row : cases) {
      ObjectNode data = MAPPER.createObjectNode();
      data.put("comment", "required filler");
      putAny(data, row.field(), row.value());
      boolean accepted;
      String failureReason = null;
      try {
        validator.validate(message, data, "u-test", "Tester");
        accepted = true;
      } catch (org.springframework.web.server.ResponseStatusException e) {
        accepted = false;
        failureReason = e.getReason();
      }
      final String reason = failureReason;
      boolean expected = "accept".equals(row.verdict());
      assertEquals(
          expected,
          accepted,
          () ->
              "divergence on "
                  + row.field()
                  + "="
                  + row.value()
                  + " (expected "
                  + row.verdict()
                  + "; reason: "
                  + reason
                  + ")");
      checked++;
    }
    assertEquals(27, checked, "fixture case count drift — update both suites together");
  }

  /** Required-field probe shared with the browser suite (missingRequired / requireFields). */
  @Test
  void requiredProbeMatchesTheSharedFixture() {
    var validator = new AllowlistSubmitValidator();
    McMessageEntity message = messageWithFields();
    JsonNode requiredCase;
    try (var in = getClass().getResourceAsStream("/msgcenter/schema-contract.json")) {
      requiredCase = MAPPER.readTree(in).path("requiredCase");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    ObjectNode payload =
        requiredCase.path("payload").isObject()
            ? (ObjectNode) requiredCase.path("payload")
            : MAPPER.createObjectNode();
    var e =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> validator.validate(message, payload, "u-test", "Tester"));
    assertEquals(400, e.getStatusCode().value());
    org.junit.jupiter.api.Assertions.assertTrue(
        String.valueOf(e.getReason()).contains("required"),
        () -> "expected a required-field rejection, got: " + e.getReason());
  }

  private static void putAny(ObjectNode node, String name, Object value) {
    if (value == null) {
      node.putNull(name);
    } else if (value instanceof Boolean b) {
      node.put(name, b);
    } else if (value instanceof Integer i) {
      node.put(name, i);
    } else if (value instanceof Long l) {
      node.put(name, l);
    } else if (value instanceof Double d) {
      node.put(name, d);
    } else {
      node.put(name, String.valueOf(value));
    }
  }
}
