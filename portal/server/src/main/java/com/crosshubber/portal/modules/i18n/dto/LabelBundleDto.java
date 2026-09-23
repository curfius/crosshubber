package com.crosshubber.portal.modules.i18n.dto;

import java.util.Map;

/** Label bundle payload ({@code /api/i18n/labels/{lang}}). */
public record LabelBundleDto(String language, int version, Map<String, String> labels) {}
