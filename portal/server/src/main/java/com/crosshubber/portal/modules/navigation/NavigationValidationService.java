package com.crosshubber.portal.modules.navigation;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.crosshubber.portal.modules.registry.entrypoints.EntryPointEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure navigation tree validators + default builders — mirrors {@code
 * portal/src/modules/navigation/navigation.service.ts} (no DB access).
 */
public final class NavigationValidationService {

  private NavigationValidationService() {}

  public static final int MAX_PINNED_DEPTH = 3;
  public static final int MAX_SHELL_DEPTH = 3;

  /** Zod parity: sections/children arrays accept at most 500 items. */
  public static final int MAX_LAYOUT_SECTIONS = 500;

  /**
   * JS {@code localeCompare} parity for name tiebreaks (ICU collation). Locale.ROOT is the closest
   * deterministic match — JS itself is locale-dependent.
   */
  private static final Collator NAME_COLLATOR = Collator.getInstance(Locale.ROOT);

  /** Mirrors REF_RE in navigation.service.ts. */
  public static final String REF_RE = "^[a-z0-9][a-z0-9-]{0,63}:[a-z0-9][a-z0-9-]{0,63}$";

  public record Validation(boolean success, String error) {

    public static Validation ok() {
      return new Validation(true, null);
    }

    public static Validation fail(String error) {
      return new Validation(false, error);
    }
  }

  public static String entryPointRef(EntryPointEntity ep) {
    return ep.getModuleKey() + ":" + ep.getEntryKey();
  }

  // ── Pinned apps tree ─────────────────────────────────────────────────

  public static Validation validatePinnedTree(JsonNode nodes, Set<String> knownRefs) {
    Set<String> seenRefs = new LinkedHashSet<>();
    return walkPinned(nodes, 1, knownRefs, seenRefs);
  }

  private static Validation walkPinned(
      JsonNode list, int depth, Set<String> knownRefs, Set<String> seenRefs) {
    if (depth > MAX_PINNED_DEPTH) {
      return Validation.fail("pinned tree exceeds max depth " + MAX_PINNED_DEPTH);
    }
    if (list == null || !list.isArray()) {
      return Validation.fail("tree node children must be an array");
    }
    for (JsonNode node : list) {
      if (node == null || !node.isObject()) {
        return Validation.fail("tree nodes must be objects");
      }
      // id: optional, string, <=64 chars (zod parity — numeric ids must not be coerced)
      JsonNode id = node.get("id");
      if (id != null && !id.isNull()) {
        if (!id.isTextual()) {
          return Validation.fail("id: Invalid input: expected string, received " + jsonType(id));
        }
        if (id.asText().length() > 64) {
          return Validation.fail("id: Too big: expected string to have <=64 characters");
        }
      }
      String nodeType = node.path("nodeType").asText(null);
      if (!"folder".equals(nodeType) && !"item".equals(nodeType)) {
        return Validation.fail("nodeType must be \"folder\" or \"item\"");
      }
      if ("folder".equals(nodeType)) {
        if (!nonBlank(node.path("name"))) {
          return Validation.fail("folders require a name");
        }
        if (node.path("name").asText().length() > 256) {
          return Validation.fail("name: Too big: expected string to have <=256 characters");
        }
        if (node.has("ref")) {
          return Validation.fail("folders must not carry a ref");
        }
        JsonNode children = node.get("children");
        if (children != null && !children.isNull() && !children.isArray()) {
          return Validation.fail(
              "children: Invalid input: expected array, received " + jsonType(children));
        }
        Validation res =
            walkPinned(
                children == null || children.isNull() ? null : children,
                depth + 1,
                knownRefs,
                seenRefs);
        if (!res.success()) {
          return res;
        }
      } else {
        String ref = node.path("ref").asText(null);
        if (ref == null || !ref.matches(REF_RE)) {
          return Validation.fail("items require a ref of the form \"moduleKey:entryKey\"");
        }
        if (ref.length() > 255) {
          return Validation.fail("ref: Too big: expected string to have <=255 characters");
        }
        if (!knownRefs.contains(ref)) {
          return Validation.fail("unknown app ref \"" + ref + "\"");
        }
        JsonNode children = node.get("children");
        if (children != null && !children.isNull() && !children.isArray()) {
          return Validation.fail(
              "children: Invalid input: expected array, received " + jsonType(children));
        }
        if (children != null && children.isArray() && !children.isEmpty()) {
          return Validation.fail("items must not have children");
        }
        if (seenRefs.contains(ref)) {
          return Validation.fail("duplicate app ref \"" + ref + "\"");
        }
        seenRefs.add(ref);
      }
    }
    return Validation.ok();
  }

