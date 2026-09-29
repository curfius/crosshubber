package com.crosshubber.portal.modules.registry.manifest;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.common.Keys;
import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.modules.registry.dto.SecurityRoleDto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Manifest schema validation — strict allow-list of top-level keys, per-type entry rules, and
 * domain checks (key/name/baseUrl, at-least-one entry, portal-origin recursion guard). Issues use
 * {@code "path: message"} strings, surfaced verbatim by the registry UI.
 */
@Service
public class ManifestValidator {

  private static final List<String> TOP_LEVEL_KEYS =
      List.of(
          "manifestVersion",
          "key",
          "name",
          "baseUrl",
          "health",
          "content",
          "userSettingsSchema",
          "adminSettingsSchema",
          "security",
          "capabilities",
          "events",
          "agentContributions");
  private static final Map<String, String> CATEGORY_MAP =
      Map.of(
          "applications", "applications",
          "features", "features",
          "adminSettings", "admin-settings",
          "userSettings", "user-settings");

  private final PortalProperties props;
  private final ObjectMapper objectMapper;

  public ManifestValidator(PortalProperties props, ObjectMapper objectMapper) {
    this.props = props;
    this.objectMapper = objectMapper;
  }

  // ── parseManifest ────────────────────────────────────────────────────

  /** Parses + normalizes a manifest; returns issues when invalid. */
  public Result parse(JsonNode raw) {
    List<String> issues = new ArrayList<>();
    if (raw == null || !raw.isObject()) {
      issues.add("(root): expected an object");
      return Result.fail(issues);
    }
    ObjectNode manifest = (ObjectNode) raw.deepCopy();

    if (!manifest.has("manifestVersion") || manifest.get("manifestVersion").asInt(-1) != 1) {
      issues.add("manifestVersion: must be 1");
    }
    if (!matches(manifest.path("key"), Keys.KEY_RE)) {
      issues.add("key: must match [a-z0-9][a-z0-9-]{0,63}");
    }
    if (!nonEmptyString(manifest.path("name"))) {
      issues.add("name: Required");
    }
    if (!isUrl(manifest.path("baseUrl"))) {
      issues.add("baseUrl: must be a valid url");
    }
    if (manifest.hasNonNull("health") && !manifest.path("health").asString().startsWith("/")) {
      issues.add("health: must start with \"/\"");
    }
    for (String key : manifest.propertyNames()) {
      if (!TOP_LEVEL_KEYS.contains(key)) {
        issues.add(key + ": unknown field");
      }
    }

    // content — default all groups to []
    ObjectNode content =
        manifest.has("content") && manifest.get("content").isObject()
            ? (ObjectNode) manifest.get("content")
            : objectMapper.createObjectNode();
    for (String group : CATEGORY_MAP.keySet()) {
      if (!content.has(group) || !content.get(group).isArray()) {
        content.set(group, objectMapper.createArrayNode());
      }
    }
    manifest.set("content", content);

    // capabilities, events, agentContributions defaults
    if (!manifest.has("capabilities") || !manifest.get("capabilities").isArray()) {
      manifest.set("capabilities", objectMapper.createArrayNode());
    }
    if (!manifest.has("events") || !manifest.get("events").isObject()) {
      ObjectNode events = objectMapper.createObjectNode();
      events.set("published", objectMapper.createArrayNode());
      events.set("consumed", objectMapper.createArrayNode());
      manifest.set("events", events);
    } else {
      ObjectNode events = (ObjectNode) manifest.get("events");
      if (!events.has("published") || !events.get("published").isArray()) {
        events.set("published", objectMapper.createArrayNode());
      }
      if (!events.has("consumed") || !events.get("consumed").isArray()) {
        events.set("consumed", objectMapper.createArrayNode());
      }
    }
    if (!manifest.has("agentContributions") || !manifest.get("agentContributions").isObject()) {
      ObjectNode agent = objectMapper.createObjectNode();
      agent.set("tools", objectMapper.createArrayNode());
      agent.set("skills", objectMapper.createArrayNode());
      agent.set("agents", objectMapper.createArrayNode());
      agent.set("knowledge", objectMapper.createArrayNode());
      manifest.set("agentContributions", agent);
    } else {
      ObjectNode agent = (ObjectNode) manifest.get("agentContributions");
      normalizeArray(agent, "tools");
      normalizeArray(agent, "skills");
      normalizeArray(agent, "agents");
      normalizeArray(agent, "knowledge");
      validateTools((ArrayNode) agent.get("tools"), issues);
      validateSkills((ArrayNode) agent.get("skills"), issues);
      validateAgents((ArrayNode) agent.get("agents"), issues);
      validateKnowledge((ArrayNode) agent.get("knowledge"), issues);
    }

    Set<String> seenKeys = new LinkedHashSet<>();
    for (Map.Entry<String, String> groupEntry : CATEGORY_MAP.entrySet()) {
      ArrayNode entries = (ArrayNode) content.get(groupEntry.getKey());
      for (int i = 0; i < entries.size(); i++) {
        JsonNode entry = entries.get(i);
        String prefix = "content." + groupEntry.getKey() + "[" + i + "]";
        validateEntry(entry, prefix, issues);
        String key = entry.path("key").asString(null);
        if (key != null) {
          if (!seenKeys.add(key)) {
            issues.add("content: duplicate entry key \"" + key + "\" across content groups");
          }
        }
      }
    }

    // security.roles — default []
    if (!manifest.has("security") || !manifest.get("security").isObject()) {
      ObjectNode security = objectMapper.createObjectNode();
      security.set("roles", objectMapper.createArrayNode());
      manifest.set("security", security);
    } else {
      ObjectNode security = (ObjectNode) manifest.get("security");
      if (!security.has("roles") || !security.get("roles").isArray()) {
        security.set("roles", objectMapper.createArrayNode());
      }
      ArrayNode roles = (ArrayNode) security.get("roles");
      for (int i = 0; i < roles.size(); i++) {
        JsonNode role = roles.get(i);
        if (!matches(role.path("key"), Keys.KEY_RE)) {
          issues.add("security.roles[" + i + "].key: must match [a-z0-9][a-z0-9-]{0,63}");
        }
        if (!nonEmptyString(role.path("name"))) {
          issues.add("security.roles[" + i + "].name: required");
        }
      }
    }

    if (!issues.isEmpty()) {
      return Result.fail(issues);
    }
    return Result.ok(manifest);
  }

