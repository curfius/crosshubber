package com.crosshubber.portal.common.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.crosshubber.portal.common.Keys;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Task field validation for the message-center envelope (plan §4): one allowlist of JSON Schema
 * keywords, the v1 field-type matrix, and every cap. Shared by envelope publish validation and
 * (later) template authoring — the Task Template Studio emits exactly this shape.
 */
public final class FieldSchemaValidator {

  /** Strict per-field-schema keyword allowlist — anything else is a 422/DLQ. */
  public static final Set<String> SCHEMA_KEYWORDS =
      Set.of("type", "enum", "format", "pattern", "minLength", "maxLength", "minimum", "maximum");

  private static final Set<String> TYPES = Set.of("string", "number", "integer", "boolean");
  private static final Set<String> FORMATS = Set.of("date", "email", "uri");

  public static final int MAX_FIELDS = 20;
  private static final int MAX_ENUM = 50;
  private static final int MAX_PATTERN = 128;
  private static final int MAX_SCHEMA_BYTES = 8 * 1024;
  private static final int MAX_LABEL_VALUE = 200;

  private static final Pattern FIELD_NAME = Pattern.compile(Keys.KEY_RE);

  private FieldSchemaValidator() {}

  /** Validates one field object ({name, required, label, multiline, schema}); returns its name. */
  public static String checkField(JsonNode field, int index, List<String> errors) {
    String where = "task.fields[" + index + "]";
    if (field == null || !field.isObject()) {
      errors.add(where + " must be an object");
      return null;
    }
    for (String key : names(field)) {
      if (!Set.of("name", "required", "label", "multiline", "schema").contains(key)) {
        errors.add(where + " has unknown key '" + key + "'");
      }
    }
    String name = stringOrNull(textOrNull(field.get("name")));
    if (name == null || !FIELD_NAME.matcher(name).matches()) {
      errors.add(where + ".name must match " + Keys.KEY_RE);
      return null;
    }
    JsonNode required = field.get("required");
    if (required != null && !required.isBoolean()) {
      errors.add(where + ".required must be a boolean");
    }
    JsonNode multiline = field.get("multiline");
    if (multiline != null && !multiline.isBoolean()) {
      errors.add(where + ".multiline must be a boolean");
    }
    if (field.has("label") && !field.get("label").isNull()) {
      EnvelopeValidator.checkI18nMap(field.get("label"), MAX_LABEL_VALUE, where + ".label", errors);
    }
    JsonNode schema = field.get("schema");
    if (schema == null || !schema.isObject()) {
      errors.add(where + ".schema object is required");
      return name;
    }
    checkSchema((ObjectNode) schema, where + ".schema", errors);
    return name;
  }

  /** Validates one schema fragment against the strict keyword allowlist. */
  public static void checkSchema(ObjectNode schema, String where, List<String> errors) {
    for (String key : names(schema)) {
      if (!SCHEMA_KEYWORDS.contains(key)) {
        errors.add(where + " rejects keyword '" + key + "' (allowlist: " + SCHEMA_KEYWORDS + ")");
      }
    }
    JsonNode type = schema.get("type");
    if (type == null || !type.isString() || !TYPES.contains(type.asString())) {
      errors.add(where + ".type must be one of " + TYPES);
      return;
    }
    String t = type.asString();
    if (schema.toString().length() > MAX_SCHEMA_BYTES) {
      errors.add(where + " exceeds " + MAX_SCHEMA_BYTES + " bytes");
    }
    checkFormat(schema, t, where, errors);
    checkPattern(schema, t, where, errors);
    checkLengthBounds(schema, t, where, errors);
    checkNumericBounds(schema, t, where, errors);
    checkEnum(schema, t, where, errors);
  }

  private static void checkFormat(
      ObjectNode schema, String type, String where, List<String> errors) {
    JsonNode format = schema.get("format");
    if (format == null || format.isNull()) {
      return;
    }
    if (!"string".equals(type)) {
      errors.add(where + ".format is only valid for type=string");
      return;
    }
    String f = stringOrNull(textOrNull(format));
    if (f == null || !FORMATS.contains(f)) {
      errors.add(where + ".format must be one of " + FORMATS);
    }
  }

