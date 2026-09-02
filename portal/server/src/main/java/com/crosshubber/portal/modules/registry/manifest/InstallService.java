package com.crosshubber.portal.modules.registry.manifest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.auth.kcadmin.KcAdminClient;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointEntity;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointRepository;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModuleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Manifest install/version/draft lifecycle — mirrors {@code
 * portal/src/modules/registry/install.service.ts}.
 */
@Service
public class InstallService {

  private static final Logger log = LoggerFactory.getLogger(InstallService.class);

  private final ModuleRepository moduleRepo;
  private final EntryPointRepository entryPointRepo;
  private final ModuleVersionRepository versionRepo;
  private final ManifestValidator validator;
  private final KcAdminClient kcAdminClient;
  private final ObjectMapper objectMapper;

  public InstallService(
      ModuleRepository moduleRepo,
      EntryPointRepository entryPointRepo,
      ModuleVersionRepository versionRepo,
      ManifestValidator validator,
      KcAdminClient kcAdminClient,
      ObjectMapper objectMapper) {
    this.moduleRepo = moduleRepo;
    this.entryPointRepo = entryPointRepo;
    this.versionRepo = versionRepo;
    this.validator = validator;
    this.kcAdminClient = kcAdminClient;
    this.objectMapper = objectMapper;
  }

  // ── Install ──────────────────────────────────────────────────────────

  /**
   * Applies a manifest install: upserts module (new ones disabled, managed by manifest), upserts
   * entry points (preserving navigation-managed fields), and records the version snapshot. KC role
   * sync is best-effort, outside the transaction.
   */
  @Transactional
  public Map<String, Object> applyInstall(JsonNode manifest, String installedBy) {
    List<String> errors = validator.validateDomain(manifest);
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(ManifestValidator.formatIssues(errors));
    }
    String moduleKey = manifest.path("key").asText();
    String version = nextMajorVersion(moduleKey);
    String digest = digest(manifest);
    String baseUrl = manifest.path("baseUrl").asText();

    // 1. Upsert module (new modules start disabled; active preserved on update)
    ModuleEntity module =
        moduleRepo
            .findById(moduleKey)
            .orElseGet(
                () -> {
                  ModuleEntity created = new ModuleEntity();
                  created.setKey(moduleKey);
                  created.setActive(false);
                  created.setBuiltin(false);
                  return created;
                });
    module.setName(manifest.path("name").asText());
    module.setRoles("");
    module.setVersion(version);
    module.setManifestDigest(digest);
    module.setManagedBy("manifest");
    module.setSourceUrl(baseUrl);
    module.setSecurityRoles(writeJson(validator.declaredRoles(manifest)));
    module.setBaseUrl(baseUrl);
    module.setHealth(manifest.hasNonNull("health") ? manifest.get("health").asText() : null);
    moduleRepo.save(module);

    // 2. Upsert entry points — runtime-managed fields (groupKey, sortOrder,
    // parentEntryKey, active, icon, color) are preserved on conflict (D4).
    for (ManifestValidator.FlatEntry flat : validator.flattenEntries(manifest)) {
      JsonNode entry = flat.entry();
      String entryKey = entry.path("key").asText();
      EntryPointEntity ep =
          entryPointRepo
              .findByModuleKeyAndEntryKey(moduleKey, entryKey)
              .orElseGet(
                  () -> {
                    EntryPointEntity created = new EntryPointEntity();
                    created.setModuleKey(moduleKey);
                    created.setEntryKey(entryKey);
                    created.setSortOrder(0);
                    created.setActive(true);
                    created.setMulti(entry.has("multi") && entry.get("multi").asBoolean());
                    return created;
                  });
      ep.setCategory(flat.category());
      ep.setName(entry.path("name").asText());
      ep.setDescription(entry.hasNonNull("description") ? entry.get("description").asText() : null);
      ep.setType(entry.path("type").asText());
      ep.setUrl(validator.resolveUrl(entry, baseUrl));
      ep.setSandbox(entry.hasNonNull("sandbox") ? entry.get("sandbox").toString() : null);
      ep.setAllow(entry.hasNonNull("allow") ? entry.get("allow").asText() : null);
      ep.setLoadPath(
          "embedded".equals(entry.path("type").asText()) && entry.hasNonNull("loadPath")
              ? entry.get("loadPath").asText()
              : null);
      ep.setEntryUrl(validator.resolveEntryUrl(entry, baseUrl));
      ep.setElement(
          "mfe".equals(entry.path("type").asText()) && entry.hasNonNull("element")
              ? entry.get("element").asText()
              : null);
      ep.setRoles(rolesOrEmpty(entry.get("requiredRoles")));
      entryPointRepo.save(ep);
    }

