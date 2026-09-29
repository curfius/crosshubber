package com.crosshubber.portal.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.crosshubber.portal.config.PortalProperties;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads effective tenant config.
 *
 * <p>reads {@code _default/tenant.json} + {@code ${slug}/tenant.json} and deep-merges before
 * defaults. Exposes the desired policy for the boot reconciler: external modules ({@code
 * modules.external[]}), builtin availability overrides ({@code modules.builtin} map), the aiHub
 * provider pre-enable list ({@code aiHub.enabledProviders[]}), the i18n language policy ({@code
 * i18n{}}), and branding ({@code branding{}}). A {@code null} policy block means "not mentioned" →
 * runtime/admin-owned; present blocks are desired state, re-asserted every boot.
 */
@Component
public class TenantConfigLoader {

  private static final Logger log = LoggerFactory.getLogger(TenantConfigLoader.class);

  /** Schema version this loader understands; a declared different version aborts boot. */
  static final int SUPPORTED_CONFIG_VERSION = 2;

  private final PortalProperties props;
  private final ObjectMapper mapper;

  public TenantConfigLoader(PortalProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
  }

  /** Desired external module from the tenant overlay. */
  public record DesiredExternalModule(
      String key, String serviceKey, String manifestUrl, JsonNode manifest, boolean active) {}

  /** i18n language policy from the tenant overlay — each field independently optional. */
  public record I18nPolicy(
      List<String> enabledLanguages, String defaultLanguage, String fallbackLanguage) {}

  /** Branding fields from the tenant overlay; absent null values are preserved. */
  public record Branding(String name, String title, String logoUrl) {}

  /**
   * Tenant policy block. A {@code null} component means "not mentioned in the merged config" →
   * runtime/admin-owned. A non-null component is desired state, asserted by the Reconciler at every
   * boot.
   */
  public record EffectiveTenantConfig(
      String slug,
      String name,
      String lifecycle,
      String revision,
      String digest,
      List<DesiredExternalModule> external,
      Map<String, Object> settings,
      Map<String, Boolean> builtinActive,
      I18nPolicy i18n,
      Branding branding,
      List<String> enabledProviders) {}

  /**
   * Loads and merges tenant configs.
   *
   * @return effective config
   */
  public EffectiveTenantConfig load() {
    String slug = props.getTenantSlug();
    String dir = props.getTenantConfigDir();
    if (dir == null || dir.isBlank()) {
      // Default relative to repo: config-management/tenants-config
      dir = resolveDefaultTenantsDir();
    }
    log.info("[bootstrap] loading tenant \"{}\" from {}", slug, dir);
    try {
      Path basePath = Path.of(dir, "_default", "tenant.json");
      Path tenantPath = Path.of(dir, slug, "tenant.json");
      JsonNode baseline = null;
      if (Files.exists(basePath)) {
        baseline = mapper.readTree(Files.readString(basePath));
      }
      JsonNode tenant = null;
      if (Files.exists(tenantPath)) {
        tenant = mapper.readTree(Files.readString(tenantPath));
      } else {
        log.warn("[bootstrap] tenant file not found: {}, using baseline only", tenantPath);
        tenant = mapper.createObjectNode().put("slug", slug);
      }
      JsonNode merged = deepMerge(baseline, tenant);
      // Schema drift guard: a declared configVersion the loader does not understand aborts boot.
      // Absent version = legacy/minimal config → tolerated with a warning.
      JsonNode version = merged.get("configVersion");
      if (version != null && !version.isNull()) {
        if (!version.isNumber() || version.asInt() != SUPPORTED_CONFIG_VERSION) {
          throw new IllegalStateException(
              "tenant config configVersion "
                  + version.asString()
                  + " is not supported (expected "
                  + SUPPORTED_CONFIG_VERSION
                  + ")");
        }
      } else {
        log.warn(
            "[bootstrap] tenant config has no configVersion — assuming {}",
            SUPPORTED_CONFIG_VERSION);
      }
      // Extract minimal fields
      String name = merged.has("name") ? merged.get("name").asString() : slug;
      String lifecycle =
          merged.has("lifecycle") ? merged.get("lifecycle").asString() : "persistent";
      String revision = merged.has("revision") ? merged.get("revision").asString() : "";
      String digest = Integer.toHexString(merged.toString().hashCode());
      List<DesiredExternalModule> external = new ArrayList<>();
      Map<String, Object> settings = new HashMap<>();
      if (merged.has("modules") && merged.get("modules").isObject()) {
        external = parseExternalModules(merged.get("modules").path("external"));
      }
      Map<String, Boolean> builtinActive =
          merged.has("modules") && merged.get("modules").isObject()
              ? parseBuiltinActive(merged.get("modules").path("builtin"))
              : null;
      I18nPolicy i18n =
          merged.has("i18n") && merged.get("i18n").isObject()
              ? parseI18nPolicy(merged.get("i18n"))
              : null;
      Branding branding =
          merged.has("branding") && merged.get("branding").isObject()
              ? parseBranding(merged.get("branding"))
              : null;
      List<String> enabledProviders =
          merged.has("aiHub") && merged.get("aiHub").isObject()
              ? parseStringList(
                  merged.get("aiHub").path("enabledProviders"), "aiHub.enabledProviders")
              : null;
      if (merged.has("settings") && merged.get("settings").isObject()) {
        mapper
            .<Map<String, Object>>convertValue(
                merged.get("settings"), new TypeReference<Map<String, Object>>() {})
            .forEach(settings::put);
      }
      // Null values mean "released to admin ownership" — never treated as config state.
      settings.values().removeIf(v -> v == null);
      log.info(
          "[bootstrap] tenant \"{}\" ({}) loaded digest={} externalModules={} builtinsPinned={}",
          slug,
          lifecycle,
          digest,
          external.size(),
          builtinActive == null ? "none" : builtinActive.size());
      return new EffectiveTenantConfig(
          slug,
          name,
          lifecycle,
          revision,
          digest,
          external,
          settings,
          builtinActive,
          i18n,
          branding,
          enabledProviders);
    } catch (Exception e) {
      log.error("[bootstrap] failed to load tenant config", e);
      throw new RuntimeException("tenant config load failed", e);
    }
  }

