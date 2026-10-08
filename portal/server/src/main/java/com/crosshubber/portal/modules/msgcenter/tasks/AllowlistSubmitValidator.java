package com.crosshubber.portal.modules.msgcenter.tasks;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.modules.msgcenter.domain.McMessageEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Submit-path data validation (plan §4, checkpoint 3): the ONLY authority before a response row is
 * recorded or a completion event published.
 *
 * <p>Implementation note (decision log 2026-10-06): v1 ships a hand-rolled rule engine covering
 * exactly the field-allowlist semantics (required, type, enum ≤ 50, format, pattern, bounds, caps)
 * — a deterministic, dependency-free reference. The networknt library integration remains an
 * approved improvement for a later pass; the envelope shape does not change.
 *
 * <p>Draft writes reuse {@link #shapeCheck} without the required-field pass.
 */
@Component
public class AllowlistSubmitValidator implements SubmitDataValidator {

  @Override
  public Map<String, Object> validate(
      McMessageEntity message, JsonNode data, String actorSub, String actorName) {
    Map<String, Object> checked = shapeCheck(message, data);
    if (knownFieldNames(message) != null) {
      requireFields(message, checked);
    }
    return checked;
  }

  /** Shape-check shared by drafts (lenient) and submits (strict per-field rules applied after). */
  public Map<String, Object> shapeCheck(McMessageEntity message, JsonNode data) {
    if (data == null || data.isNull()) {
      return new java.util.LinkedHashMap<>();
    }
    if (!data.isObject()) {
      throw badRequest("data must be a JSON object");
    }
    ObjectNode object = (ObjectNode) data;
    var known = knownFieldNames(message);
    if (known == null && !object.isEmpty()) {
      throw badRequest("task does not accept form data");
    }
    if (known != null && object.size() > known.size()) {
      throw badRequest("data contains unknown fields");
    }
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : object.properties()) {
      String name = entry.getKey();
      JsonNode value = entry.getValue();
      if (known != null && !known.containsKey(name)) {
        throw badRequest("unknown data field '" + name + "'");
      }
      if (value.isString()) {
        if (value.asString().length() > MAX_SUBMITTED_STRING) {
          throw badRequest("data field '" + name + "' exceeds " + MAX_SUBMITTED_STRING + " chars");
        }
        out.put(name, value.asString());
      } else if (value.isNumber()) {
        out.put(name, value.decimalValue());
      } else if (value.isBoolean()) {
        out.put(name, value.asBoolean());
      } else if (value.isNull()) {
        out.put(name, null);
      } else {
        throw badRequest("data field '" + name + "' must be a scalar");
      }
    }
    return out;
  }

  /** Per-field schema rules (allowlist keywords only) + required-field presence. */
  private void requireFields(McMessageEntity message, Map<String, Object> checked) {
    for (Map.Entry<String, Map<String, Object>> field : knownFieldNames(message).entrySet()) {
      String name = field.getKey();
      Map<String, Object> schema = field.getValue();
      Object value = checked.get(name);
      boolean required = Boolean.TRUE.equals(schema.get("required"));
      if (value == null) {
        if (required) {
          throw badRequest("field '" + name + "' is required");
        }
        continue;
      }
      checkScalar(name, value, schema);
    }
  }

  private void checkScalar(String name, Object value, Map<String, Object> schema) {
    String type = (String) schema.getOrDefault("type", "string");
    switch (type) {
      case "string" -> {
        if (!(value instanceof String s)) {
          throw badRequest("field '" + name + "' must be a string");
        }
        Integer minLen = intOf(schema.get("minLength"));
        Integer maxLen = intOf(schema.get("maxLength"));
        if (minLen != null && s.length() < minLen) {
          throw badRequest("field '" + name + "' must be at least " + minLen + " chars");
        }
        if (maxLen != null && s.length() > maxLen) {
          throw badRequest("field '" + name + "' must be at most " + maxLen + " chars");
        }
        String pattern = (String) schema.get("pattern");
        if (pattern != null && !s.matches(pattern)) {
          throw badRequest("field '" + name + "' does not match its pattern");
        }
        String format = (String) schema.get("format");
        if (format != null && !formatMatches(format, s)) {
          throw badRequest("field '" + name + "' is not a valid " + format);
        }
        checkEnum(name, value, schema);
      }
      case "integer" -> {
        if (!(value instanceof java.math.BigDecimal bd) || bd.stripTrailingZeros().scale() > 0) {
          throw badRequest("field '" + name + "' must be an integer");
        }
        checkNumericBounds(name, bd, schema);
      }
      case "number" -> {
        if (!(value instanceof java.math.BigDecimal bd)) {
          throw badRequest("field '" + name + "' must be a number");
        }
        checkNumericBounds(name, bd, schema);
      }
      case "boolean" -> {
        if (!(value instanceof Boolean)) {
          throw badRequest("field '" + name + "' must be a boolean");
        }
      }
      default -> throw badRequest("field '" + name + "' has an unsupported type");
    }
  }

  private void checkEnum(String name, Object value, Map<String, Object> schema) {
    Object options = schema.get("enum");
    if (options instanceof java.util.List<?> list
        && list.stream().noneMatch(option -> scalarEquals(option, value))) {
      throw badRequest("field '" + name + "' must be one of its enum options");
    }
  }

  private static boolean scalarEquals(Object a, Object b) {
    if (a instanceof Number na && b instanceof Number nb) {
      return na.doubleValue() == nb.doubleValue();
    }
    return String.valueOf(a).equals(String.valueOf(b));
  }

  private void checkNumericBounds(
      String name, java.math.BigDecimal value, Map<String, Object> schema) {
    Object min = schema.get("minimum");
    Object max = schema.get("maximum");
    if (min instanceof Number n && value.doubleValue() < n.doubleValue()) {
      throw badRequest("field '" + name + "' must be ≥ " + n);
    }
    if (max instanceof Number n && value.doubleValue() > n.doubleValue()) {
      throw badRequest("field '" + name + "' must be ≤ " + n);
    }
  }

  private boolean formatMatches(String format, String value) {
    try {
      return switch (format) {
        // browser twin: task-form-model.formatMatches — keep the two lines in lockstep
        // (ISO yyyy-MM-dd; Java's LocalDate.parse accepts "2026-2-3" but the TS side does not,
        // so the server also pins the zero-padded shape here)
        case "date" ->
            value.matches("\\d{4}-\\d{2}-\\d{2}") && java.time.LocalDate.parse(value) != null;
        case "email" -> value.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
        case "uri" -> java.net.URI.create(value) != null;
        default -> true;
      };
    } catch (Exception e) {
      return false;
    }
  }

  private static Integer intOf(Object value) {
    return value instanceof Number n ? n.intValue() : null;
  }

  /** name → (schema + required) map from the stored task spec; null when the task has no fields. */
  @SuppressWarnings("unchecked")
  public static java.util.Map<String, Map<String, Object>> knownFieldNames(
      McMessageEntity message) {
    if (message.getTaskJson() == null) {
      return null;
    }
    Object fields = message.getTaskJson().get("fields");
    if (!(fields instanceof java.util.List<?> list)) {
      return null;
    }
    java.util.Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
    for (Object field : list) {
      if (field instanceof Map<?, ?> map && map.get("name") instanceof String name) {
        Map<String, Object> schema =
            map.get("schema") instanceof Map<?, ?> s
                ? new java.util.LinkedHashMap<>((Map<String, Object>) s)
                : Map.of();
        if (map.get("required") instanceof Boolean b) {
          schema.put("required", b);
        }
        out.put(name, schema);
      }
    }
    return out;
  }

  private static ResponseStatusException badRequest(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }

  private static final int MAX_SUBMITTED_STRING = 2000;
}
