package com.crosshubber.portal.shell.dto;

import java.util.List;
import java.util.Map;

import com.crosshubber.portal.modules.registry.dto.EntryPointDto;
import com.crosshubber.portal.modules.registry.dto.EntryPointGroupDto;

/** {@code GET /api/config} payload. */
public record ShellConfigDto(
    ShellUserDto user,
    Map<String, Object> preferences,
    List<EntryPointDto> entryPoints,
    List<EntryPointGroupDto> entryPointGroups,
    List<ShellServiceDto> services) {}
