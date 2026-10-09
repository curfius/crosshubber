package com.crosshubber.portal.modules.navigation;

import java.text.Collator;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.crosshubber.portal.common.Keys;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentCategory;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentType;

import tools.jackson.databind.JsonNode;

/** Pure navigation tree validators + default builders (no DB access). */
public final class NavigationValidationService {

  private NavigationValidationService() {}

  public static final int MAX_PINNED_DEPTH = 3;
  public static final int MAX_SHELL_DEPTH = 3;

  /**
   * Deterministic name-tiebreak collator. Locale.ROOT keeps ordering stable across servers and JVM
   * default locales.
   */
  private static final Collator NAME_COLLATOR = Collator.getInstance(Locale.ROOT);

  public record Validation(boolean success, String error) {

    public static Validation ok() {
      return new Validation(true, null);
    }

    public static Validation fail(String error) {
      return new Validation(false, error);
    }
  }

  public static String entryPointRef(ModuleContentEntity ep) {
    return ep.getModuleKey() + ":" + ep.getContentKey();
  }

  // — Pinned apps tree
  // —

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
      // id: optional, string, <=64 chars (numeric ids must not be coerced)
      JsonNode id = node.get("id");
      if (id != null && !id.isNull()) {
        if (!id.isString()) {
          return Validation.fail("id: Invalid input: expected string, received " + jsonType(id));
        }
        if (id.asString().length() > 64) {
          return Validation.fail("id: Too big: expected string to have <=64 characters");
        }
      }
      String nodeType = node.path("nodeType").asString(null);
      if (!"folder".equals(nodeType) && !"item".equals(nodeType)) {
        return Validation.fail("nodeType must be \"folder\" or \"item\"");
      }
      if ("folder".equals(nodeType)) {
        if (!nonBlank(node.path("name"))) {
          return Validation.fail("folders require a name");
        }
        if (node.path("name").asString().length() > 256) {
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
        String ref = node.path("ref").asString(null);
        if (ref == null || !ref.matches(Keys.REF_RE)) {
          return Validation.fail("items require a ref of the form \"moduleKey:contentKey\"");
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

  /** JSON type name for "expected X, received Y" messages. */
  private static String jsonType(JsonNode node) {
    if (node.isString()) {
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

  // — Shell nav trees (groups + items)
  // —

  public record ShellGroup(String groupKey, String name, String parentKey) {}

  public record ShellItem(String moduleKey, String contentKey, String groupKey) {}

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
                + item.contentKey()
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

  // — Computed defaults (D5 / D12)
  // —

  public static boolean isDefaultSidebarApp(ModuleContentEntity ep) {
    return ep.getCategory() == ModuleContentCategory.APPLICATIONS
        && ep.getType() != ModuleContentType.LINK
        && Boolean.TRUE.equals(ep.getActive());
  }

  /** D12: every visible app module content, ordered by sortOrder then name. */
  public static List<String> buildDefaultSidebarApps(List<ModuleContentEntity> moduleContents) {
    return moduleContents.stream()
        .filter(NavigationValidationService::isDefaultSidebarApp)
        .sorted(
            Comparator.comparingInt((ModuleContentEntity ep) -> ep.getSortOrder())
                .thenComparing(ep -> ep.getName(), NAME_COLLATOR))
        .map(NavigationValidationService::entryPointRef)
        .toList();
  }

  private static boolean nonBlank(JsonNode node) {
    return node.isString() && !node.asString().isBlank();
  }
}
