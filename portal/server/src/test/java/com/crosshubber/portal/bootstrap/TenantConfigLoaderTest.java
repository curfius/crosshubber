package com.crosshubber.portal.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.crosshubber.portal.config.PortalProperties;

import tools.jackson.databind.ObjectMapper;

/** Parsing tests for the tenant config policy blocks (builtin map, i18n, branding, aiHub). */
class TenantConfigLoaderTest {

  @TempDir Path dir;

  private TenantConfigLoader loader(String slug) {
    PortalProperties props = new PortalProperties();
    props.setTenantSlug(slug);
    props.setTenantConfigDir(dir.toString());
    return new TenantConfigLoader(props, new ObjectMapper());
  }

  private void write(String relative, String json) throws IOException {
    Path path = dir.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, json);
  }

  @Test
  void parsesAllPolicyBlocks() throws IOException {
    write(
        "_default/tenant.json",
        """
        {
          "name": "Baseline",
          "modules": {
            "builtin": {"ai-hub": true, "sample-embedded": false},
            "external": []
          },
          "settings": {"homeApp": "portal-navigation:portal", "legacy": null},
          "i18n": {
            "enabledLanguages": ["en-GB", "pt-PT"],
            "defaultLanguage": "en-GB",
            "fallbackLanguage": "en-GB"
          },
          "branding": {"name": "Acme", "title": "Acme Portal", "logoUrl": null},
          "aiHub": {"enabledProviders": ["anthropic"]}
        }
        """);
    TenantConfigLoader.EffectiveTenantConfig config = loader("dev").load();

    assertEquals(Map.of("ai-hub", true, "sample-embedded", false), config.builtinActive());
    assertEquals("portal-navigation:portal", config.settings().get("homeApp"));
    assertTrue(!config.settings().containsKey("legacy"), "null settings keys are released");
    assertEquals("en-GB", config.i18n().defaultLanguage());
    assertEquals("en-GB", config.i18n().fallbackLanguage());
    assertEquals("Acme", config.branding().name());
    assertNull(config.branding().logoUrl());
    assertEquals("anthropic", config.enabledProviders().get(0));
  }

  @Test
  void overlayMergesBuiltinMapPerKey() throws IOException {
    write(
        "_default/tenant.json",
        """
        {
          "modules": {
            "builtin": {"ai-hub": true, "sample-embedded": true, "settings": true},
            "external": []
          }
        }
        """);
    write(
        "dev/tenant.json",
        """
        {"modules": {"builtin": {"sample-embedded": false}}}
        """);
    TenantConfigLoader.EffectiveTenantConfig config = loader("dev").load();

    assertEquals(
        Map.of("ai-hub", true, "sample-embedded", false, "settings", true), config.builtinActive());
  }

  @Test
  void absentBlocksAreNullNotDefaults() throws IOException {
    write(
        "_default/tenant.json",
        """
        {"name": "Baseline", "modules": {"external": []}, "settings": {}}
        """);
    TenantConfigLoader.EffectiveTenantConfig config = loader("dev").load();

    assertNull(config.builtinActive());
    assertNull(config.i18n());
    assertNull(config.branding());
    assertNull(config.enabledProviders());
  }

  @Test
  void nullOverlayBlockReleasesOwnership() throws IOException {
    write(
        "_default/tenant.json",
        """
        {
          "modules": {"builtin": {"ai-hub": false}, "external": []},
          "branding": {"name": "Acme", "title": "Acme Portal"}
        }
        """);
    write("dev/tenant.json", "{\"branding\": {\"name\": null}}");
    TenantConfigLoader.EffectiveTenantConfig config = loader("dev").load();

    assertNull(config.branding().name(), "overlay null releases the field to runtime ownership");
    assertEquals("Acme Portal", config.branding().title(), "untouched fields inherit the baseline");
    assertEquals(Map.of("ai-hub", false), config.builtinActive());
  }

  @Test
  void nonBooleanBuiltinValueFailsFast() throws IOException {
    write(
        "_default/tenant.json",
        """
        {"modules": {"builtin": {"ai-hub": "yes"}, "external": []}}
        """);
    assertThrows(RuntimeException.class, () -> loader("dev").load());
  }

  @Test
  void emptyEnabledLanguagesFailsFast() throws IOException {
    write("_default/tenant.json", "{\"i18n\": {\"enabledLanguages\": []}}");
    assertThrows(RuntimeException.class, () -> loader("dev").load());
  }

  @Test
  void nonStringBrandingFieldFailsFast() throws IOException {
    write("_default/tenant.json", "{\"branding\": {\"name\": 42}}");
    assertThrows(RuntimeException.class, () -> loader("dev").load());
  }

  @Test
  void nonArrayEnabledProvidersFailsFast() throws IOException {
    write("_default/tenant.json", "{\"aiHub\": {\"enabledProviders\": \"anthropic\"}}");
    assertThrows(RuntimeException.class, () -> loader("dev").load());
  }

  @Test
  void supportedConfigVersionLoads() throws IOException {
    write("_default/tenant.json", "{\"configVersion\": 2, \"name\": \"Baseline\"}");
    assertEquals("Baseline", loader("dev").load().name());
  }

  @Test
  void olderConfigVersionFailsFast() throws IOException {
    write("_default/tenant.json", "{\"configVersion\": 1}");
    assertThrows(RuntimeException.class, () -> loader("dev").load());
  }

  @Test
  void nonNumericConfigVersionFailsFast() throws IOException {
    write("_default/tenant.json", "{\"configVersion\": \"two\"}");
    assertThrows(RuntimeException.class, () -> loader("dev").load());
  }

  @Test
  void absentConfigVersionIsTolerated() throws IOException {
    write("_default/tenant.json", "{\"name\": \"Baseline\"}");
    assertEquals("Baseline", loader("dev").load().name());
  }
}
