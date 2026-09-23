package com.crosshubber.portal.modules.registry.dto;

import java.util.List;

import com.crosshubber.portal.common.Keys;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** {@code POST/PUT /api/registry/entry-point-groups} body (full-replace upsert). */
public record EntryPointGroupUpsertRequest(
    @NotBlank(message = "is required")
        @Pattern(regexp = Keys.KEY_RE, message = "must match [a-z0-9][a-z0-9-]{0,63}")
        String groupKey,
    @NotNull(message = "must be applications|settings|features|user-settings")
        @Pattern(
            regexp = "applications|settings|features|user-settings",
            message = "must be applications|settings|features|user-settings")
        String category,
    @NotBlank(message = "is required") String name,
    String parentKey,
    Integer sortOrder,
    String icon,
    List<String> roles) {}