  /** Zod-style JSON type name for "expected X, received Y" messages. */
  private static String jsonType(JsonNode node) {
    if (node.isTextual()) {
      return "string";
    }
    if (node.isNumber()) {
      return "number";
    }
    if (node.isBoolean()) {
      return "boolean";
    }
    if (node.isArray()) {
      return "array";
    }
    if (node.isObject()) {
      return "object";
    }
    return "null";
  }

  // ── Portal Navigation layout ─────────────────────────────────────────

  /** Strips unknown keys from a layout payload before persisting (zod strict-schema parity). */
  public static JsonNode stripLayout(JsonNode layout) {
    if (layout == null || !layout.isObject()) {
      return layout;
    }
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    if (layout.has("pinnedSectionEnabled")) {
      out.set("pinnedSectionEnabled", layout.get("pinnedSectionEnabled").deepCopy());
    }
    JsonNode sections = layout.path("sections");
    if (sections.isArray()) {
      ArrayNode arr = JsonNodeFactory.instance.arrayNode();
      for (JsonNode section : sections) {
        arr.add(stripLayoutNode(section));
      }
      out.set("sections", arr);
    }
    return out;
  }

  private static JsonNode stripLayoutNode(JsonNode node) {
    if (node == null || !node.isObject()) {
      return node;
    }
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    for (String key : List.of("id", "type", "name", "ref")) {
      if (node.has(key)) {
        out.set(key, node.get(key).deepCopy());
      }
    }
    JsonNode children = node.path("children");
    if (children.isArray()) {
      ArrayNode arr = JsonNodeFactory.instance.arrayNode();
      for (JsonNode child : children) {
        arr.add(stripLayoutNode(child));
      }
      out.set("children", arr);
    }
    return out;
  }

  public static Validation validateLayout(JsonNode layout, Set<String> knownRefs) {
    if (layout == null || !layout.isObject()) {
      return Validation.fail("layout must be an object");
    }
    if (!(layout.path("pinnedSectionEnabled").isBoolean())) {
      return Validation.fail(
          "pinnedSectionEnabled: Invalid input: expected boolean, received undefined");
    }
    JsonNode sections = layout.path("sections");
    if (!sections.isArray()) {
      return Validation.fail("sections: Invalid input: expected array, received undefined");
    }
    if (sections.size() > MAX_LAYOUT_SECTIONS) {
      return Validation.fail("sections: Too big: expected array to have <=500 items");
    }
    Set<String> seenIds = new LinkedHashSet<>();
    Set<String> seenRefs = new LinkedHashSet<>();
    return walkLayout(sections, 0, knownRefs, seenIds, seenRefs);
  }

  private static Validation walkLayout(
      JsonNode nodes, int depth, Set<String> knownRefs, Set<String> seenIds, Set<String> seenRefs) {
    if (nodes.size() > MAX_LAYOUT_SECTIONS) {
      return Validation.fail("children: Too big: expected array to have <=500 items");
    }
    for (JsonNode node : nodes) {
      if (node == null || !node.isObject()) {
        return Validation.fail("layout nodes must be objects");
      }
      String id = node.path("id").asText(null);
      if (id == null || id.isBlank()) {
        return Validation.fail("layout nodes require an id");
      }
      if (id.length() > 128) {
        return Validation.fail("id: Too big: expected string to have <=128 characters");
      }
      if (seenIds.contains(id)) {
        return Validation.fail("duplicate layout node id \"" + id + "\"");
      }
      seenIds.add(id);
      if ("item".equals(node.path("type").asText(null))) {
        String ref = node.path("ref").asText(null);
        if (ref == null || !ref.matches(REF_RE)) {
          return Validation.fail("layout items require a ref of the form \"moduleKey:entryKey\"");
        }
        if (ref.length() > 255) {
          return Validation.fail("ref: Too big: expected string to have <=255 characters");
        }
        if (!knownRefs.contains(ref)) {
          return Validation.fail("unknown app ref \"" + ref + "\"");
        }
        if (seenRefs.contains(ref)) {
          return Validation.fail("duplicate app ref \"" + ref + "\"");
        }
        seenRefs.add(ref);
      } else {
        if (!nonBlank(node.path("name"))) {
          return Validation.fail("sections require a name");
        }
        if (node.path("name").asText().length() > 256) {
          return Validation.fail("name: Too big: expected string to have <=256 characters");
        }
        JsonNode children = node.path("children");
        if (!children.isArray()) {
          return Validation.fail("section children must be an array");
        }
        Validation res = walkLayout(children, depth + 1, knownRefs, seenIds, seenRefs);
        if (!res.success()) {
          return res;
        }
      }
    }
    return Validation.ok();
  }

