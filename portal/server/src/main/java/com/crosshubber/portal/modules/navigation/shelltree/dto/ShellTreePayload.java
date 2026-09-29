package com.crosshubber.portal.modules.navigation.shelltree.dto;

import java.util.List;

import com.crosshubber.portal.modules.navigation.groups.NavigationGroupDto;
import com.crosshubber.portal.modules.registry.dto.ModuleContentDto;

/** {@code GET/PUT /api/navigation/shell-tree} payload. */
public record ShellTreePayload(
    String category, List<NavigationGroupDto> groups, List<ModuleContentDto> items) {}
