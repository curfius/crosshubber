package com.crosshubber.portal.modules.registry.entrypoints;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Entry point types (DB CHECK: {@code iframe, embedded, mfe, link}). Stored as lowercase values.
 */
public enum EntryPointType {
  IFRAME,
  EMBEDDED,
  MFE,
  LINK;

  /** The DB/API value. */
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Parses the DB/API value; throws {@link IllegalArgumentException} when unknown. */
  public static EntryPointType parse(String raw) {
    return valueOf(raw.toUpperCase(Locale.ROOT));
  }

  /** Maps enum ↔ lowercase column values. */
  @Converter
  public static class DbConverter implements AttributeConverter<EntryPointType, String> {

    @Override
    public String convertToDatabaseColumn(EntryPointType attribute) {
      return attribute == null ? null : attribute.value();
    }

    @Override
    public EntryPointType convertToEntityAttribute(String dbData) {
      return dbData == null ? null : EntryPointType.parse(dbData);
    }
  }
}
