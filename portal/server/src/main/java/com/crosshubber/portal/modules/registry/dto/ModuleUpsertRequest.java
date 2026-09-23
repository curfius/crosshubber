package com.crosshubber.portal.modules.registry.dto;

import java.util.List;

import com.crosshubber.portal.common.Keys;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** {@code POST /api/registry/modules} body (upsert with COALESCE semantics). */
public record ModuleUpsertRequest(
    @NotBlank(message = "is required")
        @Pattern(regexp = Keys.KEY_RE, message = "must match [a-z0-9][a-z0-9-]{0,63}")
        String key,
    @NotBlank(message = "is required") String name,
    String icon,
    String roles,
    Boolean active,
    Boolean builtin,
    String version,
    String manifestDigest,
    String managedBy,
    String sourceUrl,
    String baseUrl,
    String health,
    @Valid List<SecurityRoleDto> securityRoles) {}
