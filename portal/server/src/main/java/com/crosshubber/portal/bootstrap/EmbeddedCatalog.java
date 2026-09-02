package com.crosshubber.portal.bootstrap;

import java.util.List;

/**
 * Builtin module catalog.
 *
 * <p>Mirrors {@code portal/src/shared/embedded-catalog.ts} — single source of truth for
 * portal-owned modules.
 */
public final class EmbeddedCatalog {

  private EmbeddedCatalog() {}

  public record Entry(
      String entryKey,
      String category,
      String name,
      String loadPath,
      String color,
      int sortOrder,
      boolean multi,
      List<String> roles) {}

  public record Module(
      String key,
      String name,
      String icon,
      String version,
      List<String> securityRoles,
      List<Entry> entryPoints) {}

  public static final List<Module> CATALOG =
      List.of(
          new Module(
              "portal-dashboard",
              "Dashboard",
              "layout",
              "1.0",
              List.of(),
              List.of(
                  new Entry(
                      "main",
                      "applications",
                      "Dashboard",
                      "portal-dashboard",
                      "#6366f1",
                      -1,
                      false,
                      List.of()))),
          new Module(
              "module-registry",
              "Module registry",
              "grid",
              "1.0",
              List.of(),
              List.of(
                  new Entry(
                      "main",
                      "settings",
                      "Module registry",
                      "module-registry",
                      "#a855f7",
                      0,
                      false,
                      List.of()))),
          new Module(
              "settings",
              "Settings",
              "settings",
              "1.0",
              List.of(),
              List.of(
                  new Entry(
                      "settings-shell",
                      "features",
                      "Settings",
                      "settings",
                      "#64748b",
                      -1,
                      false,
                      List.of()),
                  new Entry(
                      "general",
                      "settings",
                      "General",
                      "portal-general-settings",
                      "#64748b",
                      0,
                      false,
                      List.of()))),
          new Module(
              "user-settings",
              "User Settings",
              "user",
              "1.0",
              List.of(),
              List.of(
                  new Entry(
                      "user-settings-shell",
                      "features",
                      "User Settings",
                      "user-settings",
                      "#ec4899",
                      -1,
                      false,
                      List.of()),
                  new Entry(
                      "general",
                      "user-settings",
                      "General",
                      "user-settings-general",
                      "#ec4899",
                      0,
                      false,
                      List.of()))),
          new Module(
              "navigation",
              "Navigation",
              "compass",
              "1.0",
              List.of(),
              List.of(
                  new Entry(
                      "portal",
                      "applications",
                      "Portal Navigation",
                      "portal-navigation",
                      "#10b981",
                      1,
                      false,
                      List.of()),
                  new Entry(
                      "pinned",
                      "user-settings",
                      "Pinned apps",
                      "navigation-pinned-apps",
                      "#10b981",
                      0,
                      false,
                      List.of()),
                  new Entry(
                      "sidebar",
                      "user-settings",
                      "Sidebar",
                      "navigation-sidebar",
                      "#10b981",
                      1,
                      false,
                      List.of()),
                  new Entry(
                      "settings-nav",
                      "settings",
                      "Settings navigation",
                      "navigation-settings",
                      "#10b981",
                      5,
                      false,
                      List.of("portal-navigation-edit")),
                  new Entry(
                      "user-nav",
                      "settings",
                      "User settings navigation",
                      "navigation-user-settings",
                      "#10b981",
                      6,
                      false,
                      List.of("portal-navigation-edit")),
                  new Entry(
                      "portal-nav",
                      "settings",
                      "Portal navigation",
                      "navigation-portal",
                      "#10b981",
                      7,
                      false,
                      List.of("portal-navigation-edit")))),
          new Module(
              "ai-hub",
              "AI Hub",
              "bot",
              "1.0",
              List.of("portal-ai-hub-edit"),
              List.of(
                  new Entry(
                      "main", "applications", "AI Hub", "ai-hub", "#f59e0b", -3, false, List.of()),
                  new Entry(
                      "settings",
                      "settings",
                      "AI Hub",
                      "ai-hub-settings",
                      "#f59e0b",
                      1,
                      false,
                      List.of()),
                  new Entry(
                      "providers",
                      "settings",
                      "AI Providers",
                      "ai-hub-providers",
                      "#f59e0b",
                      2,
                      false,
                      List.of("portal-ai-hub-edit")),
                  new Entry(
                      "channels",
                      "settings",
                      "Chat Channels",
                      "ai-hub-channels",
                      "#f59e0b",
                      3,
                      false,
                      List.of("portal-ai-hub-edit")),
                  new Entry(
                      "quick-chat",
                      "features",
                      "Quick Chat",
                      "ai-hub-quick-chat",
                      "#f59e0b",
                      3,
                      false,
                      List.of()))),
          new Module(
              "i18n-settings",
              "Internationalisation",
              "globe",
              "1.0",
              List.of(),
              List.of(
                  new Entry(
                      "general",
                      "settings",
                      "Language & region",
                      "i18n-general",
                      "#0ea5e9",
                      2,
                      false,
                      List.of()),
                  new Entry(
                      "languages",
                      "settings",
                      "Languages",
                      "i18n-languages",
                      "#0ea5e9",
                      3,
                      false,
                      List.of()),
                  new Entry(
                      "labels",
                      "settings",
                      "Labels",
                      "i18n-labels",
                      "#0ea5e9",
                      4,
                      false,
                      List.of()))),
          new Module(
              "sample-embedded",
              "Sample embedded",
              null,
              "1.0",
              List.of(),
              List.of(
                  new Entry(
                      "main",
                      "applications",
                      "Sample embedded",
                      "sample-embedded",
                      "#3b82f6",
                      20,
                      true,
                      List.of()))));

  /** Registered load paths for embedded entry points (mirrors EMBEDDED_LOAD_PATHS). */
  public static final List<String> EMBEDDED_LOAD_PATHS =
      CATALOG.stream().flatMap(m -> m.entryPoints().stream()).map(Entry::loadPath).toList();
}
