package com.crosshubber.portal.modules.navigation.pinnedapps;

import java.util.List;

/** Pinned-apps tree node. Folders carry {@code name}, items carry {@code ref} (empty otherwise). */
public record PinnedNodeDto(
    String id, String nodeType, String name, String ref, List<PinnedNodeDto> children) {}