  private void validateEntry(JsonNode entry, String prefix, List<String> issues) {
    if (!entry.isObject()) {
      issues.add(prefix + ": must be an object");
      return;
    }
    if (!matches(entry.path("key"), Keys.KEY_RE)) {
      issues.add(prefix + ".key: must match [a-z0-9][a-z0-9-]{0,63}");
    }
    if (!nonEmptyString(entry.path("name"))) {
      issues.add(prefix + ".name: required");
    }
    String type = entry.path("type").asString(null);
    if (type == null || !Keys.TYPES.contains(type)) {
      issues.add(prefix + ".type: must be iframe|embedded|mfe|link");
      return;
    }
    boolean hasUrl = nonEmptyString(entry.path("url"));
    boolean hasPath = entry.has("path") && entry.path("path").asString().startsWith("/");
    boolean hasEntryUrl = nonEmptyString(entry.path("entryUrl"));
    switch (type) {
      case "iframe", "link" -> {
        if (!hasUrl && !hasPath) {
          issues.add(prefix + "." + type + " requires \"url\" or \"path\"");
        }
      }
      case "embedded" -> {
        if (!nonEmptyString(entry.path("loadPath"))) {
          issues.add(prefix + ".embedded requires \"loadPath\"");
        }
      }
      case "mfe" -> {
        if (!matches(entry.path("element"), Keys.ELEMENT_RE)) {
          issues.add(prefix + ".mfe requires \"element\"");
        }
        if (!hasEntryUrl && !hasPath) {
          issues.add(prefix + ".mfe requires \"entryUrl\" or \"path\"");
        }
      }
      default -> {
        // unreachable
      }
    }
    if (entry.has("sandbox") && !entry.get("sandbox").isArray()) {
      issues.add(prefix + ".sandbox: must be an array of strings");
    }
  }

