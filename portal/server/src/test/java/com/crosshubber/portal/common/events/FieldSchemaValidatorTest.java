package com.crosshubber.portal.common.events;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class FieldSchemaValidatorTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private List<String> validate(String fieldJson) {
    List<String> errors = new ArrayList<>();
    FieldSchemaValidator.checkField(mapper.readTree(fieldJson), 1, errors);
    return errors;
  }

  @Test
  void acceptsAllV1FieldTypes() {
    assertDoesNotThrow(() -> validateClean(shortText()));
    assertDoesNotThrow(() -> validateClean(longText()));
    assertDoesNotThrow(() -> validateClean(numberField()));
    assertDoesNotThrow(() -> validateClean(booleanField()));
    assertDoesNotThrow(() -> validateClean(enumField()));
    assertDoesNotThrow(() -> validateClean(dateField()));
  }

  /** Runs checkField and rethrows the first collected error (empty list = accepted). */
  private void validateClean(String fieldJson) {
    List<String> errors = validate(fieldJson);
    if (!errors.isEmpty()) {
      throw new EnvelopeValidationException(errors.get(0));
    }
  }

  @Test
  void rejectsUnknownFieldKey() {
    List<String> errors =
        validate("{\"name\": \"a\", \"schema\": {\"type\": \"string\"}, \"default\": \"x\"}");
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("unknown key 'default'"));
  }

  @Test
  void rejectsNonKebabFieldName() {
    List<String> errors = validate("{\"name\": \"Big Field\", \"schema\": {\"type\": \"string\"}}");
    assertTrue(errors.get(0).contains(".name must match"));
  }

  @Test
  void rejectsFormatOnNonString() {
    List<String> errors =
        validate("{\"name\": \"n\", \"schema\": {\"type\": \"number\", \"format\": \"date\"}}");
    assertTrue(errors.get(0).contains("format is only valid"));
  }

  @Test
  void rejectsBadFormatValue() {
    List<String> errors =
        validate(
            "{\"name\": \"n\", \"schema\": {\"type\": \"string\", \"format\": \"date-time\"}}");
    assertTrue(errors.get(0).contains("format must be one of"));
  }

  @Test
  void rejectsInvalidRegexAndOverlongPattern() {
    List<String> errors =
        validate(
            "{\"name\": \"n\", \"schema\": {\"type\": \"string\", \"pattern\": \"[unclosed\"}}");
    assertTrue(errors.get(0).contains("not a valid regex"));
    List<String> tooLong =
        validate(
            "{\"name\": \"n\", \"schema\": {\"type\": \"string\", \"pattern\": \""
                + "a".repeat(129)
                + "\"}}");
    assertTrue(tooLong.get(0).contains("ReDoS guard"));
  }

  @Test
  void rejectsMinMaxOnStringsAndLengthsOnNumbers() {
    List<String> errors =
        validate("{\"name\": \"n\", \"schema\": {\"type\": \"number\", \"minLength\": 1}}");
    assertTrue(errors.get(0).contains("minLength/maxLength are only valid"));
    List<String> errors2 =
        validate("{\"name\": \"n\", \"schema\": {\"type\": \"string\", \"minimum\": 1}}");
    assertTrue(errors2.get(0).contains("minimum/maximum are only valid"));
  }

  @Test
  void rejectsMaxLengthBelowMinLength() {
    List<String> errors =
        validate(
            "{\"name\": \"n\", \"schema\": {\"type\": \"string\","
                + " \"minLength\": 5, \"maxLength\": 2}}");
    assertTrue(errors.get(0).contains("maxLength must be ≥ minLength"));
  }

  @Test
  void rejectsEnumOnBooleanAndOversizedEnum() {
    List<String> errors =
        validate("{\"name\": \"n\", \"schema\": {\"type\": \"boolean\", \"enum\": [true]}}");
    assertTrue(errors.get(0).contains("not valid for type=boolean"));

    StringBuilder many = new StringBuilder("[");
    for (int i = 0; i < 51; i++) {
      many.append(i > 0 ? "," : "").append("\"v").append(i).append("\"");
    }
    many.append("]");
    List<String> errors2 =
        validate("{\"name\": \"n\", \"schema\": {\"type\": \"string\", \"enum\": " + many + "}}");
    assertTrue(errors2.get(0).contains("exceeds 50"));
  }

  @Test
  void rejectsNonScalarEnumOptions() {
    List<String> errors =
        validate(
            "{\"name\": \"n\", \"schema\": {\"type\": \"string\", \"enum\": [\"a\", {\"x\": 1}]}}");
    assertTrue(errors.get(0).contains("scalars"));
  }

  @Test
  void rejectsLabelWithMissingEn() {
    List<String> errors =
        validate(
            "{\"name\": \"n\", \"label\": {\"de\": \"Feld\"}, \"schema\": {\"type\": \"string\"}}");
    assertTrue(errors.get(0).contains("label.en"));
  }

  @Test
  void exposesKeywordAllowlistForContractTests() {
    assertEquals(
        java.util.Set.of(
            "type", "enum", "format", "pattern", "minLength", "maxLength", "minimum", "maximum"),
        FieldSchemaValidator.SCHEMA_KEYWORDS);
    assertEquals(20, FieldSchemaValidator.MAX_FIELDS);
  }

  private String shortText() {
    return "{\"name\": \"email\", \"required\": true, \"label\": {\"en\": \"Email\"},"
        + " \"schema\": {\"type\": \"string\", \"format\": \"email\", \"maxLength\": 200}}";
  }

  private String longText() {
    return "{\"name\": \"comment\", \"multiline\": true,"
        + " \"schema\": {\"type\": \"string\", \"maxLength\": 500}}";
  }

  private String numberField() {
    return "{\"name\": \"hours\", \"required\": true,"
        + " \"schema\": {\"type\": \"number\", \"minimum\": 0, \"maximum\": 24}}";
  }

  private String booleanField() {
    return "{\"name\": \"attending\", \"schema\": {\"type\": \"boolean\"}}";
  }

  private String enumField() {
    return "{\"name\": \"meal\","
        + " \"schema\": {\"type\": \"string\", \"enum\": [\"meat\", \"fish\", \"veg\"]}}";
  }

  private String dateField() {
    return "{\"name\": \"from\", \"schema\": {\"type\": \"string\", \"format\": \"date\"}}";
  }

  @Test
  void nullSchemaRejected() {
    EnvelopeValidationException none =
        assertThrows(
            EnvelopeValidationException.class,
            () ->
                new EnvelopeValidator()
                    .validate(
                        mapper.readTree(
                            "{\"v\":1,\"type\":\"task\",\"moduleKey\":\"m\","
                                + "\"id\":\"0b9e6c1e-3333-4333-8333-333333333333\","
                                + "\"createdAt\":\"2026-10-06T10:15:00Z\","
                                + "\"audience\":{\"users\":[\"u\"]},"
                                + "\"title\":{\"en\":\"t\"},"
                                + "\"task\":{\"kind\":\"collect\",\"completion\":\"any\","
                                + "\"completionEvent\":\"a.b\","
                                + "\"fields\":[{\"name\":\"n\"}]}}")));
    assertTrue(none.getMessage().contains("schema object is required"));
  }
}
