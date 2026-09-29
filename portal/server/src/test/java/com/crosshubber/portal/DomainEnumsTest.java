package com.crosshubber.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.crosshubber.portal.modules.aihub.conversations.ConversationOrigin;
import com.crosshubber.portal.modules.navigation.pinnedapps.PinnedNodeType;
import com.crosshubber.portal.modules.registry.manifest.VersionStatus;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentCategory;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentType;

/**
 * Domain enums map to lowercase(-hyphen) DB/API values via their nested converters; unknown values
 * fail loudly instead of silently persisting bad data.
 */
class DomainEnumsTest {

  @Test
  void moduleContentCategoryMapsHyphenatedValues() {
    assertEquals("admin-settings", ModuleContentCategory.ADMIN_SETTINGS.value());
    assertEquals("user-settings", ModuleContentCategory.USER_SETTINGS.value());
    assertEquals(
        ModuleContentCategory.ADMIN_SETTINGS, ModuleContentCategory.parse("admin-settings"));

    ModuleContentCategory.DbConverter converter = new ModuleContentCategory.DbConverter();
    assertEquals("settings", converter.convertToDatabaseColumn(ModuleContentCategory.SETTINGS));
    assertEquals(ModuleContentCategory.SETTINGS, converter.convertToEntityAttribute("settings"));
    assertNull(converter.convertToDatabaseColumn(null));
    assertNull(converter.convertToEntityAttribute(null));
    assertThrows(IllegalArgumentException.class, () -> ModuleContentCategory.parse("bogus"));
  }

  @Test
  void entryPointTypeMapsLowercaseValues() {
    assertEquals("iframe", ModuleContentType.IFRAME.value());
    assertEquals(ModuleContentType.MFE, ModuleContentType.parse("mfe"));

    ModuleContentType.DbConverter converter = new ModuleContentType.DbConverter();
    assertEquals("embedded", converter.convertToDatabaseColumn(ModuleContentType.EMBEDDED));
    assertEquals(ModuleContentType.EMBEDDED, converter.convertToEntityAttribute("embedded"));
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
