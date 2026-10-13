package com.crosshubber.portal.modules.navigation.shelltree;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.modules.navigation.groups.NavigationGroupEntity;
import com.crosshubber.portal.modules.navigation.groups.NavigationGroupRepository;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentCategory;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentRepository;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModuleRepository;

/**
 * Shell-tree section seeding: every shell tree is initialized with a section per module (its
 * contents live inside it) plus a "Core" section for portal-shell elements (the navigation module's
 * own entries — core apps, core settings, ...).
 *
 * <p>Sections are insert-if-absent: admin-made groupings, renames and deletions of sections that no
 * longer own content are preserved. The reconciliation pass only re-parents contents whose {@code
 * group_key} is unset or points at a missing group, so a module added to the registry gets its
 * section (with its contents) on the next boot or save.
 */
@Service
public class ShellSections {

  /** The portal's own module: its contents are shell elements and live under Core. */
  public static final String SHELL_MODULE_KEY = "navigation";

  private final NavigationGroupRepository groupRepo;
  private final ModuleContentRepository contentRepo;
  private final ModuleRepository moduleRepo;

  public ShellSections(
      NavigationGroupRepository groupRepo,
      ModuleContentRepository contentRepo,
      ModuleRepository moduleRepo) {
    this.groupRepo = groupRepo;
    this.contentRepo = contentRepo;
    this.moduleRepo = moduleRepo;
  }

  /** Category-scoped Core key ({@code group_key} is unique across categories). */
  static String coreKey(ModuleContentCategory category) {
    return switch (category) {
      case APPLICATIONS -> "core-apps";
      case ADMIN_SETTINGS -> "core-admin-settings";
      case USER_SETTINGS -> "core-user-settings";
      default -> "core-" + category.value();
    };
  }

  /** Display name of the Core section when it is created. */
  static String coreName(ModuleContentCategory category) {
    return switch (category) {
      case APPLICATIONS -> "Core apps";
      default -> "Core settings";
    };
  }

  /** Category-scoped per-module section key. */
  static String moduleSectionKey(ModuleContentCategory category, String moduleKey) {
    return "mod-" + category.value() + "-" + moduleKey;
  }

  /** Section key hosting a module's contents in the category (Core for the shell module). */
  static String sectionKeyFor(ModuleContentCategory category, String moduleKey) {
    return SHELL_MODULE_KEY.equals(moduleKey)
        ? coreKey(category)
        : moduleSectionKey(category, moduleKey);
  }

  static String sectionName(ModuleContentCategory category, String moduleKey, String moduleNames) {
    if (SHELL_MODULE_KEY.equals(moduleKey)) {
      return coreName(category);
    }
    return moduleNames;
  }

  /**
   * Boot pass: assigns every ungrouped/disowned content of the category to its module section (or
   * Core for the shell module), creating missing sections insert-if-absent. Cheap and idempotent.
   */
  @Transactional
  public void reconcileSections(ModuleContentCategory category) {
    List<ModuleContentEntity> contents =
        contentRepo.findByCategoryOrderBySortOrderAscNameAsc(category);
    if (contents.isEmpty()) {
      return;
    }
    Map<String, NavigationGroupEntity> byKey = new LinkedHashMap<>();
    for (NavigationGroupEntity g : groupRepo.findByCategoryOrderBySortOrderAscNameAsc(category)) {
      byKey.put(g.getGroupKey(), g);
    }
    Map<String, String> moduleNames = loadModuleNames();
    for (ModuleContentEntity c : contents) {
      boolean orphan = c.getGroupKey() == null || !byKey.containsKey(c.getGroupKey());
      if (!orphan) {
        continue;
      }
      String key = ensureSection(category, c.getModuleKey(), moduleNames, List.of());
      c.setGroupKey(key);
      contentRepo.save(c);
    }
  }

  /**
   * Save-time guard: hands back a section key that survives {@code excludedKeys} removals in the
   * same transaction, creating the section when no usable group exists.
   */
  @Transactional
  public String assignSection(
      ModuleContentCategory category, String moduleKey, Collection<String> excludedKeys) {
    Map<String, String> moduleNames = loadModuleNames();
    return ensureSection(
        category, moduleKey, moduleNames, excludedKeys == null ? List.of() : excludedKeys);
  }

  private String ensureSection(
      ModuleContentCategory category,
      String moduleKey,
      Map<String, String> moduleNames,
      Collection<String> excludedKeys) {
    String base = sectionKeyFor(category, moduleKey);
    String key = base;
    int n = 2;
    Optional<NavigationGroupEntity> existing = groupRepo.findByGroupKey(key);
    while (existing.isPresent()
        && (excludedKeys.contains(key) || existing.get().getCategory() != category)) {
      key = base + "-" + n++;
      existing = groupRepo.findByGroupKey(key);
    }
    if (existing.isEmpty()) {
      NavigationGroupEntity group = new NavigationGroupEntity();
      group.setGroupKey(key);
      group.setCategory(category);
      group.setName(
          sectionName(category, moduleKey, moduleNames.getOrDefault(moduleKey, moduleKey)));
      group.setSortOrder(groupRepo.findMaxSortOrder(category).map(m -> m + 10).orElse(0));
      group.setRoles("");
      groupRepo.save(group);
    }
    return key;
  }

  private Map<String, String> loadModuleNames() {
    Map<String, String> names = new HashMap<>();
    for (ModuleEntity m : moduleRepo.findAll()) {
      names.put(m.getKey(), m.getName());
    }
    return names;
  }
}
