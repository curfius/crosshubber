package com.crosshubber.portal.modules.registry.entrypointgroups;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.common.Roles;

/**
 * Entry point groups domain service — mirrors {@code
 * portal/src/modules/entry-points/entry-point-groups.service.ts}.
 */
@Service
public class EntryPointGroupsService {

  private static final String KEY_RE = "^[a-z0-9][a-z0-9-]{0,63}$";
  private static final List<String> CATEGORIES =
      List.of("applications", "settings", "features", "user-settings");

  private final EntryPointGroupRepository repo;

  public EntryPointGroupsService(EntryPointGroupRepository repo) {
    this.repo = repo;
  }

  /** Output DTO — mirrors rowToOutput in entry-point-groups.service.ts. */
  public static Map<String, Object> toOutput(EntryPointGroupEntity g) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("groupKey", g.getGroupKey());
    out.put("category", g.getCategory());
    out.put("name", g.getName());
    out.put("parentKey", g.getParentKey());
    out.put("sortOrder", g.getSortOrder());
    if (g.getIcon() != null && !g.getIcon().isBlank()) {
      out.put("icon", g.getIcon());
    }
    List<String> roles = Roles.parse(g.getRoles());
    if (!roles.isEmpty()) {
      out.put("roles", roles);
    }
    return out;
  }

  @Transactional(readOnly = true)
  public List<EntryPointGroupEntity> list(String category) {
    if (category != null) {
      return repo.findByCategoryOrderBySortOrderAscNameAsc(category);
    }
    return repo.findAll(Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("name")));
  }

  @Transactional(readOnly = true)
  public EntryPointGroupEntity get(String groupKey) {
    return repo.findByGroupKey(groupKey).orElse(null);
  }

  /** Returns the validation error message, or null when valid. */
  public String validate(Map<String, Object> input) {
    Object groupKey = input.get("groupKey");
    if (!(groupKey instanceof String key) || !key.matches(KEY_RE)) {
      return "groupKey must match [a-z0-9][a-z0-9-]{0,63}";
    }
    if (!(input.get("category") instanceof String category) || !CATEGORIES.contains(category)) {
      return "category must be applications|settings|features|user-settings";
    }
    if (!(input.get("name") instanceof String name) || name.isBlank()) {
      return "name is required";
    }
    return null;
  }

  /** Upsert on groupKey — mirrors repo.upsert (full replace). */
  @Transactional
  public EntryPointGroupEntity upsert(Map<String, Object> input) {
    String groupKey = (String) input.get("groupKey");
    EntryPointGroupEntity group =
        repo.findByGroupKey(groupKey)
            .orElseGet(
                () -> {
                  EntryPointGroupEntity created = new EntryPointGroupEntity();
                  created.setGroupKey(groupKey);
                  return created;
                });
    group.setCategory((String) input.get("category"));
    group.setName((String) input.get("name"));
    group.setParentKey(orNull(input.get("parentKey")));
    group.setSortOrder(orInt(input.get("sortOrder"), 0));
    group.setIcon(orNull(input.get("icon")));
    String roles = joinRoles(input.get("roles"));
    group.setRoles(roles != null ? roles : "");
    return repo.save(group);
  }

  @Transactional
  public boolean remove(String groupKey) {
    var existing = repo.findByGroupKey(groupKey);
    if (existing.isEmpty()) {
      return false;
    }
    repo.delete(existing.get());
    return true;
  }

  /** Mirrors repo.reorder — sort_order = i*10 in payload order. */
  @Transactional
  public void reorder(List<String> groupKeys) {
    for (int i = 0; i < groupKeys.size(); i++) {
      final int order = i * 10;
      final String groupKey = groupKeys.get(i);
      repo.findByGroupKey(groupKey)
          .ifPresent(
              g -> {
                g.setSortOrder(order);
                repo.save(g);
              });
    }
  }

  private static String orNull(Object value) {
    return value instanceof String s ? s : null;
  }

  private static int orInt(Object value, int fallback) {
    return value instanceof Number n ? n.intValue() : fallback;
  }

  private static String joinRoles(Object roles) {
    if (roles instanceof List<?> list) {
      return list.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(null);
    }
    return roles instanceof String s ? s : null;
  }
}
