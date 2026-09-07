package com.crosshubber.portal.modules.aihub.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenDto(
    String id, String name, String maskedKey, Boolean enabled, List<Map<String, Object>> models) {}
