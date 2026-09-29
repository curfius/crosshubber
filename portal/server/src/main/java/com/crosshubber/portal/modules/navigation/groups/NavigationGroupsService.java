package com.crosshubber.portal.modules.navigation.groups;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.common.Texts;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentCategory;

/** Navigation groups domain service (shell-nav sections). */
@Service
public class NavigationGroupsService {

  private final NavigationGroupRepository repo;

  public NavigationGroupsService(NavigationGroupRepository repo) {
    this.repo = repo;
  }

  /** Output DTO. */
  public static NavigationGroupDto toOutput(NavigationGroupEntity g) {
    return NavigationGroupDto.fromEntity(g);
  }

  @Transactional(readOnly = true)
  public List<NavigationGroupEntity> list(String category) {
    if (category != null) {
      return repo.findByCategoryOrderBySortOrderAscNameAsc(ModuleContentCategory.parse(category));
    }
    return repo.findAll(Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("name")));
  }

  @Transactional(readOnly = true)
  public NavigationGroupEntity get(String groupKey) {
    return repo.findByGroupKey(groupKey).orElse(null);
  }

  /** Upsert on groupKey Ã¢â‚¬â€ mirrors repo.upsert (full replace). */
  @Transactional
  public NavigationGroupEntity upsert(NavigationGroupUpsertRequest input) {
    NavigationGroupEntity group =
        repo.findByGroupKey(input.groupKey())
            .orElseGet(
                () -> {
                  NavigationGroupEntity created = new NavigationGroupEntity();
                  created.setGroupKey(input.groupKey());
                  return created;
                });
    group.setCategory(ModuleContentCategory.parse(input.category()));
    group.setName(input.name());
    group.setParentKey(input.parentKey());
    group.setSortOrder(input.sortOrder() != null ? input.sortOrder() : 0);
    group.setIcon(input.icon());
    String roles = Texts.joinComma(input.roles());
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

  /** Mirrors repo.reorder Ã¢â‚¬â€ sort_order = i*10 in payload order. */
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
}
