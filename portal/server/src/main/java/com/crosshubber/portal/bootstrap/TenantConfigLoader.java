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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads effective tenant config.
 *
 * <p>Port of {@code portal/src/bootstrap/tenant-config.ts} — reads {@code _default/tenant.json} +
 * {@code ${slug}/tenant.json} and deep-merges before defaults. Exposes the desired external modules
 * ({@code modules.external[]}) for the boot reconciler.
 */
@Component
public class TenantConfigLoader {

  private static final Logger log = LoggerFactory.getLogger(TenantConfigLoader.class);

  private final PortalProperties props;
  private final ObjectMapper mapper;

  public TenantConfigLoader(PortalProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
  }

  /** Desired external module — mirrors {@code DesiredExternalModule} in tenant-config.ts. */
  public record DesiredExternalModule(
      String key, String serviceKey, String manifestUrl, JsonNode manifest, boolean active) {}

  public record EffectiveTenantConfig(
      String slug,
      String name,
      String lifecycle,
      String revision,
      String digest,
      List<DesiredExternalModule> external,
      Map<String, Object> settings) {}

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
      // Extract minimal fields
      String name = merged.has("name") ? merged.get("name").asText() : slug;
      String lifecycle = merged.has("lifecycle") ? merged.get("lifecycle").asText() : "persistent";
      String revision = merged.has("revision") ? merged.get("revision").asText() : "";
      String digest = Integer.toHexString(merged.toString().hashCode());
      List<DesiredExternalModule> external = new ArrayList<>();
      Map<String, Object> settings = new HashMap<>();
      if (merged.has("modules") && merged.get("modules").isObject()) {
        external = parseExternalModules(merged.get("modules").path("external"));
      }
      if (merged.has("settings") && merged.get("settings").isObject()) {
        mapper
            .<Map<String, Object>>convertValue(
                merged.get("settings"), new TypeReference<Map<String, Object>>() {})
            .forEach(settings::put);
      }
      log.info(
          "[bootstrap] tenant \"{}\" ({}) loaded digest={} externalModules={}",
          slug,
          lifecycle,
          digest,
          external.size());
      return new EffectiveTenantConfig(slug, name, lifecycle, revision, digest, external, settings);
    } catch (Exception e) {
      log.error("[bootstrap] failed to load tenant config", e);
      throw new RuntimeException("tenant config load failed", e);
    }
  }

  /**
   * Parses {@code modules.external[]} — mirrors tenant-config.ts: entries without a string key are
   * skipped silently; entries with neither {@code manifestUrl} nor {@code manifest} are warned and
   * skipped; {@code active} defaults to true.
   */
  private List<DesiredExternalModule> parseExternalModules(JsonNode externalList) {
    List<DesiredExternalModule> external = new ArrayList<>();
    if (!externalList.isArray()) {
      return external;
    }
    for (JsonNode e : externalList) {
      JsonNode keyNode = e.get("key");
      if (keyNode == null || !keyNode.isTextual()) {
        continue;
      }
      String key = keyNode.asText();
      JsonNode manifestUrlNode = e.get("manifestUrl");
      JsonNode manifestNode = e.get("manifest");
      String manifestUrl =
          manifestUrlNode != null && manifestUrlNode.isTextual() ? manifestUrlNode.asText() : null;
      JsonNode manifest = manifestNode != null && manifestNode.isObject() ? manifestNode : null;
      if ((manifestUrl == null || manifestUrl.isBlank()) && manifest == null) {
        log.warn(
            "[tenant-config] external module \"{}\" has no manifestUrl/manifest — skipped", key);
        continue;
      }
      JsonNode serviceKeyNode = e.get("serviceKey");
      String serviceKey =
          serviceKeyNode != null && serviceKeyNode.isTextual() ? serviceKeyNode.asText() : null;
      boolean active =
          !e.has("active") || !e.get("active").isBoolean() || e.get("active").asBoolean();
      external.add(new DesiredExternalModule(key, serviceKey, manifestUrl, manifest, active));
    }
    return external;
  }

  private String resolveDefaultTenantsDir() {
    // Try common locations
    String[] candidates = {
      "config-management/tenants-config",
      "../config-management/tenants-config",
      "C:/playground/projects/crosshubber/config-management/tenants-config"
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
      com.fasterxml.jackson.databind.node.ObjectNode out =
          ((com.fasterxml.jackson.databind.node.ObjectNode) base).deepCopy();
      overlay
          .fields()
          .forEachRemaining(
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
