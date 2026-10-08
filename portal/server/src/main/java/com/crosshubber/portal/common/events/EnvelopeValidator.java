package com.crosshubber.portal.common.events;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.crosshubber.portal.common.Keys;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Message-center envelope contract validation (plan sections 3/4).
 *
 * <p>Strict: unknown envelope keys, unknown schema keywords and cap violations reject the whole
 * envelope with {@link EnvelopeValidationException} (422 at publish, DLQ path at ingest). The
 * validator is pure structural checking — it performs no I/O and no template resolution; the task
 * form data itself is validated separately (networknt schema, submit path only).
 */
@Component
public class EnvelopeValidator {

  public static final String TYPE_NOTIFICATION = "notification";
  public static final String TYPE_MESSAGE = "message";
  public static final String TYPE_TASK = "task";

  public static final String KIND_APPROVAL = "approval";
  public static final String KIND_COLLECT = "collect";

  public static final String COMPLETION_ANY = "any";
  public static final String COMPLETION_EACH = "each";

  private static final Set<String> TYPES = Set.of(TYPE_NOTIFICATION, TYPE_MESSAGE, TYPE_TASK);
  private static final Set<String> KINDS = Set.of(KIND_APPROVAL, KIND_COLLECT);
  private static final Set<String> COMPLETIONS = Set.of(COMPLETION_ANY, COMPLETION_EACH);
  private static final Set<String> SEVERITIES = Set.of("info", "success", "warning", "error");

  private static final Set<String> ENVELOPE_KEYS =
      Set.of(
          "v",
          "type",
          "moduleKey",
          "sender",
          "subject",
          "id",
          "createdAt",
          "audience",
          "title",
          "body",
          "severity",
          "threadId",
          "link",
          "task");

  private static final Set<String> TASK_KEYS =
      Set.of(
          "kind",
          "completion",
          "completionEvent",
          "expiresAt",
          "claim",
          "template",
          "sections",
          "fields");

  private static final Pattern COMPLETION_EVENT = Pattern.compile(Keys.COMPLETION_EVENT_RE);
  private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{3,8}$");

  private static final int MAX_USERS = 200;
  private static final int MAX_ROLES = 20;
  private static final int MAX_GROUPS = 20;
  private static final int MAX_BODY_SECTIONS = 5;
  private static final int MAX_TASK_SECTIONS = 5;
  private static final int MAX_I18N_VALUE = 4000;
  private static final int MAX_TITLE_VALUE = 500;
  private static final int MAX_THREAD_ID = 128;
  private static final int MAX_LINK_PATH = 512;
  private static final int MAX_SENDER_NAME = 100;
  private static final int MAX_RECIPIENT_VALUE = 128;

  /**
   * Validates the envelope in place. Returns the same node (structure untouched) so callers can
   * persist exactly what they validated; template resolution happens before this validator runs.
   */
  public JsonNode validate(JsonNode envelope) {
    List<String> errors = new ArrayList<>();
    check(envelope, errors);
    if (!errors.isEmpty()) {
      throw new EnvelopeValidationException("invalid envelope: " + String.join("; ", errors));
    }
    return envelope;
  }

  private void check(JsonNode envelope, List<String> errors) {
    if (envelope == null || !envelope.isObject()) {
      errors.add("envelope must be a JSON object");
      return;
    }
    ObjectNode root = (ObjectNode) envelope;
    for (String name : names(root)) {
      if (!ENVELOPE_KEYS.contains(name)) {
        errors.add("unknown envelope key '" + name + "'");
      }
    }
    if (root.get("v") == null || !root.get("v").isInt() || root.get("v").asInt() != 1) {
      errors.add("v must be 1");
    }
    String type = stringOrNull(textOrNull(root.get("type")));
    if (type == null || !TYPES.contains(type)) {
      errors.add("type must be one of " + TYPES);
      return;
    }
    String moduleKey = stringOrNull(textOrNull(root.get("moduleKey")));
    if (moduleKey == null || !moduleKey.matches(Keys.KEY_RE)) {
      errors.add("moduleKey must match " + Keys.KEY_RE);
    }
    String id = stringOrNull(textOrNull(root.get("id")));
    if (id == null || !Keys.UUID_PATTERN.matcher(id).matches()) {
      errors.add("id must be a UUID (ingest idempotency key)");
    }
    if (instantOrNull(root.get("createdAt")) == null) {
      errors.add("createdAt must be an ISO-8601 instant");
    }

    checkSender(root.get("sender"), errors);
    checkAudience(root.get("audience"), errors);
    if (root.get("title") == null || root.get("title").isNull()) {
      errors.add("title is required (i18n map with en)");
    } else {
      checkI18nMap(root.get("title"), MAX_TITLE_VALUE, "title", errors);
    }
    checkBody(root.get("body"), errors);
    if (root.has("severity") && !root.get("severity").isNull()) {
      String severity = stringOrNull(textOrNull(root.get("severity")));
      if (severity == null || !SEVERITIES.contains(severity)) {
        errors.add("severity must be one of " + SEVERITIES);
      }
    }
    if (root.has("threadId") && !root.get("threadId").isNull()) {
      String threadId = stringOrNull(textOrNull(root.get("threadId")));
      if (threadId == null || threadId.isBlank() || threadId.length() > MAX_THREAD_ID) {
        errors.add("threadId must be a non-blank string of at most " + MAX_THREAD_ID + " chars");
      }
    }
    checkLink(root.get("link"), errors);

    JsonNode task = root.get("task");
    if (TYPE_TASK.equals(type)) {
      if (task == null || !task.isObject()) {
        errors.add("task object is required for type=task");
      } else {
        checkTask((ObjectNode) task, errors);
      }
    } else if (task != null && !task.isNull()) {
      errors.add("task is only allowed for type=task");
    }
  }

