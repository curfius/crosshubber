package com.crosshubber.portal.modules.navigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.crosshubber.portal.modules.registry.entrypoints.EntryPointEntity;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pure navigation tree validators + default builders — mirrors {@code
 * portal/src/modules/navigation/navigation.service.ts} (no DB access).
 */
public final class NavigationValidationService {

  private NavigationValidationService() {}

  public static final int MAX_PINNED_DEPTH = 3;
  public static final int MAX_SHELL_DEPTH = 3;

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
      String nodeType = node.path("nodeType").asText(null);
      if (!"folder".equals(nodeType) && !"item".equals(nodeType)) {
        return Validation.fail("nodeType must be \"folder\" or \"item\"");
      }
      if ("folder".equals(nodeType)) {
        if (!nonBlank(node.path("name"))) {
          return Validation.fail("folders require a name");
        }
        if (node.has("ref") && !node.path("ref").isNull()) {
          return Validation.fail("folders must not carry a ref");
        }
        JsonNode children = node.get("children");
        if (children != null && !children.isNull() && !children.isArray()) {
          return Validation.fail("children must be an array");
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
        if (!knownRefs.contains(ref)) {
          return Validation.fail("unknown app ref \"" + ref + "\"");
        }
        JsonNode children = node.get("children");
        if (children != null && !children.isNull() && children.isArray() && !children.isEmpty()) {
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

  // ── Portal Navigation layout ─────────────────────────────────────────

  public static Validation validateLayout(JsonNode layout, Set<String> knownRefs) {
    if (layout == null || !layout.isObject()) {
      return Validation.fail("layout must be an object");
    }
    if (!(layout.path("pinnedSectionEnabled").isBoolean())) {
      return Validation.fail("pinnedSectionEnabled must be a boolean");
    }
    JsonNode sections = layout.path("sections");
    if (!sections.isArray()) {
      return Validation.fail("sections must be an array");
    }
    Set<String> seenIds = new LinkedHashSet<>();
    Set<String> seenRefs = new LinkedHashSet<>();
    return walkLayout(sections, 0, knownRefs, seenIds, seenRefs);
  }

  private static Validation walkLayout(
      JsonNode nodes, int depth, Set<String> knownRefs, Set<String> seenIds, Set<String> seenRefs) {
    for (JsonNode node : nodes) {
      if (node == null || !node.isObject()) {
        return Validation.fail("layout nodes must be objects");
      }
      String id = node.path("id").asText(null);
      if (id == null || id.isBlank()) {
        return Validation.fail("layout nodes require an id");
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
                .thenComparing(EntryPointEntity::getName))
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
                    .thenComparing(EntryPointEntity::getName))
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
