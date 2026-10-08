package com.crosshubber.portal.modules.registry.manifest;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

import com.crosshubber.portal.common.SecurityUtils;
import com.crosshubber.portal.common.Texts;
import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;

/**
 * Module manifest lifecycle routes. All endpoints are admin-only ({@code portal-registry-edit}).
 */
@RestController
@RequestMapping("/api/registry")
public class ManifestController {

  private static final Logger log = LoggerFactory.getLogger(ManifestController.class);

  private final InstallService installService;
  private final ManifestValidator validator;
  private final ManifestFetcher fetcher;
  private final PortalProperties props;
  private final tools.jackson.databind.ObjectMapper objectMapper;

  public ManifestController(
      InstallService installService,
      ManifestValidator validator,
      ManifestFetcher fetcher,
      PortalProperties props,
      tools.jackson.databind.ObjectMapper objectMapper) {
    this.installService = installService;
    this.validator = validator;
    this.fetcher = fetcher;
    this.props = props;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/active-manifest/{moduleKey}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ManifestPayload activeManifest(@PathVariable String moduleKey) {
    return manifestPayload(installService.activeManifest(moduleKey));
  }

  @GetMapping("/version-manifest/{moduleKey}/{versionId}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> versionManifest(
      @PathVariable String moduleKey, @PathVariable long versionId) {
    validateVersionId(versionId);
    return ResponseEntity.ok(manifestPayload(installService.versionManifest(moduleKey, versionId)));
  }

  @PostMapping("/fetch")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> fetch(@RequestBody Map<String, Object> body) {
    String url = Texts.string(body.get("url"));
    String baseUrl = Texts.string(body.get("baseUrl"));
    if (url == null && baseUrl == null) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "url or baseUrl is required", "code", "invalid-request"));
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
    } catch (ManifestFetchException e) {
      log.warn(
          "[portal] manifest fetch failed url={} code={}: {}",
          e.url(),
          e.code().value(),
          e.getMessage());
      return ResponseEntity.status(fetchStatus(e.code()))
          .body(Map.of("error", e.getMessage(), "code", e.code().value()));
    } catch (Exception e) {
      log.warn("[portal] manifest fetch failed unexpectedly: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(Map.of("error", "fetch failed: " + e.getMessage(), "code", "failed"));
    }
  }

  /** 400/403 for caller mistakes; 502 whenever the upstream fetch itself failed. */
  private static HttpStatus fetchStatus(ManifestFetchException.Code code) {
    return switch (code) {
      case INVALID_URL -> HttpStatus.BAD_REQUEST;
      case BLOCKED -> HttpStatus.FORBIDDEN;
      default -> HttpStatus.BAD_GATEWAY;
    };
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
      ResponseEntity<?> response =
          ResponseEntity.status(201)
              .body(installService.applyInstall(result.manifest(), actor(auth)));
      installService.syncRealmRoles(result.manifest());
      return response;
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
    validateVersionId(versionId);
    InstallService.DraftOutcome result = installService.rollback(moduleKey, versionId, actor(auth));
    if (!result.ok()) {
      return ResponseEntity.badRequest().body(Map.of("error", result.error()));
    }
    return ResponseEntity.ok(
        new RollbackPayload(true, moduleKey, versionId, result.draftId(), result.version()));
  }

  // ── Draft lifecycle ──────────────────────────────────────────────────

  @PostMapping("/draft/{moduleKey}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> createDraft(@PathVariable String moduleKey, Authentication auth) {
    InstallService.DraftOutcome result = installService.createDraft(moduleKey, actor(auth));
    if (!result.ok()) {
      return ResponseEntity.badRequest().body(Map.of("error", result.error()));
    }
    return draftResponse(result, false);
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
    return draftResponse(result, true);
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
    installService.syncRealmRoles(result.manifest());
    return ResponseEntity.ok(
        new ApplyPayload(true, result.moduleKey(), result.version(), result.shouldActivate()));
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
    validateVersionId(versionId);
    InstallService.DraftOutcome result =
        installService.loadVersion(moduleKey, versionId, actor(auth));
    if (!result.ok()) {
      return ResponseEntity.badRequest().body(Map.of("error", result.error()));
    }
    return draftResponse(result, false);
  }

  @GetMapping("/draft/{moduleKey}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ManifestPayload getDraft(@PathVariable String moduleKey) {
    return manifestPayload(installService.latestDraftManifest(moduleKey));
  }

  @GetMapping("/version-download/{moduleKey}/{versionId}")
  @PreAuthorize("hasRole('portal-registry-edit')")
  public ResponseEntity<?> download(@PathVariable String moduleKey, @PathVariable long versionId) {
    validateVersionId(versionId);
    JsonNode manifest = installService.versionManifest(moduleKey, versionId);
    if (manifest == null) {
      return ResponseEntity.status(404).body(Map.of("error", "version not found"));
    }
    String version = installService.versionLabel(moduleKey, versionId);
    String filename = moduleKey + "-v" + (version != null ? version : "unknown") + ".json";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(prettyPrint(manifest));
  }

  // ── Helpers ──────────────────────────────────────────────────────────

  private ManifestPayload manifestPayload(JsonNode manifest) {
    return new ManifestPayload(manifest);
  }

  private ResponseEntity<DraftPayload> draftResponse(
      InstallService.DraftOutcome result, boolean includeVersion) {
    return ResponseEntity.ok(
        new DraftPayload(
            true, result.draftId(), result.manifest(), includeVersion ? result.version() : null));
  }

  /** {@code {"manifest": <JsonNode>}} wrapper — null manifest serializes as {@code null}. */
  record ManifestPayload(JsonNode manifest) {}

  /** Draft lifecycle success payload; {@code version} omitted unless requested. */
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  record DraftPayload(boolean ok, Long draftId, JsonNode manifest, String version) {}

  record RollbackPayload(
      boolean ok, String moduleKey, long versionId, Long draftId, String version) {}

  record ApplyPayload(boolean ok, String moduleKey, String version, boolean shouldActivate) {}

  private ResponseEntity<Map<String, Object>> unprocessable(java.util.List<String> issues) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("error", "invalid manifest");
    out.put("code", "invalid-manifest");
    out.put("issues", issues);
    return ResponseEntity.unprocessableContent().body(out);
  }

  /** Audit actor for install/rollback bookkeeping. */
  private String actor(Authentication auth) {
    PortalUser user = SecurityUtils.principal(auth);
    if (user != null) {
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

  private static void validateVersionId(long versionId) {
    if (versionId <= 0) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "invalid versionId");
    }
  }
}