  private void checkSender(JsonNode sender, List<String> errors) {
    if (sender == null || sender.isNull()) {
      return;
    }
    if (!sender.isObject()) {
      errors.add("sender must be an object");
      return;
    }
    String name = stringOrNull(textOrNull(sender.get("name")));
    if (name != null && (name.isBlank() || name.length() > MAX_SENDER_NAME)) {
      errors.add("sender.name must be at most " + MAX_SENDER_NAME + " chars");
    }
    String color = stringOrNull(textOrNull(sender.get("color")));
    if (color != null && !HEX_COLOR.matcher(color).matches()) {
      errors.add("sender.color must be a hex color (#rgb/#rrggbb/#rrggbbaa)");
    }
  }

  private void checkAudience(JsonNode audience, List<String> errors) {
    if (audience == null || !audience.isObject()) {
      errors.add("audience object is required");
      return;
    }
    int users =
        checkStringArray(
            audience.get("users"), MAX_USERS, MAX_RECIPIENT_VALUE, "audience.users", errors);
    int roles =
        checkStringArray(
            audience.get("roles"), MAX_ROLES, MAX_RECIPIENT_VALUE, "audience.roles", errors);
    int groups =
        checkStringArray(audience.get("groups"), MAX_GROUPS, 64, "audience.groups", errors);
    JsonNode allUsers = audience.get("allUsers");
    if (allUsers != null && !allUsers.isNull() && !allUsers.isBoolean()) {
      errors.add("audience.allUsers must be a boolean");
      return;
    }
    boolean any =
        users > 0
            || roles > 0
            || groups > 0
            || (allUsers != null && allUsers.isBoolean() && allUsers.asBoolean());
    if (!any) {
      errors.add("audience must set at least one channel (users/roles/groups/allUsers)");
    }
  }

  private void checkBody(JsonNode body, List<String> errors) {
    if (body == null || body.isNull()) {
      return;
    }
    if (!body.isObject()) {
      errors.add("body must be an object (i18n map, en required, optional sections)");
      return;
    }
    ObjectNode bodyObj = (ObjectNode) body;
    for (String name : names(bodyObj)) {
      if ("sections".equals(name)) {
        continue;
      }
      if (!name.matches(Keys.LANG_CODE_RE)) {
        errors.add("body." + name + " is not a language key");
      }
    }
    checkI18nValue(bodyObj.get("en"), "body.en", errors);
    JsonNode sections = bodyObj.get("sections");
    if (sections == null || sections.isNull()) {
      return;
    }
    if (!sections.isArray()) {
      errors.add("body.sections must be an array");
      return;
    }
    if (sections.size() > MAX_BODY_SECTIONS) {
      errors.add("body.sections exceeds " + MAX_BODY_SECTIONS);
      return;
    }
    int i = 0;
    for (JsonNode section : sections) {
      i++;
      if (!section.isObject()) {
        errors.add("body.sections[" + i + "] must be an object");
        continue;
      }
      if (section.get("title") == null || section.get("title").isNull()) {
        errors.add("body.sections[" + i + "].title is required");
      } else {
        checkI18nMap(
            section.get("title"), MAX_TITLE_VALUE, "body.sections[" + i + "].title", errors);
      }
      if (section.has("text") && !section.get("text").isNull()) {
        checkI18nMap(section.get("text"), MAX_I18N_VALUE, "body.sections[" + i + "].text", errors);
      }
    }
  }