    // 3. Version snapshot — mark previous active as superseded
    Optional<ModuleVersionEntity> previousActive =
        versionRepo.findFirstByModuleKeyAndStatusOrderByInstalledAtDesc(moduleKey, "active");
    previousActive.ifPresent(
        v -> {
          v.setStatus("superseded");
          versionRepo.save(v);
        });
    ModuleVersionEntity versionRow = new ModuleVersionEntity();
    versionRow.setModuleKey(moduleKey);
    versionRow.setVersion(version);
    versionRow.setDigest(digest);
    versionRow.setManifest(writeJson(manifest));
    versionRow.setInstalledBy(installedBy);
    versionRow.setStatus("active");
    versionRepo.save(versionRow);

    log.info(
        "[install] module \"{}\" v{} installed (digest {}…)",
        moduleKey,
        version,
        digest.substring(0, Math.min(8, digest.length())));

    // 4. Sync roles to KC (best effort — never blocks the install)
    List<Map<String, Object>> roles = validator.declaredRoles(manifest);
    if (!roles.isEmpty() && kcAdminClient.isConfigured()) {
      try {
        kcAdminClient.ensureRealmRoles(roles);
      } catch (Exception e) {
        log.error("[install] KC role sync failed (non-blocking): {}", e.getMessage());
      }
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ok", true);
    result.put("moduleKey", moduleKey);
    result.put("version", version);
    return result;
  }

  // ── Versions ─────────────────────────────────────────────────────────

  @Transactional(readOnly = true)
  public List<Map<String, Object>> listVersions(String moduleKey) {
    return versionRepo.findByModuleKeyOrderByInstalledAtDesc(moduleKey).stream()
        .map(
            v -> {
              Map<String, Object> dto = new LinkedHashMap<>();
              dto.put("id", v.getId());
              dto.put("version", v.getVersion());
              dto.put("digest", v.getDigest());
              dto.put("installedAt", v.getInstalledAt().toString());
              dto.put("installedBy", v.getInstalledBy());
              dto.put("status", v.getStatus());
              return dto;
            })
        .toList();
  }

  @Transactional(readOnly = true)
  public JsonNode activeManifest(String moduleKey) {
    return versionRepo
        .findFirstByModuleKeyAndStatusOrderByInstalledAtDesc(moduleKey, "active")
        .map(v -> readJson(v.getManifest()))
        .orElse(null);
  }

  @Transactional(readOnly = true)
  public JsonNode versionManifest(String moduleKey, long versionId) {
    return versionRepo
        .findById(versionId)
        .filter(v -> v.getModuleKey().equals(moduleKey))
        .map(v -> readJson(v.getManifest()))
        .orElse(null);
  }

  // ── Rollback / drafts ────────────────────────────────────────────────

  /** Rollback creates a DRAFT from an older version — never activates directly. */
  @Transactional
  public DraftOutcome rollback(String moduleKey, long versionId, String rolledBackBy) {
    ModuleVersionEntity target =
        versionRepo
            .findById(versionId)
            .filter(v -> v.getModuleKey().equals(moduleKey))
            .orElse(null);
    if (target == null) {
      return DraftOutcome.fail("version not found");
    }
    JsonNode manifest = readJson(target.getManifest());
    ManifestValidator.Result parsed = validator.parse(manifest);
    if (!parsed.ok()) {
      return DraftOutcome.fail(
          "stored manifest invalid: " + ManifestValidator.formatIssues(parsed.issues()));
    }
    int activeMajor =
        versionRepo
            .findFirstByModuleKeyAndStatusOrderByInstalledAtDesc(moduleKey, "active")
            .map(v -> majorOf(v.getVersion()))
            .orElse(majorOf(target.getVersion()));
    String draftVersion = nextDraftVersion(moduleKey, activeMajor);
    Long draftId = insertDraft(moduleKey, draftVersion, parsed.manifest(), rolledBackBy);
    log.info(
        "[install] rollback \"{}\" v{} -> draft v{} (id={})",
        moduleKey,
        target.getVersion(),
        draftVersion,
        draftId);
    return DraftOutcome.ok(draftId, parsed.manifest(), draftVersion);
  }

