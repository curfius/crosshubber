package com.crosshubber.portal.modules.registry.dto;

import java.util.List;

import com.crosshubber.portal.common.Roles;
import com.crosshubber.portal.common.Texts;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Module registry output ({@code /api/registry/modules}). Blank/empty optional fields are omitted
 * from the payload.
 */
@JsonInclude(Include.NON_NULL)
public record ModuleDto(
    String key,
    String name,
    Boolean active,
    Boolean builtin,
    String managedBy,
    String icon,
    List<String> roles,
    String version,
    String manifestDigest,
    String sourceUrl,
    List<SecurityRoleDto> securityRoles,
    String baseUrl,
    String health) {

  public static ModuleDto fromEntity(ModuleEntity m, List<SecurityRoleDto> securityRoles) {
    List<String> roles = Roles.parse(m.getRoles());
    return new ModuleDto(
        m.getKey(),
        m.getName(),
        m.getActive(),
        m.getBuiltin(),
        m.getManagedBy(),
        Texts.blankToNull(m.getIcon()),
        roles.isEmpty() ? null : roles,
        m.getVersion(),
        m.getManifestDigest(),
        m.getSourceUrl(),
        securityRoles.isEmpty() ? null : securityRoles,
        m.getBaseUrl(),
        m.getHealth());
  }
}
