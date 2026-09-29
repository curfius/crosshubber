package com.crosshubber.portal.shell;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.bootstrap.TenantMetaRepository;

/**
 * Public branding surface ({@code GET /api/branding}).
 *
 * <p>Serves the tenant's configured branding (name/title/logoUrl) written into {@code tenant_meta}
 * by the boot reconciler. Public (permitAll) because the login screen renders before any
 * authenticated call is possible. Falls back to the repo-baseline branding when the tenant config
 * has no {@code branding{}} block.
 */
@RestController
public class BrandingController {

  static final String DEFAULT_NAME = "Crosshubber";

  private final TenantMetaRepository tenantMetaRepo;

  public BrandingController(TenantMetaRepository tenantMetaRepo) {
    this.tenantMetaRepo = tenantMetaRepo;
  }

  @GetMapping("/api/branding")
  public Map<String, Object> branding() {
    String name = readString("branding.name", DEFAULT_NAME);
    String title = readString("branding.title", name + " Portal");
    String logoUrl = readString("branding.logoUrl", null);
    if (logoUrl == null) {
      return Map.of("name", name, "title", title);
    }
    return Map.of("name", name, "title", title, "logoUrl", logoUrl);
  }

  private String readString(String key, String fallback) {
    return tenantMetaRepo
        .findById(key)
        .map(e -> e.getValue())
        .map(
            json -> {
              String trimmed = json.trim();
              if (trimmed.equals("null") || trimmed.length() < 2) {
                return null;
              }
              return trimmed.substring(1, trimmed.length() - 1);
            })
        .filter(v -> v != null && !v.isBlank())
        .orElse(fallback);
  }
}
