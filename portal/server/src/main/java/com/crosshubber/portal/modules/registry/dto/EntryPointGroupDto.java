package com.crosshubber.portal.modules.registry.dto;

import java.util.List;

import com.crosshubber.portal.common.Roles;
import com.crosshubber.portal.common.Texts;
import com.crosshubber.portal.modules.registry.entrypointgroups.EntryPointGroupEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Entry point group output (registry, shell trees, shell config). {@code parentKey} is always
 * present (null for roots); blank/empty optional fields are omitted; {@code hidden} is emitted only
 * when {@code true}.
 */
@JsonInclude(Include.NON_NULL)
public record EntryPointGroupDto(
    String groupKey,
    String category,
    String name,
    @JsonInclude(Include.ALWAYS) String parentKey,
    Integer sortOrder,
    String icon,
    List<String> roles,
    Boolean hidden) {

  public static EntryPointGroupDto fromEntity(EntryPointGroupEntity g) {
    List<String> roles = Roles.parse(g.getRoles());
    return new EntryPointGroupDto(
        g.getGroupKey(),
        g.getCategory() == null ? null : g.getCategory().value(),
        g.getName(),
        g.getParentKey(),
        g.getSortOrder(),
        Texts.blankToNull(g.getIcon()),
        roles.isEmpty() ? null : roles,
        Boolean.TRUE.equals(g.getHidden()) ? Boolean.TRUE : null);
  }

  /** Null-safe comparator key for sort ordering. */
  public int orderOf() {
    return sortOrder != null ? sortOrder : 0;
  }
}
