package com.crosshubber.portal.modules.i18n.dto;

import java.util.List;
import java.util.Map;

/**
 * Runtime i18n config ({@code /api/i18n/config}); all keys always present, including a possibly
 * empty {@code overrides} object.
 */
public record I18nConfigDto(
    List<LanguageDto> languages,
    String defaultLanguage,
    String fallbackLanguage,
    Map<String, Object> overrides,
    int contentVersion) {}
