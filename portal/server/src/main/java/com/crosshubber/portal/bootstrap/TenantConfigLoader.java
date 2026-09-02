package com.crosshubber.portal.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
 * <p>Simplified port of {@code portal/src/bootstrap/tenant-config.ts} — reads {@code
 * _default/tenant.json} + {@code ${slug}/tenant.json} and deep-merges before defaults. For now only
 * supports dev baseline inheritance for settings.
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

  public record EffectiveTenantConfig(
      String slug,
      String name,
      String lifecycle,
      String revision,
      String digest,
      Map<String, Object> builtin,
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
      Map<String, Object> builtin = new HashMap<>();
      Map<String, Object> settings = new HashMap<>();
      if (merged.has("settings") && merged.get("settings").isObject()) {
        mapper
            .<Map<String, Object>>convertValue(
                merged.get("settings"), new TypeReference<Map<String, Object>>() {})
            .forEach((k, v) -> settings.put(k, v));
      }
      log.info("[bootstrap] tenant \"{}\" ({}) loaded digest={}", slug, lifecycle, digest);
      return new EffectiveTenantConfig(slug, name, lifecycle, revision, digest, builtin, settings);
    } catch (Exception e) {
      log.error("[bootstrap] failed to load tenant config", e);
      throw new RuntimeException("tenant config load failed", e);
    }
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