  @Transactional
  public DraftOutcome createDraft(String moduleKey, String createdBy) {
    if (versionRepo.existsByModuleKeyAndStatus(moduleKey, "draft")) {
      return DraftOutcome.fail("draft already exists");
    }
    Optional<ModuleVersionEntity> active =
        versionRepo.findFirstByModuleKeyAndStatusOrderByInstalledAtDesc(moduleKey, "active");
    JsonNode manifest;
    String activeVersion;
    if (active.isPresent()) {
      manifest = readJson(active.get().getManifest());
      activeVersion = active.get().getVersion();
    } else {
      ModuleEntity mod = moduleRepo.findById(moduleKey).orElse(null);
      if (mod == null) {
        return DraftOutcome.fail("module not found");
      }
      ObjectNode bootstrap = objectMapper.createObjectNode();
      bootstrap.put("manifestVersion", 1);
      bootstrap.put("key", mod.getKey());
      bootstrap.put("name", mod.getName());
      bootstrap.put("baseUrl", mod.getBaseUrl() != null ? mod.getBaseUrl() : "");
      bootstrap.put("health", mod.getHealth() != null ? mod.getHealth() : "/healthz");
      ObjectNode content = bootstrap.putObject("content");
      content.putArray("applications");
      content.putArray("features");
      content.putArray("adminSettings");
      content.putArray("userSettings");
      bootstrap.putObject("security").putArray("roles");
      manifest = bootstrap;
      activeVersion = "0.1";
    }
    String draftVersion = majorOf(activeVersion) + ".1";
    Long draftId = insertDraft(moduleKey, draftVersion, manifest, createdBy);
    log.info("[install] draft created for \"{}\" as v{} (id={})", moduleKey, draftVersion, draftId);
    return DraftOutcome.ok(draftId, manifest, draftVersion);
  }

  /** Saves a draft as a new minor version (history preserved). */
  @Transactional
  public DraftOutcome saveDraft(String moduleKey, JsonNode manifest, String savedBy) {
    List<ModuleVersionEntity> drafts = versionRepo.findByModuleKeyAndStatus(moduleKey, "draft");
    if (drafts.isEmpty()) {
      return DraftOutcome.fail("no draft found — call createDraft first");
    }
    int baseMajor = majorOf(drafts.get(0).getVersion());
    String newVersion = nextDraftVersion(moduleKey, baseMajor);
    insertDraft(moduleKey, newVersion, manifest, savedBy);
    log.info("[install] draft saved for \"{}\" as v{} (history)", moduleKey, newVersion);
    return DraftOutcome.ok(null, manifest, newVersion);
  }

