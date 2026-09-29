package com.crosshubber.portal.modules.registry.modulecontents;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Module content types (DB CHECK: {@code iframe, embedded, mfe, link}). Stored as lowercase values.
 */
public enum ModuleContentType {
  IFRAME,
  EMBEDDED,
  MFE,
  LINK;

  /** The DB/API value. */
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Parses the DB/API value; throws {@link IllegalArgumentException} when unknown. */
  public static ModuleContentType parse(String raw) {
    return valueOf(raw.toUpperCase(Locale.ROOT));
  }

  /** Maps enum â†” lowercase column values. */
  @Converter
  public static class DbConverter implements AttributeConverter<ModuleContentType, String> {

    @Override
    public String convertToDatabaseColumn(ModuleContentType attribute) {
      return attribute == null ? null : attribute.value();
    }

    @Override
    public ModuleContentType convertToEntityAttribute(String dbData) {
      return dbData == null ? null : ModuleContentType.parse(dbData);
    }
  }
}
