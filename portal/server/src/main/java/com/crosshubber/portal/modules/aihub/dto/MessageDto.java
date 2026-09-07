package com.crosshubber.portal.modules.aihub.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageDto(
    Long id,
    String conversationId,
    String role,
    String content,
    String providerId,
    String model,
    String createdAt) {}