  // ── validateManifest (install path) ────────────────────────────

  /** Domain validation: portal-origin recursion guards + content presence. */
  public List<String> validateDomain(JsonNode manifest) {
    List<String> errors = new ArrayList<>();
    if (!nonEmptyString(manifest.path("key"))) {
      errors.add("key: Module key is required");
    }
    if (!nonEmptyString(manifest.path("name"))) {
      errors.add("name: Module name is required");
    }
    if (!nonEmptyString(manifest.path("baseUrl"))) {
      errors.add("baseUrl: Base URL is required");
    }
    boolean hasContent = false;
    for (String group : CATEGORY_MAP.keySet()) {
      if (manifest.path("content").path(group).isArray()
          && manifest.path("content").path(group).size() > 0) {
        hasContent = true;
      }
    }
    if (!hasContent) {
      errors.add("content: At least one content entry is required");
    }
    String baseUrl = manifest.path("baseUrl").asString("");
    for (FlatEntry flat : flattenEntries(manifest)) {
      JsonNode entry = flat.entry();
      String url = resolveUrl(entry, baseUrl);
      String entryUrl = resolveEntryUrl(entry, baseUrl);
      String candidate = "mfe".equals(entry.path("type").asString()) ? entryUrl : url;
      if (candidate != null && isPortalOrigin(candidate)) {
        errors.add(
            "content."
                + entry.path("key").asString()
                + ": Entry \""
                + entry.path("key").asString()
                + "\" resolves to portal origin ("
                + candidate
                + ")"
                + " — would cause infinite iframe recursion");
      }
    }
    return errors;
  }

  // ── Entry helpers ───────────────────────

  public record FlatEntry(JsonNode entry, String category) {}

  /** Flattens content entries with their DB category. */
  public List<FlatEntry> flattenEntries(JsonNode manifest) {
    List<FlatEntry> result = new ArrayList<>();
    JsonNode content = manifest.path("content");
    for (Map.Entry<String, String> groupEntry : CATEGORY_MAP.entrySet()) {
      JsonNode entries = content.path(groupEntry.getKey());
      if (entries.isArray()) {
        for (JsonNode entry : entries) {
          result.add(new FlatEntry(entry, groupEntry.getValue()));
        }
      }
    }
    return result;
  }

  public String resolveUrl(JsonNode entry, String baseUrl) {
    if (nonEmptyString(entry.path("url"))) {
      return entry.path("url").asString();
    }
    if (nonEmptyString(entry.path("path"))) {
      return stripTrailingSlash(baseUrl) + entry.path("path").asString();
    }
    return null;
  }

  public String resolveEntryUrl(JsonNode entry, String baseUrl) {
    if (nonEmptyString(entry.path("entryUrl"))) {
      return entry.path("entryUrl").asString();
    }
    if (nonEmptyString(entry.path("path"))) {
      return stripTrailingSlash(baseUrl) + entry.path("path").asString();
    }
    return null;
  }

