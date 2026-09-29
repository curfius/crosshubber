package com.crosshubber.portal.modules.registry.modulecontents;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Module content / navigation group categories (DB CHECK on {@code
 * registry_module_contents.category} and {@code navigation_groups.category}). Stored as
 * lowercase-hyphen values; groups accept all values except {@code admin-settings} (4-value subset,
 * V6+), enforced by DB CHECK + request validation.
 */
public enum ModuleContentCategory {
  APPLICATIONS,
  SETTINGS,
  FEATURES,
  ADMIN_SETTINGS,
  USER_SETTINGS;

  /** The DB/API value ({@code admin-settings} etc.). */
  public String value() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /** Parses the DB/API value; throws {@link IllegalArgumentException} when unknown. */
  public static ModuleContentCategory parse(String raw) {
    return valueOf(raw.toUpperCase(Locale.ROOT).replace('-', '_'));
  }

  /** Maps enum â†” lowercase-hyphen column values. */
  @Converter
  public static class DbConverter implements AttributeConverter<ModuleContentCategory, String> {

    @Override
    public String convertToDatabaseColumn(ModuleContentCategory attribute) {
      return attribute == null ? null : attribute.value();
    }

    @Override
    public ModuleContentCategory convertToEntityAttribute(String dbData) {
      return dbData == null ? null : ModuleContentCategory.parse(dbData);
    }
  }
}
