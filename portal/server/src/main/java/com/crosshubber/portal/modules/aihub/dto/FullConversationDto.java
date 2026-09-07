package com.crosshubber.portal.modules.aihub.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FullConversationDto(
    String id, String userId, String origin, String title, String createdAt, String updatedAt) {}