  /** Promotes the latest draft (or the given form-state manifest) to active. */
  @Transactional
  public ApplyOutcome applyDraft(String moduleKey, String appliedBy, JsonNode incoming) {
    JsonNode manifest;
    if (incoming != null) {
      ManifestValidator.Result parsed = validator.parse(incoming);
      if (!parsed.ok()) {
        return ApplyOutcome.fail(
            "invalid manifest: " + ManifestValidator.formatIssues(parsed.issues()));
      }
      manifest = parsed.manifest();
    } else {
      Optional<ModuleVersionEntity> latestDraft =
          versionRepo.findFirstByModuleKeyAndStatusOrderByInstalledAtDesc(moduleKey, "draft");
      if (latestDraft.isEmpty()) {
        return ApplyOutcome.fail("no draft found");
      }
      manifest = readJson(latestDraft.get().getManifest());
    }
    List<String> errors = validator.validateDomain(manifest);
    if (!errors.isEmpty()) {
      return ApplyOutcome.fail(ManifestValidator.formatIssues(errors));
    }

    ModuleEntity module = moduleRepo.findById(moduleKey).orElse(null);
    boolean shouldActivate = module != null && !Boolean.TRUE.equals(module.getActive());

    // Form-state manifests are archived as superseded draft rows for history
    if (incoming != null) {
      int baseMajor =
          versionRepo
              .findFirstByModuleKeyAndStatusOrderByInstalledAtDesc(moduleKey, "active")
              .map(v -> majorOf(v.getVersion()))
              .orElse(1);
      String archivedVersion = nextDraftVersion(moduleKey, baseMajor);
      ModuleVersionEntity archived = new ModuleVersionEntity();
      archived.setModuleKey(moduleKey);
      archived.setVersion(archivedVersion);
      archived.setDigest(digest(manifest));
      archived.setManifest(writeJson(manifest));
      archived.setInstalledBy(appliedBy);
      archived.setStatus("superseded");
      versionRepo.save(archived);
    }

    Map<String, Object> result = applyInstall(manifest, appliedBy);

    // Archive remaining drafts as superseded (history instead of delete)
    List<ModuleVersionEntity> drafts = versionRepo.findByModuleKeyAndStatus(moduleKey, "draft");
    for (ModuleVersionEntity draft : drafts) {
      draft.setStatus("superseded");
      versionRepo.save(draft);
    }
    log.info(
        "[install] drafts archived after apply for \"{}\" ({} rows)", moduleKey, drafts.size());

    return ApplyOutcome.ok(
        String.valueOf(result.get("moduleKey")),
        String.valueOf(result.get("version")),
        shouldActivate);
  }

  /** Deletes only the most recent draft (history preserved). */
  @Transactional
  public boolean discardDraft(String moduleKey) {
    Optional<ModuleVersionEntity> latest =
        versionRepo.findFirstByModuleKeyAndStatusOrderByInstalledAtDesc(moduleKey, "draft");
    if (latest.isEmpty()) {
      return false;
    }
    versionRepo.delete(latest.get());
    log.info(
        "[install] draft discarded for \"{}\" v{} (id={})",
        moduleKey,
        latest.get().getVersion(),
        latest.get().getId());
    return true;
  }

  /** Loads an old version as a new draft. */
  @Transactional
  public DraftOutcome loadVersion(String moduleKey, long versionId, String loadedBy) {
    ModuleVersionEntity target =
        versionRepo
            .findById(versionId)
            .filter(v -> v.getModuleKey().equals(moduleKey))
            .orElse(null);
    if (target == null) {
      return DraftOutcome.fail("version not found");
    }
    JsonNode manifest = readJson(target.getManifest());
    int activeMajor =
        versionRepo
            .findFirstByModuleKeyAndStatusOrderByInstalledAtDesc(moduleKey, "active")
            .map(v -> majorOf(v.getVersion()))
            .orElse(majorOf(target.getVersion()));
    String draftVersion = nextDraftVersion(moduleKey, activeMajor);
    Long draftId = insertDraft(moduleKey, draftVersion, manifest, loadedBy);
    log.info(
        "[install] loaded version v{} as draft v{} for \"{}\" (id={})",
        target.getVersion(),
        draftVersion,
        moduleKey,
        draftId);
    return DraftOutcome.ok(draftId, manifest, draftVersion);
  }

  @Transactional(readOnly = true)
  public JsonNode latestDraftManifest(String moduleKey) {
    return versionRepo
        .findFirstByModuleKeyAndStatusOrderByInstalledAtDesc(moduleKey, "draft")
        .map(v -> readJson(v.getManifest()))
        .orElse(null);
  }

  // ── Helpers ──────────────────────────────────────────────────────────

