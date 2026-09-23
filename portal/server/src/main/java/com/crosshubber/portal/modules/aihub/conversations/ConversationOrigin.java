package com.crosshubber.portal.modules.aihub.conversations;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Conversation origin channel (no DB CHECK; default {@code portal}). Telegram/WhatsApp remain
 * representable for legacy rows from the pre-channel-decommission era. Stored as lowercase values.
 */
public enum ConversationOrigin {
  PORTAL,
  TELEGRAM,
  WHATSAPP;

  /** The DB/API value. */
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Parses the DB/API value; throws {@link IllegalArgumentException} when unknown. */
  public static ConversationOrigin parse(String raw) {
    return valueOf(raw.toUpperCase(Locale.ROOT));
  }

  /** Maps enum ↔ lowercase column values. */
  @Converter
  public static class DbConverter implements AttributeConverter<ConversationOrigin, String> {

    @Override
    public String convertToDatabaseColumn(ConversationOrigin attribute) {
      return attribute == null ? null : attribute.value();
    }

    @Override
    public ConversationOrigin convertToEntityAttribute(String dbData) {
      return dbData == null ? null : ConversationOrigin.parse(dbData);
    }
  }
}