  // ── Shell nav trees (groups + items) ─────────────────────────────────

  public record ShellGroup(String groupKey, String name, String parentKey) {}

  public record ShellItem(String moduleKey, String entryKey, String groupKey) {}

  public static Validation validateShellTree(List<ShellGroup> groups, List<ShellItem> items) {
    Map<String, ShellGroup> byKey = new LinkedHashMap<>();
    for (ShellGroup g : groups) {
      if (g.groupKey() != null) {
        if (byKey.containsKey(g.groupKey())) {
          return Validation.fail("duplicate groupKey \"" + g.groupKey() + "\" in payload");
        }
        byKey.put(g.groupKey(), g);
      }
    }
    for (ShellGroup g : groups) {
      if (g.parentKey() != null && !byKey.containsKey(g.parentKey())) {
        return Validation.fail("unknown parent group \"" + g.parentKey() + "\"");
      }
    }
    // Cycle + depth guard
    for (String key : byKey.keySet()) {
      int depth = depthOf(key, byKey, new LinkedHashSet<>());
      if (depth < 0) {
        return Validation.fail("group hierarchy contains a cycle");
      }
      if (depth > MAX_SHELL_DEPTH) {
        return Validation.fail("group nesting exceeds max depth " + MAX_SHELL_DEPTH);
      }
    }
    for (ShellItem item : items) {
      if (item.groupKey() != null && !byKey.containsKey(item.groupKey())) {
        return Validation.fail(
            "item "
                + item.moduleKey()
                + ":"
                + item.entryKey()
                + " references unknown group \""
                + item.groupKey()
                + "\"");
      }
    }
    return Validation.ok();
  }

  private static int depthOf(String key, Map<String, ShellGroup> byKey, Set<String> guard) {
    if (guard.contains(key)) {
      return -1;
    }
    ShellGroup group = byKey.get(key);
    String parent = group == null ? null : group.parentKey();
    if (parent == null) {
      return 1;
    }
    guard.add(key);
    int depth = depthOf(parent, byKey, guard);
    guard.remove(key);
    return depth < 0 ? -1 : depth + 1;
  }

  // ── Computed defaults (D5 / D12) ─────────────────────────────────────

  public static boolean isDefaultSidebarApp(EntryPointEntity ep) {
    return "applications".equals(ep.getCategory())
        && !"link".equals(ep.getType())
        && Boolean.TRUE.equals(ep.getActive());
  }

  /** D12: every visible app entry point, ordered by sortOrder then name. */
  public static List<String> buildDefaultSidebarApps(List<EntryPointEntity> entryPoints) {
    return entryPoints.stream()
        .filter(NavigationValidationService::isDefaultSidebarApp)
        .sorted(
            Comparator.comparingInt(EntryPointEntity::getSortOrder)
                .thenComparing(EntryPointEntity::getName, NAME_COLLATOR))
        .map(NavigationValidationService::entryPointRef)
        .toList();
  }

  /** D5: single "Applications" section listing every app ref by sortOrder. */
  public static Map<String, Object> buildDefaultLayout(List<EntryPointEntity> entryPoints) {
    List<Map<String, Object>> children = new ArrayList<>();
    for (EntryPointEntity ep : entryPoints) {
      if (!isDefaultSidebarApp(ep)) {
        continue;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", "item:" + entryPointRef(ep));
      item.put("type", "item");
      item.put("ref", entryPointRef(ep));
      children.add(item);
    }
    // Reorder deterministically by sortOrder/name
    List<EntryPointEntity> sorted =
        entryPoints.stream()
            .filter(NavigationValidationService::isDefaultSidebarApp)
            .sorted(
                Comparator.comparingInt(EntryPointEntity::getSortOrder)
                    .thenComparing(EntryPointEntity::getName, NAME_COLLATOR))
            .toList();
    children.clear();
    for (EntryPointEntity ep : sorted) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", "item:" + entryPointRef(ep));
      item.put("type", "item");
      item.put("ref", entryPointRef(ep));
      children.add(item);
    }
    Map<String, Object> applications = new LinkedHashMap<>();
    applications.put("id", "applications");
    applications.put("name", "Applications");
    applications.put("children", children);
    Map<String, Object> layout = new LinkedHashMap<>();
    layout.put("pinnedSectionEnabled", true);
    layout.put("sections", List.of(applications));
    return layout;
  }

  private static boolean nonBlank(JsonNode node) {
    return node.isTextual() && !node.asText().isBlank();
  }
}
