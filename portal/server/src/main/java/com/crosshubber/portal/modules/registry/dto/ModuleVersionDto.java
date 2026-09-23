package com.crosshubber.portal.modules.registry.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Stored module version snapshot ({@code /api/registry/versions/{moduleKey}}); {@code installedAt}
 * is an ISO-8601 string with millisecond precision.
 */
@JsonInclude(Include.NON_NULL)
public record ModuleVersionDto(
    Long id,
    String version,
    String digest,
    String installedAt,
    String installedBy,
    String status) {}
