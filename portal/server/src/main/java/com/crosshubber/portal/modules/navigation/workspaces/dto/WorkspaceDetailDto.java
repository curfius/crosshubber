package com.crosshubber.portal.modules.navigation.workspaces.dto;

import tools.jackson.databind.JsonNode;

/**
 * Workspace detail ({@code savedAt} is epoch millis; {@code layout}/{@code focusedGroupId} are
 * always present, null when unset; {@code homeVisible} is false only when explicitly saved false —
 * legacy rows and requests omitting it report true).
 */
public record WorkspaceDetailDto(
    String id,
    String name,
    String description,
    JsonNode layout,
    JsonNode groups,
    String focusedGroupId,
    boolean hideSingleTabToolbar,
    boolean homeVisible,
    boolean locked,
    String color,
    String status,
    long savedAt) {}
