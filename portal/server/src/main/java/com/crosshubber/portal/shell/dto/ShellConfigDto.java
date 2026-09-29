package com.crosshubber.portal.shell.dto;

import java.util.List;
import java.util.Map;

import com.crosshubber.portal.modules.navigation.groups.NavigationGroupDto;
import com.crosshubber.portal.modules.registry.dto.ModuleContentDto;

/** {@code GET /api/config} payload. */
public record ShellConfigDto(
    ShellUserDto user,
    Map<String, Object> preferences,
    List<ModuleContentDto> moduleContents,
    List<NavigationGroupDto> navigationGroups,
    List<ShellServiceDto> services) {}