  /**
   * Parses {@code modules.external[]}: entries without a string key are skipped silently; entries
   * with neither {@code manifestUrl} nor {@code manifest} are warned and skipped; {@code active}
   * defaults to true.
   */
  private List<DesiredExternalModule> parseExternalModules(JsonNode externalList) {
    List<DesiredExternalModule> external = new ArrayList<>();
    if (!externalList.isArray()) {
      return external;
    }
    for (JsonNode e : externalList) {
      JsonNode keyNode = e.get("key");
      if (keyNode == null || !keyNode.isString()) {
        continue;
      }
      String key = keyNode.asString();
      JsonNode manifestUrlNode = e.get("manifestUrl");
      JsonNode manifestNode = e.get("manifest");
      String manifestUrl =
          manifestUrlNode != null && manifestUrlNode.isString() ? manifestUrlNode.asString() : null;
      JsonNode manifest = manifestNode != null && manifestNode.isObject() ? manifestNode : null;
      if ((manifestUrl == null || manifestUrl.isBlank()) && manifest == null) {
        log.warn(
            "[tenant-config] external module \"{}\" has no manifestUrl/manifest — skipped", key);
        continue;
      }
      JsonNode serviceKeyNode = e.get("serviceKey");
      String serviceKey =
          serviceKeyNode != null && serviceKeyNode.isString() ? serviceKeyNode.asString() : null;
      boolean active =
          !e.has("active") || !e.get("active").isBoolean() || e.get("active").asBoolean();
      external.add(new DesiredExternalModule(key, serviceKey, manifestUrl, manifest, active));
    }
    return external;
  }

  /**
   * Parses {@code modules.builtin} as a per-key override map ({@code "key": bool}); omitted keys
   * default to active. Unknown/invalid entries fail fast — config typos must abort boot, never
   * silently no-op.
   */
  private Map<String, Boolean> parseBuiltinActive(JsonNode builtin) {
    if (builtin == null || builtin.isMissingNode()) {
      return null;
    }
    if (!builtin.isObject()) {
      throw new IllegalStateException("modules.builtin must be an object map of key → boolean");
    }
    Map<String, Boolean> out = new HashMap<>();
    builtin
        .properties()
        .forEach(
            entry -> {
              String key = entry.getKey();
              JsonNode value = entry.getValue();
              if (value.isNull()) {
                return;
              }
              if (!value.isBoolean()) {
                throw new IllegalArgumentException(
                    "modules.builtin[\"" + key + "\"] must be a boolean");
              }
              out.put(key, value.asBoolean());
            });
    return out;
  }

