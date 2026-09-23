package com.crosshubber.portal.modules.registry.manifest;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Module version lifecycle status (no DB CHECK — the enum is the authoritative value set). Stored
 * as lowercase values: {@code active, superseded, draft, archived}.
 */
public enum VersionStatus {
  ACTIVE,
  SUPERSEDED,
  DRAFT,
  ARCHIVED;

  /** The DB/API value. */
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Parses the DB/API value; throws {@link IllegalArgumentException} when unknown. */
  public static VersionStatus parse(String raw) {
    return valueOf(raw.toUpperCase(Locale.ROOT));
  }

  /** Maps enum ↔ lowercase column values. */
  @Converter
  public static class DbConverter implements AttributeConverter<VersionStatus, String> {

    @Override
    public String convertToDatabaseColumn(VersionStatus attribute) {
      return attribute == null ? null : attribute.value();
    }

    @Override
    public VersionStatus convertToEntityAttribute(String dbData) {
      return dbData == null ? null : VersionStatus.parse(dbData);
    }
  }
}