  private static void checkPattern(
      ObjectNode schema, String type, String where, List<String> errors) {
    JsonNode pattern = schema.get("pattern");
    if (pattern == null || pattern.isNull()) {
      return;
    }
    if (!"string".equals(type)) {
      errors.add(where + ".pattern is only valid for type=string");
      return;
    }
    String p = stringOrNull(textOrNull(pattern));
    if (p == null || p.isBlank() || p.length() > MAX_PATTERN) {
      errors.add(
          where + ".pattern must be a non-blank regex ≤ " + MAX_PATTERN + " chars (ReDoS guard)");
      return;
    }
    try {
      Pattern.compile(p);
    } catch (Exception e) {
      errors.add(where + ".pattern is not a valid regex: " + e.getMessage());
    }
  }

  private static void checkLengthBounds(
      ObjectNode schema, String type, String where, List<String> errors) {
    if (!"string".equals(type)) {
      if (schema.has("minLength") || schema.has("maxLength")) {
        errors.add(where + ".minLength/maxLength are only valid for type=string");
      }
      return;
    }
    Long min = intOrNull(schema.get("minLength"), where + ".minLength", errors);
    Long max = intOrNull(schema.get("maxLength"), where + ".maxLength", errors);
    if (min != null && min < 0) {
      errors.add(where + ".minLength must be ≥ 0");
    }
    if (min != null && max != null && max < min) {
      errors.add(where + ".maxLength must be ≥ minLength");
    }
  }

  private static void checkNumericBounds(
      ObjectNode schema, String type, String where, List<String> errors) {
    if (!"number".equals(type) && !"integer".equals(type)) {
      if (schema.has("minimum") || schema.has("maximum")) {
        errors.add(where + ".minimum/maximum are only valid for type=number|integer");
      }
      return;
    }
    JsonNode min = schema.get("minimum");
    JsonNode max = schema.get("maximum");
    if (min != null && !min.isNull() && !min.isNumber()) {
      errors.add(where + ".minimum must be a number");
    }
    if (max != null && !max.isNull() && !max.isNumber()) {
      errors.add(where + ".maximum must be a number");
    }
    if (min != null
        && min.isNumber()
        && max != null
        && max.isNumber()
        && max.asDouble() < min.asDouble()) {
      errors.add(where + ".maximum must be ≥ minimum");
    }
  }

  private static void checkEnum(ObjectNode schema, String type, String where, List<String> errors) {
    JsonNode enumNode = schema.get("enum");
    if (enumNode == null || enumNode.isNull()) {
      return;
    }
    if ("boolean".equals(type)) {
      errors.add(where + ".enum is not valid for type=boolean");
      return;
    }
    if (!enumNode.isArray()) {
      errors.add(where + ".enum must be an array");
      return;
    }
    if (enumNode.size() > MAX_ENUM) {
      errors.add(where + ".enum exceeds " + MAX_ENUM + " options");
      return;
    }
    for (JsonNode option : enumNode) {
      if (!option.isString() && !option.isNumber() && !option.isBoolean()) {
        errors.add(where + ".enum options must be scalars");
        return;
      }
    }
  }

  private static Long intOrNull(JsonNode node, String where, List<String> errors) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isIntegralNumber()) {
      errors.add(where + " must be a non-negative integer");
      return null;
    }
    return node.asLong();
  }

  private static JsonNode textOrNull(JsonNode node) {
    return node == null || node.isNull() ? null : node;
  }

  /** Text extraction for Jackson 3 nodes ({@code asString}); null when not a string node. */
  private static String stringOrNull(JsonNode node) {
    return node != null && node.isString() ? node.asString() : null;
  }

  private static List<String> names(JsonNode object) {
    List<String> keys = new ArrayList<>();
    object.properties().forEach(entry -> keys.add(entry.getKey()));
    return keys;
  }
}
