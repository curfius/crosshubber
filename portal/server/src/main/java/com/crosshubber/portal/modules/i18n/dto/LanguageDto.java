package com.crosshubber.portal.modules.i18n.dto;

/** Language entry of the runtime i18n config. */
public record LanguageDto(
    String code, String name, String nativeName, boolean enabled, boolean seeded, int sortOrder) {}
