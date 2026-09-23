package com.crosshubber.portal.modules.registry.dto;

import java.util.Arrays;
import java.util.List;

import com.crosshubber.portal.common.Roles;
import com.crosshubber.portal.common.Texts;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Entry point output ({@code /api/registry/entry-points}, shell trees, shell config). Blank/empty
 * optional fields are omitted; {@code sandbox} and {@code roles} are stored comma-joined and
 * re-emitted as arrays; {@code hidden} is emitted only when {@code true}.
 */
@JsonInclude(Include.NON_NULL)
public record EntryPointDto(
    Long id,
    String moduleKey,
    String entryKey,
    String category,
    String name,
    String type,
    Integer sortOrder,
    Boolean active,
    Boolean multi,
    String description,
    String url,
    List<String> sandbox,
    String allow,
    String loadPath,
    String entryUrl,
    String element,
    String parentEntryKey,
    String groupKey,
    List<String> roles,
    String icon,
    String color,
    Boolean hidden) {

  public static EntryPointDto fromEntity(EntryPointEntity ep) {
    List<String> roles = Roles.parse(ep.getRoles());
    List<String> sandbox = null;
    if (Texts.notBlank(ep.getSandbox())) {
      String[] tokens = ep.getSandbox().split(",");
      if (tokens.length > 0 && !tokens[0].isEmpty()) {
        sandbox = Arrays.asList(tokens);
      }
    }
    return new EntryPointDto(
        ep.getId(),
        ep.getModuleKey(),
        ep.getEntryKey(),
        ep.getCategory() == null ? null : ep.getCategory().value(),
        ep.getName(),
        ep.getType() == null ? null : ep.getType().value(),
        ep.getSortOrder(),
        ep.getActive(),
        ep.getMulti(),
        Texts.blankToNull(ep.getDescription()),
        Texts.blankToNull(ep.getUrl()),
        sandbox,
        Texts.blankToNull(ep.getAllow()),
        Texts.blankToNull(ep.getLoadPath()),
        Texts.blankToNull(ep.getEntryUrl()),
        Texts.blankToNull(ep.getElement()),
        Texts.blankToNull(ep.getParentEntryKey()),
        Texts.blankToNull(ep.getGroupKey()),
        roles.isEmpty() ? null : roles,
        Texts.blankToNull(ep.getIcon()),
        Texts.blankToNull(ep.getColor()),
        Boolean.TRUE.equals(ep.getHidden()) ? Boolean.TRUE : null);
  }

  /** Null-safe comparator key for sort ordering. */
  public int orderOf() {
    return sortOrder != null ? sortOrder : 0;
  }
}
