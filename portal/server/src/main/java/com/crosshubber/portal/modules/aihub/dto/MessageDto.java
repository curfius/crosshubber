package com.crosshubber.portal.modules.aihub.dto;

/** Chat message history entry ({@code /api/ai-hub/conversations/{id}/messages}). */
public record MessageDto(String role, String content) {}
