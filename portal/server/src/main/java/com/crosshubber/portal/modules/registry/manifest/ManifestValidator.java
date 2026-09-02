package com.crosshubber.portal.modules.registry.manifest;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.config.PortalProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Manifest schema validation — mirrors {@code portal/src/modules/registry/manifest.schema.ts}
 * (parseManifest) plus {@code install.service.ts validateManifest}. Produces the same {@code "path:
 * message"} issue strings.
 */
@Service
public class ManifestValidator {

  private static final String KEY_RE = "^[a-z0-9][a-z0-9-]{0,63}$";
  private static final String ELEMENT_RE = "^[a-z][a-z0-9-]*$";
  private static final List<String> ENTRY_TYPES = List.of("iframe", "embedded", "mfe", "link");
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
    ObjectNode manifest = raw.deepCopy();

    if (!manifest.has("manifestVersion") || manifest.get("manifestVersion").asInt(-1) != 1) {
      issues.add("manifestVersion: must be 1");
    }
    if (!matches(manifest.path("key"), KEY_RE)) {
      issues.add("key: must match [a-z0-9][a-z0-9-]{0,63}");
    }
    if (!nonEmptyString(manifest.path("name"))) {
      issues.add("name: required");
    }
    if (!isUrl(manifest.path("baseUrl"))) {
      issues.add("baseUrl: must be a valid url");
    }
    if (manifest.hasNonNull("health") && !manifest.path("health").asText().startsWith("/")) {
      issues.add("health: must start with \"/\"");
    }
    for (Iterator<String> it = manifest.fieldNames(); it.hasNext(); ) {
      String key = it.next();
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

    Set<String> seenKeys = new LinkedHashSet<>();
    for (Map.Entry<String, String> groupEntry : CATEGORY_MAP.entrySet()) {
      ArrayNode entries = (ArrayNode) content.get(groupEntry.getKey());
      for (int i = 0; i < entries.size(); i++) {
        JsonNode entry = entries.get(i);
        String prefix = "content." + groupEntry.getKey() + "[" + i + "]";
        validateEntry(entry, prefix, issues);
        String key = entry.path("key").asText(null);
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
        if (!matches(role.path("key"), KEY_RE)) {
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
    if (!matches(entry.path("key"), KEY_RE)) {
      issues.add(prefix + ".key: must match [a-z0-9][a-z0-9-]{0,63}");
    }
    if (!nonEmptyString(entry.path("name"))) {
      issues.add(prefix + ".name: required");
    }
    String type = entry.path("type").asText(null);
    if (type == null || !ENTRY_TYPES.contains(type)) {
      issues.add(prefix + ".type: must be iframe|embedded|mfe|link");
      return;
    }
    boolean hasUrl = nonEmptyString(entry.path("url"));
    boolean hasPath = entry.has("path") && entry.path("path").asText().startsWith("/");
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
        if (!matches(entry.path("element"), ELEMENT_RE)) {
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

  // ── validateManifest (install.service.ts) ────────────────────────────

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
    String baseUrl = manifest.path("baseUrl").asText("");
    for (FlatEntry flat : flattenEntries(manifest)) {
      JsonNode entry = flat.entry();
      String url = resolveUrl(entry, baseUrl);
      String entryUrl = resolveEntryUrl(entry, baseUrl);
      String candidate = "mfe".equals(entry.path("type").asText()) ? entryUrl : url;
      if (candidate != null && isPortalOrigin(candidate)) {
        errors.add(
            "content."
                + entry.path("key").asText()
                + ": Entry \""
                + entry.path("key").asText()
                + "\" resolves to portal origin ("
                + candidate
                + ")"
                + " — would cause infinite iframe recursion");
      }
    }
    return errors;
  }

  // ── Entry helpers (mirrors install.service.ts) ───────────────────────

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
      return entry.path("url").asText();
    }
    if (nonEmptyString(entry.path("path"))) {
      return stripTrailingSlash(baseUrl) + entry.path("path").asText();
    }
    return null;
  }

  public String resolveEntryUrl(JsonNode entry, String baseUrl) {
    if (nonEmptyString(entry.path("entryUrl"))) {
      return entry.path("entryUrl").asText();
    }
    if (nonEmptyString(entry.path("path"))) {
      return stripTrailingSlash(baseUrl) + entry.path("path").asText();
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

  // ── Shared helpers ───────────────────────────────────────────────────

  private static boolean matches(JsonNode node, String regex) {
    return node.isTextual() && node.asText().matches(regex);
  }

  private static boolean nonEmptyString(JsonNode node) {
    return node.isTextual() && !node.asText().isBlank();
  }

  private static boolean isUrl(JsonNode node) {
    if (!node.isTextual()) {
      return false;
    }
    try {
      URI uri = URI.create(node.asText());
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

  /** Convenience for issue formatting parity. */
  public static String formatIssues(List<String> issues) {
    return String.join("; ", issues);
  }

  /** Linked map of declared roles (key/name/description). */
  public List<Map<String, Object>> declaredRoles(JsonNode manifest) {
    List<Map<String, Object>> roles = new ArrayList<>();
    JsonNode array = manifest.path("security").path("roles");
    if (array.isArray()) {
      for (JsonNode role : array) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", role.path("key").asText());
        map.put("name", role.path("name").asText());
        if (role.hasNonNull("description")) {
          map.put("description", role.path("description").asText());
        }
        roles.add(map);
      }
    }
    return roles;
  }
}
