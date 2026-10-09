package com.crosshubber.portal.modules.registry.modulecontents;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Module content / navigation group categories (DB CHECK on {@code
 * registry_module_contents.category} and {@code navigation_groups.category}). Stored as
 * lowercase-hyphen values; the group-capable subset is applications / admin-settings / features /
 * user-settings (V34 fold). {@code SETTINGS} survives only as a parse alias: the retired {@code
 * settings} value folds to {@code admin-settings} so legacy rows and external manifests converge on
 * the folded category.
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

  /** Parses the DB/API value; the retired {@code settings} value folds to admin-settings. */
  public static ModuleContentCategory parse(String raw) {
    if ("settings".equals(raw)) {
      return ADMIN_SETTINGS;
    }
    return valueOf(raw.toUpperCase(Locale.ROOT).replace('-', '_'));
  }

  /** Maps enum ↔ lowercase-hyphen column values. */
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
