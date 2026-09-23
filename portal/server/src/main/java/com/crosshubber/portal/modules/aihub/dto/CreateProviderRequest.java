package com.crosshubber.portal.modules.aihub.dto;

import com.crosshubber.portal.common.Keys;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** {@code POST /api/ai-hub/providers} body. */
public record CreateProviderRequest(
    @NotBlank @Pattern(regexp = Keys.KEY_RE, message = "must be kebab-case") String id,
    @NotBlank String name,
    String baseURL) {}