  private void checkLink(JsonNode link, List<String> errors) {
    if (link == null || link.isNull()) {
      return;
    }
    if (!link.isObject()) {
      errors.add("link must be an object {moduleKey, path}");
      return;
    }
    String moduleKey = stringOrNull(textOrNull(link.get("moduleKey")));
    if (moduleKey == null || !moduleKey.matches(Keys.KEY_RE)) {
      errors.add("link.moduleKey must match " + Keys.KEY_RE);
    }
    String path = stringOrNull(textOrNull(link.get("path")));
    if (path == null || !path.startsWith("/") || path.length() > MAX_LINK_PATH) {
      errors.add("link.path must start with '/' and be at most " + MAX_LINK_PATH + " chars");
    }
  }

  private void checkTask(ObjectNode task, List<String> errors) {
    for (String name : names(task)) {
      if (!TASK_KEYS.contains(name)) {
        errors.add("unknown task key '" + name + "'");
      }
    }
    String kind = stringOrNull(textOrNull(task.get("kind")));
    if (kind == null || !KINDS.contains(kind)) {
      errors.add("task.kind must be one of " + KINDS);
    }
    String completion = stringOrNull(textOrNull(task.get("completion")));
    if (completion == null || !COMPLETIONS.contains(completion)) {
      errors.add("task.completion must be one of " + COMPLETIONS);
    }
    String completionEvent = stringOrNull(textOrNull(task.get("completionEvent")));
    if (completionEvent != null && !COMPLETION_EVENT.matcher(completionEvent).matches()) {
      errors.add("task.completionEvent must match " + Keys.COMPLETION_EVENT_RE);
    } else if (completionEvent == null && KIND_COLLECT.equals(kind)) {
      errors.add("task.completionEvent is required for collect tasks");
    }
    if (task.has("expiresAt")
        && !task.get("expiresAt").isNull()
        && instantOrNull(task.get("expiresAt")) == null) {
      errors.add("task.expiresAt must be an ISO-8601 instant");
    }
    checkClaim(task.get("claim"), completion, errors);
    checkTaskShape(task, errors);
  }

  private void checkClaim(JsonNode claim, String completion, List<String> errors) {
    if (claim == null || claim.isNull()) {
      return;
    }
    if (!claim.isObject()) {
      errors.add("task.claim must be an object {enabled, mode}");
      return;
    }
    for (String name : names(claim)) {
      if (!Set.of("enabled", "mode").contains(name)) {
        errors.add("unknown task.claim key '" + name + "'");
      }
    }
    JsonNode enabled = claim.get("enabled");
    if (enabled == null || !enabled.isBoolean() || !enabled.asBoolean()) {
      errors.add("task.claim.enabled must be true when claim is declared");
    }
    String mode = stringOrNull(textOrNull(claim.get("mode")));
    if (mode == null || !"single".equals(mode)) {
      errors.add("task.claim.mode must be 'single'");
    }
    if (enabled != null
        && enabled.isBoolean()
        && enabled.asBoolean()
        && completion != null
        && !COMPLETION_ANY.equals(completion)) {
      errors.add("task.claim requires completion=any");
    }
  }

  /**
   * Shape source: template reference xor inline fields/sections. When a template is declared the
   * inline shape keys must be absent (the merged envelope is validated after resolution).
   */
  private void checkTaskShape(ObjectNode task, List<String> errors) {
    JsonNode template = task.get("template");
    boolean hasTemplate = template != null && !template.isNull();
    boolean hasFields = task.has("fields") && !task.get("fields").isNull();
    boolean hasSections = task.has("sections") && !task.get("sections").isNull();
    if (hasTemplate) {
      if (!template.isObject()) {
        errors.add("task.template must be an object {key, version}");
      } else {
        for (String name : names(template)) {
          if (!Set.of("key", "version").contains(name)) {
            errors.add("unknown task.template key '" + name + "'");
          }
        }
        String key = stringOrNull(textOrNull(template.get("key")));
        if (key == null || !key.matches(Keys.KEY_RE)) {
          errors.add("task.template.key must match " + Keys.KEY_RE);
        }
        JsonNode version = template.get("version");
        if (version == null || !version.isIntegralNumber() || version.asInt() < 1) {
          errors.add(
              "task.template.version must be an integer of at least 1 (pinned at send time)");
        }
      }
      if (hasFields) {
        errors.add("task.fields is not allowed together with task.template");
      }
      if (hasSections) {
        errors.add("task.sections is not allowed together with task.template");
      }
      return;
    }
    List<String> fieldNames = new ArrayList<>();
    if (hasFields) {
      JsonNode fields = task.get("fields");
      if (!fields.isArray()) {
        errors.add("task.fields must be an array");
        return;
      }
      if (fields.size() > FieldSchemaValidator.MAX_FIELDS) {
        errors.add("task.fields exceeds " + FieldSchemaValidator.MAX_FIELDS);
        return;
      }
      int i = 0;
      for (JsonNode field : fields) {
        i++;
        String name = FieldSchemaValidator.checkField(field, i, errors);
        if (name != null) {
          fieldNames.add(name);
        }
      }
    }
    checkTaskSections(task.get("sections"), fieldNames, errors);
  }

