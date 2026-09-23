package com.crosshubber.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.crosshubber.portal.modules.aihub.conversations.ConversationOrigin;
import com.crosshubber.portal.modules.navigation.pinnedapps.PinnedNodeType;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointCategory;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointType;
import com.crosshubber.portal.modules.registry.manifest.VersionStatus;

/**
 * Domain enums map to lowercase(-hyphen) DB/API values via their nested converters; unknown values
 * fail loudly instead of silently persisting bad data.
 */
class DomainEnumsTest {

  @Test
  void entryPointCategoryMapsHyphenatedValues() {
    assertEquals("admin-settings", EntryPointCategory.ADMIN_SETTINGS.value());
    assertEquals("user-settings", EntryPointCategory.USER_SETTINGS.value());
    assertEquals(EntryPointCategory.ADMIN_SETTINGS, EntryPointCategory.parse("admin-settings"));

    EntryPointCategory.DbConverter converter = new EntryPointCategory.DbConverter();
    assertEquals("settings", converter.convertToDatabaseColumn(EntryPointCategory.SETTINGS));
    assertEquals(EntryPointCategory.SETTINGS, converter.convertToEntityAttribute("settings"));
    assertNull(converter.convertToDatabaseColumn(null));
    assertNull(converter.convertToEntityAttribute(null));
    assertThrows(IllegalArgumentException.class, () -> EntryPointCategory.parse("bogus"));
  }

  @Test
  void entryPointTypeMapsLowercaseValues() {
    assertEquals("iframe", EntryPointType.IFRAME.value());
    assertEquals(EntryPointType.MFE, EntryPointType.parse("mfe"));

    EntryPointType.DbConverter converter = new EntryPointType.DbConverter();
    assertEquals("embedded", converter.convertToDatabaseColumn(EntryPointType.EMBEDDED));
    assertEquals(EntryPointType.EMBEDDED, converter.convertToEntityAttribute("embedded"));
    assertNull(converter.convertToDatabaseColumn(null));
    assertNull(converter.convertToEntityAttribute(null));
  }

  @Test
  void versionStatusMapsLifecycleValues() {
    assertEquals("superseded", VersionStatus.SUPERSEDED.value());
    assertEquals(VersionStatus.DRAFT, VersionStatus.parse("draft"));

    VersionStatus.DbConverter converter = new VersionStatus.DbConverter();
    assertEquals("active", converter.convertToDatabaseColumn(VersionStatus.ACTIVE));
    assertEquals(VersionStatus.ACTIVE, converter.convertToEntityAttribute("active"));
    assertNull(converter.convertToEntityAttribute(null));
  }

  @Test
  void conversationOriginKeepsLegacyChannelsRepresentable() {
    assertEquals("portal", ConversationOrigin.PORTAL.value());
    assertEquals("whatsapp", ConversationOrigin.parse("whatsapp").value());

    ConversationOrigin.DbConverter converter = new ConversationOrigin.DbConverter();
    assertEquals(ConversationOrigin.TELEGRAM, converter.convertToEntityAttribute("telegram"));
    assertNull(converter.convertToDatabaseColumn(null));
  }

  @Test
  void pinnedNodeTypeMapsFolderAndItem() {
    assertEquals("folder", PinnedNodeType.FOLDER.value());
    assertEquals(PinnedNodeType.ITEM, PinnedNodeType.parse("item"));

    PinnedNodeType.DbConverter converter = new PinnedNodeType.DbConverter();
    assertEquals("item", converter.convertToDatabaseColumn(PinnedNodeType.ITEM));
    assertEquals(PinnedNodeType.FOLDER, converter.convertToEntityAttribute("folder"));
    assertNull(converter.convertToDatabaseColumn(null));
  }
}