  private boolean isPortalOrigin(String rawUrl) {
    try {
      URI portal = URI.create(props.getPublicBaseUrl());
      URI candidate = rawUrl.startsWith("/") ? portal.resolve(rawUrl) : URI.create(rawUrl);
      return candidate.getScheme() != null
          && candidate.getScheme().equalsIgnoreCase(portal.getScheme())
          && candidate.getHost() != null
          && candidate.getHost().equalsIgnoreCase(portal.getHost())
          && effectivePort(candidate) == effectivePort(portal);
    } catch (Exception e) {
      return false;
    }
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  // ── agentContributions v2 ────────────────────────────────────────────

  private static final List<String> KNOWLEDGE_KINDS = List.of("markdown", "url", "docsource");

  private void normalizeArray(ObjectNode parent, String field) {
    if (!parent.has(field) || !parent.get(field).isArray()) {
      parent.set(field, objectMapper.createArrayNode());
    }
  }

  /**
   * Validates {@code tools[]} entries: {@code {name, description, arguments (JSON Schema object),
   * mutates, roles[], path}}. Unknown nested fields are ignored (validator style — only top-level
   * fields have an allow-list).
   */
  private void validateTools(ArrayNode tools, List<String> issues) {
    Set<String> seen = new LinkedHashSet<>();
    for (int i = 0; i < tools.size(); i++) {
      JsonNode tool = tools.get(i);
      String prefix = "agentContributions.tools[" + i + "]";
      if (!tool.isObject()) {
        issues.add(prefix + ": must be an object");
        continue;
      }
      if (!matches(tool.path("name"), Keys.AGENT_NAME_RE)) {
        issues.add(prefix + ".name: must match [a-z][a-z0-9_-]*");
      } else if (!seen.add(tool.path("name").asString())) {
        issues.add(prefix + ".name: duplicate tool name \"" + tool.path("name").asString() + "\"");
      }
      if (!nonEmptyString(tool.path("description"))) {
        issues.add(prefix + ".description: required");
      }
      if (tool.has("arguments") && !tool.get("arguments").isObject()) {
        issues.add(prefix + ".arguments: must be an object (JSON Schema)");
      }
      if (tool.has("mutates") && !tool.get("mutates").isBoolean()) {
        issues.add(prefix + ".mutates: must be a boolean");
      }
      validateRoleArray(tool, prefix, issues);
      if (tool.hasNonNull("path") && !tool.path("path").asString().startsWith("/")) {
        issues.add(prefix + ".path: must start with \"/\"");
      }
    }
  }

  /** Validates {@code skills[]} entries: {@code {name, description, prompts[] | promptRef}}. */
  private void validateSkills(ArrayNode skills, List<String> issues) {
    for (int i = 0; i < skills.size(); i++) {
      JsonNode skill = skills.get(i);
      String prefix = "agentContributions.skills[" + i + "]";
      if (!skill.isObject()) {
        issues.add(prefix + ": must be an object");
        continue;
      }
      if (!matches(skill.path("name"), Keys.AGENT_NAME_RE)) {
        issues.add(prefix + ".name: must match [a-z][a-z0-9_-]*");
      }
      if (!nonEmptyString(skill.path("description"))) {
        issues.add(prefix + ".description: required");
      }
      if (skill.has("prompts")) {
        JsonNode prompts = skill.get("prompts");
        boolean allStrings = prompts.isArray() && prompts.size() > 0;
        if (prompts.isArray()) {
          for (JsonNode prompt : prompts) {
            if (!prompt.isString() || prompt.asString().isBlank()) {
              allStrings = false;
            }
          }
        }
        if (!allStrings) {
          issues.add(prefix + ".prompts: must be a non-empty array of strings");
        }
      }
      if (skill.hasNonNull("promptRef") && !nonEmptyString(skill.path("promptRef"))) {
        issues.add(prefix + ".promptRef: must be a non-empty string");
      }
    }
  }

  /**
   * Validates {@code agents[]} entries (sub-agents): {@code {name, description, endpoint,
   * roles[]}}.
   */
  private void validateAgents(ArrayNode agents, List<String> issues) {
    for (int i = 0; i < agents.size(); i++) {
      JsonNode agentEntry = agents.get(i);
      String prefix = "agentContributions.agents[" + i + "]";
      if (!agentEntry.isObject()) {
        issues.add(prefix + ": must be an object");
        continue;
      }
      if (!matches(agentEntry.path("name"), Keys.AGENT_NAME_RE)) {
        issues.add(prefix + ".name: must match [a-z][a-z0-9_-]*");
      }
      if (!nonEmptyString(agentEntry.path("description"))) {
        issues.add(prefix + ".description: required");
      }
      if (!nonEmptyString(agentEntry.path("endpoint"))
          || !agentEntry.path("endpoint").asString().startsWith("/")) {
        issues.add(prefix + ".endpoint: must start with \"/\"");
      }
      validateRoleArray(agentEntry, prefix, issues);
    }
  }

  /** Validates {@code knowledge[]} entries: {@code {id, title, kind, ref}}. */
  private void validateKnowledge(ArrayNode knowledge, List<String> issues) {
    for (int i = 0; i < knowledge.size(); i++) {
      JsonNode doc = knowledge.get(i);
      String prefix = "agentContributions.knowledge[" + i + "]";
      if (!doc.isObject()) {
        issues.add(prefix + ": must be an object");
        continue;
      }
      if (!matches(doc.path("id"), Keys.KEY_RE)) {
        issues.add(prefix + ".id: must match [a-z0-9][a-z0-9-]{0,63}");
      }
      if (!nonEmptyString(doc.path("title"))) {
        issues.add(prefix + ".title: required");
      }
      String kind = doc.path("kind").asString(null);
      if (kind == null || !KNOWLEDGE_KINDS.contains(kind)) {
        issues.add(prefix + ".kind: must be markdown|url|docsource");
      }
      if (!nonEmptyString(doc.path("ref"))) {
        issues.add(prefix + ".ref: required");
      }
    }
  }

  /** {@code roles}, when present, must be an array of kebab-case keys. */
  private void validateRoleArray(JsonNode node, String prefix, List<String> issues) {
    if (!node.has("roles")) {
      return;
    }
    JsonNode roles = node.get("roles");
    if (!roles.isArray()) {
      issues.add(prefix + ".roles: must be an array of strings");
      return;
    }
    for (int r = 0; r < roles.size(); r++) {
      if (!matches(roles.get(r), Keys.KEY_RE)) {
        issues.add(prefix + ".roles[" + r + "]: must match [a-z0-9][a-z0-9-]{0,63}");
      }
    }
  }

  // ── Shared helpers ───────────────────────────────────────────────────

  private static boolean matches(JsonNode node, String regex) {
    return node.isString() && node.asString().matches(regex);
  }

  private static boolean nonEmptyString(JsonNode node) {
    return node.isString() && !node.asString().isBlank();
  }

  private static boolean isUrl(JsonNode node) {
    if (!node.isString()) {
      return false;
    }
    try {
      URI uri = URI.create(node.asString());
      return uri.getScheme() != null && uri.getHost() != null;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static String stripTrailingSlash(String url) {
    return url.replaceAll("/+$", "");
  }

  public record Result(boolean ok, JsonNode manifest, List<String> issues) {

    static Result ok(JsonNode manifest) {
      return new Result(true, manifest, List.of());
    }

    static Result fail(List<String> issues) {
      return new Result(false, null, issues);
    }
  }

  /** Joins issues into a single semicolon-separated string. */
  public static String formatIssues(List<String> issues) {
    return String.join("; ", issues);
  }

  /** Declared roles ({@code security.roles[]}) of a manifest. */
  public List<SecurityRoleDto> declaredRoles(JsonNode manifest) {
    List<SecurityRoleDto> roles = new ArrayList<>();
    JsonNode array = manifest.path("security").path("roles");
    if (array.isArray()) {
      for (JsonNode role : array) {
        roles.add(
            new SecurityRoleDto(
                role.path("key").asString(),
                role.path("name").asString(),
                role.hasNonNull("description") ? role.get("description").asString() : null));
      }
    }
    return roles;
  }
}