  public record DraftOutcome(
      boolean ok, Long draftId, JsonNode manifest, String version, String error) {

    static DraftOutcome ok(Long draftId, JsonNode manifest, String version) {
      return new DraftOutcome(true, draftId, manifest, version, null);
    }

    static DraftOutcome fail(String error) {
      return new DraftOutcome(false, null, null, null, error);
    }
  }

  public record ApplyOutcome(
      boolean ok, String moduleKey, String version, boolean shouldActivate, String error) {

    static ApplyOutcome ok(String moduleKey, String version, boolean shouldActivate) {
      return new ApplyOutcome(true, moduleKey, version, shouldActivate, null);
    }

    static ApplyOutcome fail(String error) {
      return new ApplyOutcome(false, null, null, false, error);
    }
  }

  /** Only counts majors (X.0) — archived minor drafts are ignored. */
  private String nextMajorVersion(String moduleKey) {
    long majors =
        versionRepo.countByModuleKeyAndStatusInAndVersionEndingWith(
            moduleKey, List.of("active", "superseded"), ".0");
    return (majors + 1) + ".0";
  }

  private String nextDraftVersion(String moduleKey, int baseMajor) {
    List<ModuleVersionEntity> drafts = versionRepo.findByModuleKeyAndStatus(moduleKey, "draft");
    int maxMinor = 0;
    for (ModuleVersionEntity draft : drafts) {
      String version = draft.getVersion();
      int dot = version.indexOf('.');
      if (dot > 0 && Integer.parseInt(version.substring(0, dot)) == baseMajor) {
        int minor = Integer.parseInt(version.substring(dot + 1));
        if (minor > maxMinor) {
          maxMinor = minor;
        }
      }
    }
    return baseMajor + "." + (maxMinor + 1);
  }

  private Long insertDraft(String moduleKey, String version, JsonNode manifest, String by) {
    ModuleVersionEntity draft = new ModuleVersionEntity();
    draft.setModuleKey(moduleKey);
    draft.setVersion(version);
    draft.setDigest(digest(manifest));
    draft.setManifest(writeJson(manifest));
    draft.setInstalledBy(by);
    draft.setStatus("draft");
    return versionRepo.save(draft).getId();
  }

  private static int majorOf(String version) {
    int dot = version.indexOf('.');
    try {
      return dot > 0 ? Integer.parseInt(version.substring(0, dot)) : 1;
    } catch (NumberFormatException e) {
      return 1;
    }
  }

  /** sha256 over deterministic (recursively key-sorted) JSON. */
  private String digest(JsonNode manifest) {
    try {
      Object sorted = sortKeys(manifest);
      byte[] bytes = objectMapper.writeValueAsBytes(sorted);
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException("digest failed", e);
    }
  }

  private Object sortKeys(JsonNode node) {
    if (node.isObject()) {
      Map<String, Object> out = new LinkedHashMap<>();
      List<String> names = new java.util.ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      java.util.Collections.sort(names);
      for (String name : names) {
        out.put(name, sortKeys(node.get(name)));
      }
      return out;
    }
    if (node.isArray()) {
      List<Object> out = new java.util.ArrayList<>();
      for (JsonNode item : node) {
        out.add(sortKeys(item));
      }
      return out;
    }
    if (node.isTextual()) {
      return node.asText();
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    if (node.isNumber()) {
      return node.numberValue();
    }
    return node.isNull() ? null : node.toString();
  }

  private JsonNode readJson(String raw) {
    try {
      return objectMapper.readTree(raw);
    } catch (Exception e) {
      throw new IllegalStateException("stored manifest unreadable", e);
    }
  }

  private String writeJson(JsonNode node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new IllegalStateException("manifest serialization failed", e);
    }
  }

  private String writeJson(Object node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new IllegalStateException("serialization failed", e);
    }
  }

  private String rolesOrEmpty(JsonNode roles) {
    String joined = joinRoles(roles);
    return joined != null ? joined : "";
  }

  private String joinRoles(JsonNode roles) {
    if (roles == null || !roles.isArray() || roles.isEmpty()) {
      return null;
    }
    List<String> parts = new java.util.ArrayList<>();
    roles.forEach(r -> parts.add(r.asText()));
    return String.join(",", parts);
  }

  static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
