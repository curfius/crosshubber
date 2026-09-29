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

import com.crosshubber.portal.common.Keys;
import com.crosshubber.portal.common.Texts;
import com.crosshubber.portal.modules.navigation.NavigationValidationService;
import com.crosshubber.portal.modules.navigation.groups.NavigationGroupDto;
import com.crosshubber.portal.modules.navigation.groups.NavigationGroupEntity;
import com.crosshubber.portal.modules.navigation.groups.NavigationGroupRepository;
import com.crosshubber.portal.modules.navigation.groups.NavigationGroupsService;
import com.crosshubber.portal.modules.navigation.shelltree.dto.ShellTreePayload;
import com.crosshubber.portal.modules.registry.dto.ModuleContentDto;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentCategory;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentRepository;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentsService;

import tools.jackson.databind.JsonNode;

/**
 * Settings-shell tree editor (groups + items): group key resolution (slugified {@code nav-*} keys),
 * per-bucket renumbering, ungrouping of unlisted items and cascade cleanup of removed groups — one
 * transaction.
 */
@Service
public class ShellTreeService {

  private final NavigationGroupRepository groupRepo;
  private final ModuleContentRepository contentRepo;

  public ShellTreeService(
      NavigationGroupRepository groupRepo, ModuleContentRepository contentRepo) {
    this.groupRepo = groupRepo;
    this.contentRepo = contentRepo;
  }

