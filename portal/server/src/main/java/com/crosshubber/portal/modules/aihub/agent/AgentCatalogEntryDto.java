package com.crosshubber.portal.modules.aihub.agent;

/** One listed entry of the per-user agent tool catalogue. */
public record AgentCatalogEntryDto(
    String modelName,
    String name,
    String kind,
    String moduleKey,
    String description,
    boolean mutates) {}