  private void checkTaskSections(JsonNode sections, List<String> fieldNames, List<String> errors) {
    if (sections == null || sections.isNull()) {
      return;
    }
    if (!sections.isArray()) {
      errors.add("task.sections must be an array");
      return;
    }
    if (sections.size() > MAX_TASK_SECTIONS) {
      errors.add("task.sections exceeds " + MAX_TASK_SECTIONS);
      return;
    }
    int i = 0;
    for (JsonNode section : sections) {
      i++;
      String where = "task.sections[" + i + "]";
      if (!section.isObject()) {
        errors.add(where + " must be an object");
        continue;
      }
      for (String name : names(section)) {
        if (!Set.of("title", "description", "fields").contains(name)) {
          errors.add("unknown " + where + " key '" + name + "'");
        }
      }
      if (section.get("title") == null || section.get("title").isNull()) {
        errors.add(where + ".title is required");
      } else {
        checkI18nMap(section.get("title"), MAX_TITLE_VALUE, where + ".title", errors);
      }
      if (section.has("description") && !section.get("description").isNull()) {
        checkI18nMap(section.get("description"), MAX_TITLE_VALUE, where + ".description", errors);
      }
      JsonNode refs = section.get("fields");
      if (refs == null || !refs.isArray() || refs.isEmpty()) {
        errors.add(where + ".fields must reference at least one task field name");
        continue;
      }
      for (JsonNode ref : refs) {
        String refName = stringOrNull(textOrNull(ref));
        if (refName == null || !fieldNames.contains(refName)) {
          errors.add(where + " references unknown field '" + refName + "'");
        }
      }
    }
  }

  /**
   * I18n map check: object, keys are language codes, {@code en} required, values bounded strings.
   */
  static void checkI18nMap(JsonNode map, int maxValue, String where, List<String> errors) {
    if (map == null || !map.isObject()) {
      errors.add(where + " must be an i18n object with 'en'");
      return;
    }
    for (String name : names(map)) {
      if (!name.matches(Keys.LANG_CODE_RE)) {
        errors.add(where + "." + name + " is not a language key");
      }
    }
    checkI18nValue(map.get("en"), where + ".en", errors);
    if (maxValue <= 0) {
      return;
    }
    for (String name : names(map)) {
      String s = stringOrNull(textOrNull(map.get(name)));
      if (s != null && s.length() > maxValue) {
        errors.add(where + "." + name + " exceeds " + maxValue + " chars");
      }
    }
  }

  private static void checkI18nValue(JsonNode value, String where, List<String> errors) {
    String s = stringOrNull(textOrNull(value));
    if (s == null || s.isBlank()) {
      errors.add(where + " must be a non-blank string");
    }
  }

  private int checkStringArray(
      JsonNode array, int max, int maxValue, String where, List<String> errors) {
    if (array == null || array.isNull()) {
      return 0;
    }
    if (!array.isArray()) {
      errors.add(where + " must be an array of strings");
      return 0;
    }
    if (array.size() > max) {
      errors.add(where + " exceeds " + max + " entries");
      return array.size();
    }
    for (JsonNode item : array) {
      String s = stringOrNull(textOrNull(item));
      if (s == null || s.isBlank() || s.length() > maxValue) {
        errors.add(where + " entries must be non-blank strings of at most " + maxValue + " chars");
        break;
      }
    }
    return array.size();
  }

  static Instant instantOrNull(JsonNode node) {
    if (node == null || node.isNull() || !node.isString()) {
      return null;
    }
    try {
      return Instant.parse(node.asString());
    } catch (DateTimeParseException e) {
      return null;
    }
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