  @Transactional(readOnly = true)
  public ShellTreePayload shellTreePayload(String category) {
    List<NavigationGroupDto> groups =
        groupRepo
            .findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.parse(category))
            .stream()
            .map(NavigationGroupsService::toOutput)
            .toList();
    List<ModuleContentDto> items =
        contentRepo
            .findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.parse(category))
            .stream()
            .filter(ep -> Boolean.TRUE.equals(ep.getActive()))
            .map(ModuleContentsService::toOutput)
            .toList();
    return new ShellTreePayload(category, groups, items);
  }

  /** Applies the full settings-shell tree save. */
  @Transactional
  public ShellTreePayload saveShellTree(String category, JsonNode body) {
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
      String groupKey = g.path("groupKey").asString(null);
      if (groupKey != null && !groupKey.isEmpty() && !groupKey.matches(Keys.KEY_RE)) {
        throw new IllegalArgumentException(
            "groups[" + i + "].groupKey: must match [a-z0-9][a-z0-9-]{0,63}");
      }
      String parentKey = g.path("parentKey").asString(null);
      if (parentKey != null && !parentKey.isEmpty() && !parentKey.matches(Keys.KEY_RE)) {
        throw new IllegalArgumentException(
            "groups[" + i + "].parentKey: must match [a-z0-9][a-z0-9-]{0,63}");
      }
      String name = g.path("name").asString(null);
      if (name == null || name.isEmpty()) {
        throw new IllegalArgumentException("groups[" + i + "].name: Required");
      }
      if (name.length() > 256) {
        throw new IllegalArgumentException(
            "groups[" + i + "].name: must not be longer than 256 characters");
      }
      String icon = g.path("icon").asString(null);
      if (icon != null && icon.length() > 64) {
        throw new IllegalArgumentException(
            "groups[" + i + "].icon: must not be longer than 64 characters");
      }
      if (!isNullish(g.path("hidden")) && !g.path("hidden").isBoolean()) {
        throw new IllegalArgumentException("groups[" + i + "].hidden: must be a boolean");
      }
    }
    // Validate each item
    for (int i = 0; i < itemInputs.size(); i++) {
      JsonNode item = itemInputs.get(i);
      String moduleKey = item.path("moduleKey").asString(null);
      if (moduleKey == null || moduleKey.isEmpty()) {
        throw new IllegalArgumentException("items[" + i + "].moduleKey: Required");
      }
      if (!moduleKey.matches(Keys.KEY_RE)) {
        throw new IllegalArgumentException(
            "items[" + i + "].moduleKey: must match [a-z0-9][a-z0-9-]{0,63}");
      }
      String contentKey = item.path("contentKey").asString(null);
      if (contentKey == null || contentKey.isEmpty()) {
        throw new IllegalArgumentException("items[" + i + "].contentKey: Required");
      }
      if (!contentKey.matches(Keys.KEY_RE)) {
        throw new IllegalArgumentException(
            "items[" + i + "].contentKey: must match [a-z0-9][a-z0-9-]{0,63}");
      }
      if (!isNullish(item.path("hidden")) && !item.path("hidden").isBoolean()) {
        throw new IllegalArgumentException("items[" + i + "].hidden: must be a boolean");
      }
    }

    // Resolve group keys: client keys win; missing keys are slugified (unique
    // across ALL groups, mirroring the route helper).
    List<NavigationGroupEntity> existingGroups =
        groupRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.parse(category));
    Set<String> allGroupKeys = new LinkedHashSet<>();
    Map<String, NavigationGroupEntity> byGlobalKey = new LinkedHashMap<>();
    groupRepo
        .findAll()
        .forEach(
            g -> {
              allGroupKeys.add(g.getGroupKey());
              byGlobalKey.put(g.getGroupKey(), g);
            });
    Map<String, NavigationGroupEntity> byExistingKey = new LinkedHashMap<>();
    for (NavigationGroupEntity g : existingGroups) {
      byExistingKey.put(g.getGroupKey(), g);
    }

    record ResolvedGroup(
        String key,
        String name,
        String parentKey,
        String icon,
        boolean hidden,
        String existingRoles) {}
    List<ResolvedGroup> resolved = new ArrayList<>();
    for (JsonNode g : groupInputs) {
      String key;
      String provided = g.path("groupKey").asString(null);
      if (provided != null && !provided.isEmpty()) {
        key = provided;
      } else {
        key = slugify(g.path("name").asString(""), allGroupKeys);
      }
      boolean isNew = !byExistingKey.containsKey(key);
      if (!isNew) {
        allGroupKeys.add(key);
      }
      resolved.add(
          new ResolvedGroup(
              key,
              g.path("name").asString(),
              g.path("parentKey").isString() ? g.get("parentKey").asString() : null,
              g.path("icon").isString() ? g.get("icon").asString() : null,
              g.path("hidden").asBoolean(false),
              isNew ? "" : Texts.orEmpty(byExistingKey.get(key).getRoles())));
    }

    List<NavigationValidationService.ShellGroup> groupsForValidation =
        resolved.stream()
            .map(g -> new NavigationValidationService.ShellGroup(g.key(), g.name(), g.parentKey()))
            .toList();
    List<NavigationValidationService.ShellItem> itemsForValidation = new ArrayList<>();
    for (JsonNode item : itemInputs) {
      itemsForValidation.add(
          new NavigationValidationService.ShellItem(
              item.path("moduleKey").asString(),
              item.path("contentKey").asString(),
              isNullish(item.path("groupKey")) ? null : item.path("groupKey").asString()));
    }
    NavigationValidationService.Validation treeValidation =
        NavigationValidationService.validateShellTree(groupsForValidation, itemsForValidation);
    if (!treeValidation.success()) {
      throw new IllegalArgumentException(treeValidation.error());
    }

    // Items must belong to this category
    List<ModuleContentEntity> categoryRows =
        contentRepo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.parse(category));
    Set<String> categoryKeys = new HashSet<>();
    for (ModuleContentEntity row : categoryRows) {
      categoryKeys.add(row.getModuleKey() + ":" + row.getContentKey());
    }
    for (JsonNode item : itemInputs) {
      String ref = item.path("moduleKey").asString() + ":" + item.path("contentKey").asString();
      if (!categoryKeys.contains(ref)) {
        throw new IllegalArgumentException(
            "entry " + ref + " is not in category \"" + category + "\"");
      }
    }

    // Groups: upsert in payload order, renumbering per parent bucket.
    // Roles are never touched (preserved on conflict, empty on insert).
    // Lookups use the preloaded global map — no per-key SELECT in the loop.
    Map<String, Integer> bucketCounter = new LinkedHashMap<>();
    for (ResolvedGroup g : resolved) {
      String bucket = g.parentKey() == null ? "" : g.parentKey();
      int order = bucketCounter.getOrDefault(bucket, 0);
      bucketCounter.put(bucket, order + 10);
      NavigationGroupEntity entity =
          byGlobalKey.containsKey(g.key()) ? byGlobalKey.get(g.key()) : new NavigationGroupEntity();
      if (entity.getGroupKey() == null) {
        entity.setGroupKey(g.key());
        entity.setRoles("");
      }
      if (entity.getCategory() == null) {
        entity.setCategory(ModuleContentCategory.parse(category));
      }
      entity.setName(g.name());
      entity.setParentKey(g.parentKey());
      entity.setSortOrder(order);
      entity.setIcon(g.icon());
      entity.setHidden(g.hidden());
      if (entity.getRoles() == null) {
        entity.setRoles(g.existingRoles());
      }
      groupRepo.save(entity);
    }

    // Items: listed items get group_key + per-bucket renumber; unlisted items
    // of the category are ungrouped and appended after the listed root ones.
    // Lookup map is built from the already-loaded category rows (no per-item SELECT).
    Map<String, ModuleContentEntity> itemsByRef = new LinkedHashMap<>();
    for (ModuleContentEntity row : categoryRows) {
      itemsByRef.put(row.getModuleKey() + ":" + row.getContentKey(), row);
    }
    Set<String> listedRefs = new LinkedHashSet<>();
    for (JsonNode item : itemInputs) {
      listedRefs.add(item.path("moduleKey").asString() + ":" + item.path("contentKey").asString());
    }
    Map<String, Integer> itemBucket = new LinkedHashMap<>();
    for (JsonNode item : itemInputs) {
      String groupKey = isNullish(item.path("groupKey")) ? null : item.path("groupKey").asString();
      String bucket = groupKey == null ? "" : groupKey;
      int order = itemBucket.getOrDefault(bucket, 0);
      itemBucket.put(bucket, order + 10);
      ModuleContentEntity ep =
          itemsByRef.get(
              item.path("moduleKey").asString() + ":" + item.path("contentKey").asString());
      if (ep != null) {
        ep.setGroupKey(groupKey);
        ep.setSortOrder(order);
        ep.setHidden(item.path("hidden").asBoolean(false));
        contentRepo.save(ep);
      }
    }
    List<ModuleContentEntity> unlisted =
        categoryRows.stream()
            .filter(r -> !listedRefs.contains(r.getModuleKey() + ":" + r.getContentKey()))
            .sorted(java.util.Comparator.comparingInt(r -> r.getSortOrder()))
            .toList();
    for (ModuleContentEntity row : unlisted) {
      int order = itemBucket.getOrDefault("", 0);
      itemBucket.put("", order + 10);
      row.setGroupKey(null);
      row.setSortOrder(order);
      contentRepo.save(row);
    }

    // Groups removed from the payload: clear dangling item references first,
    // then delete (nested groups cascade via parent_key ON DELETE CASCADE).
    Set<String> payloadKeys = new LinkedHashSet<>();
    for (ResolvedGroup g : resolved) {
      payloadKeys.add(g.key());
    }
    for (NavigationGroupEntity g : existingGroups) {
      if (!payloadKeys.contains(g.getGroupKey())) {
        for (ModuleContentEntity ep : contentRepo.findByGroupKey(g.getGroupKey())) {
          ep.setGroupKey(null);
          contentRepo.save(ep);
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
}
