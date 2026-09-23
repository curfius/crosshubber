package com.crosshubber.portal.modules.registry.entrypoints;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Entry point / group categories (DB CHECK on {@code entry_points.category} and {@code
 * entry_point_groups.category}). Stored as lowercase-hyphen values; groups accept all values except
 * {@code admin-settings} (4-value subset, V6+), enforced by DB CHECK + request validation.
 */
public enum EntryPointCategory {
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
  public static EntryPointCategory parse(String raw) {
    return valueOf(raw.toUpperCase(Locale.ROOT).replace('-', '_'));
  }

  /** Maps enum ↔ lowercase-hyphen column values. */
  @Converter
  public static class DbConverter implements AttributeConverter<EntryPointCategory, String> {

    @Override
    public String convertToDatabaseColumn(EntryPointCategory attribute) {
      return attribute == null ? null : attribute.value();
    }

    @Override
    public EntryPointCategory convertToEntityAttribute(String dbData) {
      return dbData == null ? null : EntryPointCategory.parse(dbData);
    }
  }
}
