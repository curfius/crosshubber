package com.crosshubber.portal.modules.aihub.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/ai-hub/providers/{id}/tokens} body. */
public record CreateTokenRequest(@NotBlank String name, String apiKey) {}
