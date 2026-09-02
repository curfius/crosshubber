package com.crosshubber.portal.modules.registry.manifest;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.security.PortalUser;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Module manifest lifecycle routes — mirrors {@code
 * portal/src/modules/registry/registry.routes.ts}. All endpoints are admin-only ({@code
 * portal-registry-edit}).
 */
@RestController
@RequestMapping("/api/registry")
public class ManifestController {

  private final InstallService installService;
  private final ManifestValidator validator;
  private final ManifestFetcher fetcher;
  private final PortalProperties props;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

  public ManifestController(
      InstallService installService,
      ManifestValidator validator,
      ManifestFetcher fetcher,
      PortalProperties props,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
    this.installService = installService;
    this.validator = validator;
    this.fetcher = fetcher;
    this.props = props;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/active-manifest/{moduleKey}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public Map<String, Object> activeManifest(@PathVariable String moduleKey) {
    return manifestPayload(installService.activeManifest(moduleKey));
  }

  @GetMapping("/version-manifest/{moduleKey}/{versionId}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> versionManifest(
      @PathVariable String moduleKey, @PathVariable long versionId) {
    JsonNode manifest = installService.versionManifest(moduleKey, versionId);
    if (manifest == null) {
      return ResponseEntity.status(404).body(Map.of("error", "version not found"));
    }
    return ResponseEntity.ok(manifestPayload(manifest));
  }

  @PostMapping("/fetch")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> fetch(@RequestBody Map<String, Object> body) {
    String url = string(body.get("url"));
    String baseUrl = string(body.get("baseUrl"));
    if (url == null && baseUrl == null) {
      return ResponseEntity.badRequest().body(Map.of("error", "url or baseUrl is required"));
    }
    try {
      JsonNode raw =
          url != null
              ? fetcher.fetchManifestFromUrl(url)
              : fetcher.fetchManifestFromWellKnown(baseUrl);
      ManifestValidator.Result result = validator.parse(raw);
      if (!result.ok()) {
        return unprocessable(result.issues());
      }
      return ResponseEntity.ok(manifestPayload(result.manifest()));
    } catch (Exception e) {
      return ResponseEntity.status(502).body(Map.of("error", "fetch failed: " + e.getMessage()));
    }
  }

  @PostMapping("/install")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> install(@RequestBody Map<String, Object> body, Authentication auth) {
    if (body == null || body.get("manifest") == null) {
      return ResponseEntity.badRequest().body(Map.of("error", "manifest is required"));
    }
    ManifestValidator.Result result =
        validator.parse(objectMapper.valueToTree(body.get("manifest")));
    if (!result.ok()) {
      return unprocessable(result.issues());
    }
    try {
      return ResponseEntity.ok(installService.applyInstall(result.manifest(), actor(auth)));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.internalServerError()
          .body(Map.of("error", "install failed: " + e.getMessage()));
    }
  }

  @GetMapping("/versions/{moduleKey}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public Map<String, Object> versions(@PathVariable String moduleKey) {
    return Map.of("versions", installService.listVersions(moduleKey));
  }

  @PostMapping("/rollback/{moduleKey}/{versionId}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> rollback(
      @PathVariable String moduleKey, @PathVariable long versionId, Authentication auth) {
    InstallService.DraftOutcome result = installService.rollback(moduleKey, versionId, actor(auth));
    if (!result.ok()) {
      return ResponseEntity.badRequest().body(Map.of("error", result.error()));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ok", true);
    out.put("moduleKey", moduleKey);
    out.put("versionId", versionId);
    out.put("draftId", result.draftId());
    out.put("version", result.version());
    return ResponseEntity.ok(out);
  }

  // ── Draft lifecycle ──────────────────────────────────────────────────

  @PostMapping("/draft/{moduleKey}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> createDraft(@PathVariable String moduleKey, Authentication auth) {
    InstallService.DraftOutcome result = installService.createDraft(moduleKey, actor(auth));
    if (!result.ok()) {
      return ResponseEntity.badRequest().body(Map.of("error", result.error()));
    }
    return draftResponse(result);
  }

  @PutMapping("/draft/{moduleKey}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> saveDraft(
      @PathVariable String moduleKey, @RequestBody Map<String, Object> body, Authentication auth) {
    if (body == null || body.get("manifest") == null) {
      return ResponseEntity.badRequest().body(Map.of("error", "manifest is required"));
    }
    ManifestValidator.Result parsed =
        validator.parse(objectMapper.valueToTree(body.get("manifest")));
    if (!parsed.ok()) {
      return unprocessable(parsed.issues());
    }
    InstallService.DraftOutcome result =
        installService.saveDraft(moduleKey, parsed.manifest(), actor(auth));
    if (!result.ok()) {
      return ResponseEntity.badRequest().body(Map.of("error", result.error()));
    }
    return draftResponse(result);
  }

  @PostMapping("/draft/{moduleKey}/apply")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> applyDraft(
      @PathVariable String moduleKey,
      @RequestBody(required = false) Map<String, Object> body,
      Authentication auth) {
    JsonNode incoming = null;
    if (body != null && body.get("manifest") != null) {
      incoming = objectMapper.valueToTree(body.get("manifest"));
    }
    InstallService.ApplyOutcome result =
        installService.applyDraft(moduleKey, actor(auth), incoming);
    if (!result.ok()) {
      return ResponseEntity.badRequest().body(Map.of("error", result.error()));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ok", true);
    out.put("moduleKey", result.moduleKey());
    out.put("version", result.version());
    out.put("shouldActivate", result.shouldActivate());
    return ResponseEntity.ok(out);
  }

  @DeleteMapping("/draft/{moduleKey}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> discardDraft(@PathVariable String moduleKey) {
    if (!installService.discardDraft(moduleKey)) {
      return ResponseEntity.badRequest().body(Map.of("error", "no draft found"));
    }
    return ResponseEntity.ok(Map.of("ok", true));
  }

  @PostMapping("/load-version/{moduleKey}/{versionId}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> loadVersion(
      @PathVariable String moduleKey, @PathVariable long versionId, Authentication auth) {
    InstallService.DraftOutcome result =
        installService.loadVersion(moduleKey, versionId, actor(auth));
    if (!result.ok()) {
      return ResponseEntity.badRequest().body(Map.of("error", result.error()));
    }
    return draftResponse(result);
  }

  @GetMapping("/draft/{moduleKey}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public Map<String, Object> getDraft(@PathVariable String moduleKey) {
    return manifestPayload(installService.latestDraftManifest(moduleKey));
  }

  @GetMapping("/version-download/{moduleKey}/{versionId}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> download(@PathVariable String moduleKey, @PathVariable long versionId) {
    JsonNode manifest = installService.versionManifest(moduleKey, versionId);
    if (manifest == null) {
      return ResponseEntity.status(404).body(Map.of("error", "version not found"));
    }
    String version =
        installService.listVersions(moduleKey).stream()
            .filter(v -> ((Number) v.get("id")).longValue() == versionId)
            .map(v -> String.valueOf(v.get("version")))
            .findFirst()
            .orElse("unknown");
    String filename = moduleKey + "-v" + version + ".json";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(prettyPrint(manifest));
  }

  // ── Helpers ──────────────────────────────────────────────────────────

  private Map<String, Object> manifestPayload(JsonNode manifest) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("manifest", manifest);
    return out;
  }

  private ResponseEntity<Map<String, Object>> draftResponse(InstallService.DraftOutcome result) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ok", true);
    out.put("draftId", result.draftId());
    out.put("manifest", result.manifest());
    if (result.version() != null) {
      out.put("version", result.version());
    }
    return ResponseEntity.ok(out);
  }

  private ResponseEntity<Map<String, Object>> unprocessable(java.util.List<String> issues) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("error", "invalid manifest");
    out.put("issues", issues);
    return ResponseEntity.unprocessableEntity().body(out);
  }

  /** Audit actor — mirrors registry.routes.ts actor(). */
  private String actor(Authentication auth) {
    if (auth != null && auth.getPrincipal() instanceof PortalUser user) {
      return props.getTenantSlug() + "/" + user.name() + " (" + user.sub() + ")";
    }
    return props.getTenantSlug() + "/unknown";
  }

  private String prettyPrint(JsonNode manifest) {
    try {
      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
    } catch (Exception e) {
      return manifest.toString();
    }
  }

  private static String string(Object value) {
    return value instanceof String s ? s : null;
  }
}
