package com.crosshubber.portal.modules.navigation.features;

/** Navigation feature switches ({@code /api/navigation/features}). */
public record FeatureFlagsDto(boolean pinnedAppsEnabled, boolean workspacesEnabled) {}
