package com.crosshubber.portal.modules.navigation.shelltree;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.modules.navigation.NavigationValidationService;
import com.crosshubber.portal.modules.registry.entrypointgroups.EntryPointGroupEntity;
import com.crosshubber.portal.modules.registry.entrypointgroups.EntryPointGroupRepository;
import com.crosshubber.portal.modules.registry.entrypointgroups.EntryPointGroupsService;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointEntity;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointRepository;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointsService;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Settings-shell tree editor (groups + items) — mirrors the shell-tree endpoints in {@code
 * portal/src/modules/navigation/navigation.routes.ts}: group key resolution (slugified {@code
 * nav-*} keys), per-bucket renumbering, ungrouping of unlisted items and cascade cleanup of removed
 * groups — one transaction.
 */
@Service
public class ShellTreeService {

  private static final String KEY_RE = "^[a-z0-9][a-z0-9-]{0,63}$";

  private final EntryPointGroupRepository groupRepo;
  private final EntryPointRepository entryPointRepo;

  public ShellTreeService(
      EntryPointGroupRepository groupRepo, EntryPointRepository entryPointRepo) {
    this.groupRepo = groupRepo;
    this.entryPointRepo = entryPointRepo;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> shellTreePayload(String category) {
    List<Map<String, Object>> groups =
        groupRepo.findByCategoryOrderBySortOrderAscNameAsc(category).stream()
            .map(EntryPointGroupsService::toOutput)
            .toList();
    List<Map<String, Object>> items =
        entryPointRepo.findByCategoryOrderBySortOrderAscNameAsc(category).stream()
            .filter(ep -> Boolean.TRUE.equals(ep.getActive()))
            .map(EntryPointsService::toOutput)
            .toList();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("category", category);
    payload.put("groups", groups);
    payload.put("items", items);
    return payload;
  }

  /** Applies the full settings-shell tree save. */
  @Transactional
  public Map<String, Object> saveShellTree(String category, JsonNode body) {
    JsonNode groupInputs = body.path("groups");
    JsonNode itemInputs = body.path("items");

    // Validate counts
    if (groupInputs.size() > 200) {
      throw new IllegalArgumentException("groups: must not have more than 200 items");
    }
    if (itemInputs.size() > 500) {
      throw new IllegalArgumentException("items: must not have more than 500 items");
    }
    // Validate each group
    for (int i = 0; i < groupInputs.size(); i++) {
      JsonNode g = groupInputs.get(i);
      String groupKey = g.path("groupKey").asText(null);
      if (groupKey != null && !groupKey.isEmpty() && !groupKey.matches(KEY_RE)) {
        throw new IllegalArgumentException(
            "groups[" + i + "].groupKey: must match [a-z0-9][a-z0-9-]{0,63}");
      }
      String parentKey = g.path("parentKey").asText(null);
      if (parentKey != null && !parentKey.isEmpty() && !parentKey.matches(KEY_RE)) {
        throw new IllegalArgumentException(
            "groups[" + i + "].parentKey: must match [a-z0-9][a-z0-9-]{0,63}");
      }
      String name = g.path("name").asText(null);
      if (name == null || name.isEmpty()) {
        throw new IllegalArgumentException("groups[" + i + "].name: Required");
      }
      if (name.length() > 256) {
        throw new IllegalArgumentException(
            "groups[" + i + "].name: must not be longer than 256 characters");
      }
      String icon = g.path("icon").asText(null);
      if (icon != null && icon.length() > 64) {
        throw new IllegalArgumentException(
            "groups[" + i + "].icon: must not be longer than 64 characters");
      }
    }
    // Validate each item
    for (int i = 0; i < itemInputs.size(); i++) {
      JsonNode item = itemInputs.get(i);
      String moduleKey = item.path("moduleKey").asText(null);
      if (moduleKey == null || moduleKey.isEmpty()) {
        throw new IllegalArgumentException("items[" + i + "].moduleKey: Required");
      }
      if (!moduleKey.matches(KEY_RE)) {
        throw new IllegalArgumentException(
            "items[" + i + "].moduleKey: must match [a-z0-9][a-z0-9-]{0,63}");
      }
      String entryKey = item.path("entryKey").asText(null);
      if (entryKey == null || entryKey.isEmpty()) {
        throw new IllegalArgumentException("items[" + i + "].entryKey: Required");
      }
      if (!entryKey.matches(KEY_RE)) {
        throw new IllegalArgumentException(
            "items[" + i + "].entryKey: must match [a-z0-9][a-z0-9-]{0,63}");
      }
    }

    // Resolve group keys: client keys win; missing keys are slugified (unique
    // across ALL groups, mirroring the route helper).
    List<EntryPointGroupEntity> existingGroups =
        groupRepo.findByCategoryOrderBySortOrderAscNameAsc(category);
    Set<String> allGroupKeys = new LinkedHashSet<>();
    groupRepo.findAll().forEach(g -> allGroupKeys.add(g.getGroupKey()));
    Map<String, EntryPointGroupEntity> byExistingKey = new LinkedHashMap<>();
    for (EntryPointGroupEntity g : existingGroups) {
      byExistingKey.put(g.getGroupKey(), g);
    }

    record ResolvedGroup(
        String key, String name, String parentKey, String icon, String existingRoles) {}
    List<ResolvedGroup> resolved = new ArrayList<>();
    for (JsonNode g : groupInputs) {
      String key;
      String provided = g.path("groupKey").asText(null);
      if (provided != null && !provided.isEmpty()) {
        key = provided;
      } else {
        key = slugify(g.path("name").asText(""), allGroupKeys);
      }
      boolean isNew = !byExistingKey.containsKey(key);
      if (!isNew) {
        allGroupKeys.add(key);
      }
      resolved.add(
          new ResolvedGroup(
              key,
              g.path("name").asText(),
              g.path("parentKey").isTextual() ? g.get("parentKey").asText() : null,
              g.path("icon").isTextual() ? g.get("icon").asText() : null,
              isNew ? "" : orEmpty(byExistingKey.get(key).getRoles())));
    }

    List<NavigationValidationService.ShellGroup> groupsForValidation =
        resolved.stream()
            .map(g -> new NavigationValidationService.ShellGroup(g.key(), g.name(), g.parentKey()))
            .toList();
    List<NavigationValidationService.ShellItem> itemsForValidation = new ArrayList<>();
    for (JsonNode item : itemInputs) {
      itemsForValidation.add(
          new NavigationValidationService.ShellItem(
              item.path("moduleKey").asText(),
              item.path("entryKey").asText(),
              isNullish(item.path("groupKey")) ? null : item.path("groupKey").asText()));
    }
    NavigationValidationService.Validation treeValidation =
        NavigationValidationService.validateShellTree(groupsForValidation, itemsForValidation);
    if (!treeValidation.success()) {
      throw new IllegalArgumentException(treeValidation.error());
    }

    // Items must belong to this category
    List<EntryPointEntity> categoryRows =
        entryPointRepo.findByCategoryOrderBySortOrderAscNameAsc(category);
    Set<String> categoryKeys = new HashSet<>();
    for (EntryPointEntity row : categoryRows) {
      categoryKeys.add(row.getModuleKey() + ":" + row.getEntryKey());
    }
    for (JsonNode item : itemInputs) {
      String ref = item.path("moduleKey").asText() + ":" + item.path("entryKey").asText();
      if (!categoryKeys.contains(ref)) {
        throw new IllegalArgumentException(
            "entry " + ref + " is not in category \"" + category + "\"");
      }
    }

    // Groups: upsert in payload order, renumbering per parent bucket.
    // Roles are never touched (preserved on conflict, empty on insert).
    Map<String, Integer> bucketCounter = new LinkedHashMap<>();
    for (ResolvedGroup g : resolved) {
      String bucket = g.parentKey() == null ? "" : g.parentKey();
      int order = bucketCounter.getOrDefault(bucket, 0);
      bucketCounter.put(bucket, order + 10);
      EntryPointGroupEntity entity =
          groupRepo
              .findByGroupKey(g.key())
              .orElseGet(
                  () -> {
                    EntryPointGroupEntity created = new EntryPointGroupEntity();
                    created.setGroupKey(g.key());
                    created.setRoles("");
                    return created;
                  });
      if (entity.getCategory() == null) {
        entity.setCategory(category);
      }
      entity.setName(g.name());
      entity.setParentKey(g.parentKey());
      entity.setSortOrder(order);
      entity.setIcon(g.icon());
      if (entity.getRoles() == null) {
        entity.setRoles(g.existingRoles());
      }
      groupRepo.save(entity);
    }

    // Items: listed items get group_key + per-bucket renumber; unlisted items
    // of the category are ungrouped and appended after the listed root ones.
    Set<String> listedRefs = new LinkedHashSet<>();
    for (JsonNode item : itemInputs) {
      listedRefs.add(item.path("moduleKey").asText() + ":" + item.path("entryKey").asText());
    }
    Map<String, Integer> itemBucket = new LinkedHashMap<>();
    for (JsonNode item : itemInputs) {
      String groupKey = isNullish(item.path("groupKey")) ? null : item.path("groupKey").asText();
      String bucket = groupKey == null ? "" : groupKey;
      int order = itemBucket.getOrDefault(bucket, 0);
      itemBucket.put(bucket, order + 10);
      entryPointRepo
          .findByModuleKeyAndEntryKey(
              item.path("moduleKey").asText(), item.path("entryKey").asText())
          .ifPresent(
              ep -> {
                ep.setGroupKey(groupKey);
                ep.setSortOrder(order);
                entryPointRepo.save(ep);
              });
    }
    List<EntryPointEntity> unlisted =
        categoryRows.stream()
            .filter(r -> !listedRefs.contains(r.getModuleKey() + ":" + r.getEntryKey()))
            .sorted(java.util.Comparator.comparingInt(EntryPointEntity::getSortOrder))
            .toList();
    for (EntryPointEntity row : unlisted) {
      int order = itemBucket.getOrDefault("", 0);
      itemBucket.put("", order + 10);
      row.setGroupKey(null);
      row.setSortOrder(order);
      entryPointRepo.save(row);
    }

    // Groups removed from the payload: clear dangling item references first,
    // then delete (nested groups cascade via parent_key ON DELETE CASCADE).
    Set<String> payloadKeys = new LinkedHashSet<>();
    for (ResolvedGroup g : resolved) {
      payloadKeys.add(g.key());
    }
    for (EntryPointGroupEntity g : existingGroups) {
      if (!payloadKeys.contains(g.getGroupKey())) {
        for (EntryPointEntity ep : entryPointRepo.findByGroupKey(g.getGroupKey())) {
          ep.setGroupKey(null);
          entryPointRepo.save(ep);
        }
        groupRepo.delete(g);
      }
    }

    return shellTreePayload(category);
  }

  /** Mirrors the route slugify: nav-<base>, de-duplicated with -2, -3… */
  private static String slugify(String name, Set<String> takenKeys) {
    String base = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    if (base.length() > 40) {
      base = base.substring(0, 40);
    }
    if (base.isEmpty()) {
      base = "section";
    }
    String key = "nav-" + base;
    int n = 2;
    while (takenKeys.contains(key)) {
      key = "nav-" + base + "-" + n++;
    }
    takenKeys.add(key);
    return key;
  }

  private static boolean isNullish(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode();
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
