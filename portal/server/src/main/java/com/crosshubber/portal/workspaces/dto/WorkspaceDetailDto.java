package com.crosshubber.portal.workspaces.dto;

import tools.jackson.databind.JsonNode;

/**
 * Workspace detail ({@code savedAt} is epoch millis; {@code layout}/{@code focusedGroupId} are
 * always present, null when unset).
 */
public record WorkspaceDetailDto(
    String id,
    String name,
    String description,
    JsonNode layout,
    JsonNode groups,
    String focusedGroupId,
    boolean hideSingleTabToolbar,
    boolean locked,
    String color,
    String status,
    long savedAt) {}
