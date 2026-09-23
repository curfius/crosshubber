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
        ep.getCategory(),
        ep.getName(),
        ep.getType(),
        ep.getSortOrder(),
        ep.getActive(),
        ep.getMulti(),
        Texts.notBlank(ep.getDescription()) ? ep.getDescription() : null,
        Texts.notBlank(ep.getUrl()) ? ep.getUrl() : null,
        sandbox,
        Texts.notBlank(ep.getAllow()) ? ep.getAllow() : null,
        Texts.notBlank(ep.getLoadPath()) ? ep.getLoadPath() : null,
        Texts.notBlank(ep.getEntryUrl()) ? ep.getEntryUrl() : null,
        Texts.notBlank(ep.getElement()) ? ep.getElement() : null,
        Texts.notBlank(ep.getParentEntryKey()) ? ep.getParentEntryKey() : null,
        Texts.notBlank(ep.getGroupKey()) ? ep.getGroupKey() : null,
        roles.isEmpty() ? null : roles,
        Texts.notBlank(ep.getIcon()) ? ep.getIcon() : null,
        Texts.notBlank(ep.getColor()) ? ep.getColor() : null,
        Boolean.TRUE.equals(ep.getHidden()) ? Boolean.TRUE : null);
  }

  /** Null-safe comparator key for sort ordering. */
  public int orderOf() {
    return sortOrder != null ? sortOrder : 0;
  }
}
