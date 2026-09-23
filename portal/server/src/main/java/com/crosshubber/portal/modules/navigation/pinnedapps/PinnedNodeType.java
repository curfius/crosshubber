package com.crosshubber.portal.modules.navigation.pinnedapps;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Pinned-tree node shapes (DB CHECK: {@code folder, item}). Stored as lowercase values. */
public enum PinnedNodeType {
  FOLDER,
  ITEM;

  /** The DB/API value. */
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Parses the DB/API value; throws {@link IllegalArgumentException} when unknown. */
  public static PinnedNodeType parse(String raw) {
    return valueOf(raw.toUpperCase(Locale.ROOT));
  }

  /** Maps enum ↔ lowercase column values. */
  @Converter
  public static class DbConverter implements AttributeConverter<PinnedNodeType, String> {

    @Override
    public String convertToDatabaseColumn(PinnedNodeType attribute) {
      return attribute == null ? null : attribute.value();
    }

    @Override
    public PinnedNodeType convertToEntityAttribute(String dbData) {
      return dbData == null ? null : PinnedNodeType.parse(dbData);
    }
  }
}