  /** Parses {@code i18n{}}: per-field independent, null = not mentioned. */
  private I18nPolicy parseI18nPolicy(JsonNode i18n) {
    if (i18n == null || !i18n.isObject()) {
      throw new IllegalArgumentException("i18n must be an object");
    }
    JsonNode langs = i18n.get("enabledLanguages");
    JsonNode def = i18n.get("defaultLanguage");
    JsonNode fallback = i18n.get("fallbackLanguage");
    if (def != null && !(def.isNull() || def.isString())) {
      throw new IllegalArgumentException("i18n.defaultLanguage must be a string");
    }
    if (fallback != null && !(fallback.isNull() || fallback.isString())) {
      throw new IllegalArgumentException("i18n.fallbackLanguage must be a string");
    }
    List<String> enabledLanguages = null;
    if (langs != null && !langs.isNull()) {
      enabledLanguages = parseStringList(langs, "i18n.enabledLanguages");
      if (enabledLanguages.isEmpty()) {
        throw new IllegalArgumentException("i18n.enabledLanguages must not be empty");
      }
    }
    return new I18nPolicy(
        enabledLanguages,
        def == null || def.isNull() ? null : def.asString(),
        fallback == null || fallback.isNull() ? null : fallback.asString());
  }

  /** Parses {@code branding{}}; each field optional, string-typed when present. */
  private Branding parseBranding(JsonNode branding) {
    if (branding == null || !branding.isObject()) {
      throw new IllegalArgumentException("branding must be an object");
    }
    JsonNode name = branding.get("name");
    JsonNode title = branding.get("title");
    JsonNode logoUrl = branding.get("logoUrl");
    if ((name != null && !(name.isNull() || name.isString()))
        || (title != null && !(title.isNull() || title.isString()))
        || (logoUrl != null && !(logoUrl.isNull() || logoUrl.isString()))) {
      throw new IllegalArgumentException(
          "branding.name/title/logoUrl must be strings (logoUrl may be null)");
    }
    return new Branding(
        name == null || name.isNull() ? null : name.asString(),
        title == null || title.isNull() ? null : title.asString(),
        logoUrl == null || logoUrl.isNull() ? null : logoUrl.asString());
  }

  /** Parses a JSON array of non-blank strings; fails fast on anything else. */
  private List<String> parseStringList(JsonNode list, String field) {
    if (list == null || list.isMissingNode()) {
      return null;
    }
    if (!list.isArray()) {
      throw new IllegalArgumentException(field + " must be an array of strings");
    }
    List<String> out = new ArrayList<>();
    for (JsonNode e : list) {
      if (!e.isString() || e.asString().isBlank()) {
        throw new IllegalArgumentException(field + " entries must be non-blank strings");
      }
      out.add(e.asString());
    }
    return out;
  }

  private String resolveDefaultTenantsDir() {
    // Try common relative locations (works for repo root, portal/server, and Docker mounts)
    String[] candidates = {
      "config-management/tenants-config",
      "../config-management/tenants-config",
      "../../config-management/tenants-config"
    };
    for (String c : candidates) {
      if (Files.exists(Path.of(c, "_default", "tenant.json"))) {
        return c;
      }
    }
    return "config-management/tenants-config";
  }

  private JsonNode deepMerge(JsonNode base, JsonNode overlay) {
    if (base == null) {
      return overlay;
    }
    if (overlay == null) {
      return base;
    }
    if (base.isObject() && overlay.isObject()) {
      // Merge objects recursively, overlay wins
      tools.jackson.databind.node.ObjectNode out =
          ((tools.jackson.databind.node.ObjectNode) base).deepCopy();
      overlay
          .properties()
          .forEach(
              e -> {
                String key = e.getKey();
                JsonNode baseVal = out.get(key);
                JsonNode overlayVal = e.getValue();
                if (baseVal != null && baseVal.isObject() && overlayVal.isObject()) {
                  out.set(key, deepMerge(baseVal, overlayVal));
                } else {
                  out.set(key, overlayVal);
                }
              });
      return out;
    }
    // Arrays/scalars: overlay replaces base (matches backoffice-tools deepMerge)
    return overlay;
  }
}
