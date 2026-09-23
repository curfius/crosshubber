package com.crosshubber.portal.modules.navigation.shelltree.dto;

import java.util.List;

import com.crosshubber.portal.modules.registry.dto.EntryPointDto;
import com.crosshubber.portal.modules.registry.dto.EntryPointGroupDto;

/** {@code GET/PUT /api/navigation/shell-tree} payload. */
public record ShellTreePayload(
    String category, List<EntryPointGroupDto> groups, List<EntryPointDto> items) {}
