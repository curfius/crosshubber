package com.crosshubber.portal.modules.aihub.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderDto(
    String id, String name, Boolean enabled, String baseURL, List<TokenDto> tokens) {}
