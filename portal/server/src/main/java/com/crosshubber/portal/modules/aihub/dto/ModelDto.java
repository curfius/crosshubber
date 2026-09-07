package com.crosshubber.portal.modules.aihub.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelDto(String id, String name, Boolean enabled) {}
