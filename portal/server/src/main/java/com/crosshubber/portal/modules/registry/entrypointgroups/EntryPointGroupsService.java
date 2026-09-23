package com.crosshubber.portal.modules.registry.entrypointgroups;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.common.Texts;
import com.crosshubber.portal.modules.registry.dto.EntryPointGroupDto;
import com.crosshubber.portal.modules.registry.dto.EntryPointGroupUpsertRequest;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointCategory;

/** Entry point groups domain service. */
@Service
public class EntryPointGroupsService {

  private final EntryPointGroupRepository repo;

  public EntryPointGroupsService(EntryPointGroupRepository repo) {
    this.repo = repo;
  }

  /** Output DTO. */
  public static EntryPointGroupDto toOutput(EntryPointGroupEntity g) {
    return EntryPointGroupDto.fromEntity(g);
  }

  @Transactional(readOnly = true)
  public List<EntryPointGroupEntity> list(String category) {
    if (category != null) {
      return repo.findByCategoryOrderBySortOrderAscNameAsc(EntryPointCategory.parse(category));
    }
    return repo.findAll(Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("name")));
  }

  @Transactional(readOnly = true)
  public EntryPointGroupEntity get(String groupKey) {
    return repo.findByGroupKey(groupKey).orElse(null);
  }

  /** Upsert on groupKey — mirrors repo.upsert (full replace). */
  @Transactional
  public EntryPointGroupEntity upsert(EntryPointGroupUpsertRequest input) {
    EntryPointGroupEntity group =
        repo.findByGroupKey(input.groupKey())
            .orElseGet(
                () -> {
                  EntryPointGroupEntity created = new EntryPointGroupEntity();
                  created.setGroupKey(input.groupKey());
                  return created;
                });
    group.setCategory(EntryPointCategory.parse(input.category()));
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
}
