package com.crosshubber.portal.modules.registry.dto;

import java.util.List;

import com.crosshubber.portal.common.Keys;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * {@code POST/PUT /api/registry/module-contents} body (full-replace upsert). Scalar constraints are
 * enforced declaratively; type-specific cross-field rules (url/loadPath/entryUrl/element) stay in
 * {@code ModuleContentsService.validate}.
 */
public record ModuleContentUpsertRequest(
    @NotBlank(message = "is required")
        @Pattern(regexp = Keys.KEY_RE, message = "must match [a-z0-9][a-z0-9-]{0,63}")
        String moduleKey,
    @NotBlank(message = "is required")
        @Pattern(regexp = Keys.KEY_RE, message = "must match [a-z0-9][a-z0-9-]{0,63}")
        String contentKey,
    @NotNull(message = "must be applications|settings|features|user-settings")
        @Pattern(
            regexp = "applications|settings|features|user-settings",
            message = "must be applications|settings|features|user-settings")
        String category,
    @NotBlank(message = "is required") String name,
    @NotNull(message = "must be iframe|embedded|mfe|link")
        @Pattern(regexp = "iframe|embedded|mfe|link", message = "must be iframe|embedded|mfe|link")
        String type,
    String description,
    String url,
    List<String> sandbox,
    String allow,
    String loadPath,
    String entryUrl,
    String element,
    String parentContentKey,
    String groupKey,
    Integer sortOrder,
    List<String> roles,
    Boolean active,
    String icon,
    String color,
    Boolean multi) {}
