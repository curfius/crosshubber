package com.crosshubber.portal.workspaces.dto;

/** Workspace list item ({@code savedAt} is epoch millis). */
public record WorkspaceSummaryDto(
    String id, String name, String description, String color, String status, long savedAt) {}
