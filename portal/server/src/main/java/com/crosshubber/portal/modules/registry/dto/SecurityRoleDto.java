package com.crosshubber.portal.modules.registry.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Manifest-declared security role ({@code security.roles[]}). */
@JsonInclude(Include.NON_NULL)
public record SecurityRoleDto(String key, String name, String description) {}
