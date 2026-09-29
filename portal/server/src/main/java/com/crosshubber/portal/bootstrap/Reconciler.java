package com.crosshubber.portal.bootstrap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.crosshubber.portal.auth.kcadmin.KcAdminClient;
import com.crosshubber.portal.modules.aihub.providers.AiHubProviderEntity;
import com.crosshubber.portal.modules.aihub.providers.AiHubProviderRepository;
import com.crosshubber.portal.modules.i18n.labels.I18nLabelEntity;
import com.crosshubber.portal.modules.i18n.labels.I18nLabelRepository;
import com.crosshubber.portal.modules.i18n.languages.I18nLanguageEntity;
import com.crosshubber.portal.modules.i18n.languages.I18nLanguageRepository;
import com.crosshubber.portal.modules.i18n.settings.I18nSettingsEntity;
import com.crosshubber.portal.modules.i18n.settings.I18nSettingsRepository;
import com.crosshubber.portal.modules.navigation.settings.NavigationSettingsEntity;
import com.crosshubber.portal.modules.navigation.settings.NavigationSettingsRepository;
import com.crosshubber.portal.modules.registry.dto.SecurityRoleDto;
import com.crosshubber.portal.modules.registry.manifest.InstallService;
import com.crosshubber.portal.modules.registry.manifest.ManifestFetcher;
import com.crosshubber.portal.modules.registry.manifest.ManifestValidator;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentCategory;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentEntity;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentRepository;
import com.crosshubber.portal.modules.registry.modulecontents.ModuleContentType;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModuleRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reconciles DB state with embedded catalog and tenant config at boot.
 *
 * <p>Upserts builtin modules/module content, seeds AI Hub providers, navigation settings, i18n
 * languages/labels, and tenant_meta. All steps are idempotent and non-destructive (user data is
 * never touched). Fails fast: any seeding error aborts boot instead of serving an empty portal.
 */
@Component
public class Reconciler implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

  /** Code-owned provider catalog, seeded idempotently at boot. */
  static final List<String[]> SEED_PROVIDERS =
      List.of(
          new String[] {"anthropic", "Anthropic", "https://api.anthropic.com"},
          new String[] {"openai", "OpenAI", "https://api.openai.com/v1"},
          new String[] {
            "google", "Google Vertex AI", "https://generativelanguage.googleapis.com/v1beta"
          },
          new String[] {"deepseek", "DeepSeek", "https://api.deepseek.com"},
          new String[] {"ollama", "Ollama (local)", "http://localhost:11434/v1"},
          new String[] {"openrouter", "OpenRouter", "https://openrouter.ai/api/v1"},
          new String[] {"xai", "xAI", "https://api.x.ai/v1"});

  private final TenantConfigLoader tenantLoader;
  private final ModuleRepository moduleRepo;
  private final ModuleContentRepository contentRepo;
  private final NavigationSettingsRepository navigationSettingsRepo;
  private final I18nLanguageRepository languageRepo;
  private final I18nLabelRepository labelRepo;
  private final I18nSettingsRepository i18nSettingsRepo;
  private final AiHubProviderRepository providerRepo;
  private final TenantMetaRepository tenantMetaRepo;
  private final InstallService installService;
  private final ManifestValidator manifestValidator;
  private final ManifestFetcher manifestFetcher;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactionTemplate;
  private final KcAdminClient kcAdminClient;

  public Reconciler(
      TenantConfigLoader tenantLoader,
      ModuleRepository moduleRepo,
      ModuleContentRepository contentRepo,
      NavigationSettingsRepository navigationSettingsRepo,
      I18nLanguageRepository languageRepo,
      I18nLabelRepository labelRepo,
      I18nSettingsRepository i18nSettingsRepo,
      AiHubProviderRepository providerRepo,
      TenantMetaRepository tenantMetaRepo,
      InstallService installService,
      ManifestValidator manifestValidator,
      ManifestFetcher manifestFetcher,
      ObjectMapper objectMapper,
      TransactionTemplate transactionTemplate,
      KcAdminClient kcAdminClient) {
    this.tenantLoader = tenantLoader;
    this.moduleRepo = moduleRepo;
    this.contentRepo = contentRepo;
    this.navigationSettingsRepo = navigationSettingsRepo;
    this.languageRepo = languageRepo;
    this.labelRepo = labelRepo;
    this.i18nSettingsRepo = i18nSettingsRepo;
    this.providerRepo = providerRepo;
    this.tenantMetaRepo = tenantMetaRepo;
    this.installService = installService;
    this.manifestValidator = manifestValidator;
    this.manifestFetcher = manifestFetcher;
    this.objectMapper = objectMapper;
    this.transactionTemplate = transactionTemplate;
    this.kcAdminClient = kcAdminClient;
  }

  @Override
  public void run(ApplicationArguments args) {
    // Fail-fast: a seeding error must abort boot — never serve an empty portal.
    // Transaction layout: remote I/O (manifest fetches with retry/sleep, KC role sync) runs
    // OUTSIDE any transaction; everything DB-bound is one atomic phase via TransactionTemplate
    // so a failure rolls back all seeding instead of leaving a half-populated tenant.
    TenantConfigLoader.EffectiveTenantConfig tenant = tenantLoader.load();
    log.info("[reconcile] starting for tenant \"{}\"", tenant.slug());
    Map<String, JsonNode> externalManifests = fetchExternalManifests(tenant);
    transactionTemplate.executeWithoutResult(
        status -> {
          reconcileBuiltins(tenant);
          persistExternalModules(tenant, externalManifests);
          reconcileProviders(tenant);
          reconcileNavigationSettings(tenant);
          reconcileI18n(tenant);
          recordTenantMeta(tenant);
        });
    syncExternalRealmRoles(externalManifests);
    syncBuiltinRealmRoles();
    log.info("[reconcile] completed for tenant \"{}\"", tenant.slug());
  }

  /**
   * Network phase (no TX): resolves each external module's manifest (inline or fetched with retry),
   * validates, and key-guards. Any failure aborts boot before a single DB write.
   */
  private Map<String, JsonNode> fetchExternalManifests(
      TenantConfigLoader.EffectiveTenantConfig tenant) {
    Map<String, JsonNode> manifests = new java.util.LinkedHashMap<>();
    for (TenantConfigLoader.DesiredExternalModule ext : tenant.external()) {
      JsonNode rawManifest = ext.manifest();
      if (rawManifest == null && ext.manifestUrl() != null) {
        rawManifest = fetchManifestWithRetry(ext.manifestUrl());
      }
      ManifestValidator.Result parsed = manifestValidator.parse(rawManifest);
      if (!parsed.ok()) {
        throw new IllegalStateException(
            "external module \""
                + ext.key()
                + "\" has an invalid manifest: "
                + ManifestValidator.formatIssues(parsed.issues()));
      }
      if (!ext.key().equals(parsed.manifest().path("key").asString())) {
        throw new IllegalStateException(
            "external module \""
                + ext.key()
                + "\" manifest declares key \""
                + parsed.manifest().path("key").asString()
                + "\"");
      }
      manifests.put(ext.key(), parsed.manifest());
    }
    return manifests;
  }

  /** DB phase (inside the reconciler transaction): install external modules, then activate. */
  private void persistExternalModules(
      TenantConfigLoader.EffectiveTenantConfig tenant, Map<String, JsonNode> externalManifests) {
    List<String> installed = new java.util.ArrayList<>();
    for (TenantConfigLoader.DesiredExternalModule ext : tenant.external()) {
      JsonNode manifest = externalManifests.get(ext.key());
      installService.applyInstall(manifest, "tenant-bootstrap:" + tenant.slug());
      moduleRepo
          .findById(ext.key())
          .ifPresent(
              module -> {
                module.setActive(ext.active());
                moduleRepo.save(module);
              });
      installed.add(ext.key());
    }
    if (!installed.isEmpty()) {
      log.info("[reconcile] external modules installed: [{}]", String.join(", ", installed));
    }
  }

  /** Post-commit (no TX): best-effort KC realm-role sync per installed external module. */
  private void syncExternalRealmRoles(Map<String, JsonNode> externalManifests) {
    for (JsonNode manifest : externalManifests.values()) {
      installService.syncRealmRoles(manifest);
    }
  }

  private void reconcileBuiltins(TenantConfigLoader.EffectiveTenantConfig tenant) {
    validateBuiltinMap(tenant);
    // Tenant-active overrides ("present = config-owned"): a key listed in modules.builtin is
    // re-asserted every boot; omitted keys default to active=true.
    Map<String, Boolean> builtinActive =
        tenant.builtinActive() == null ? Map.of() : tenant.builtinActive();
    int upserted = 0;
    for (EmbeddedCatalog.Module mod : EmbeddedCatalog.CATALOG) {
      Optional<ModuleEntity> existing = moduleRepo.findById(mod.key());
      ModuleEntity entity =
          existing.orElseGet(
              () -> {
                ModuleEntity m = new ModuleEntity();
                m.setKey(mod.key());
                return m;
              });
      entity.setName(mod.name());
      entity.setIcon(mod.icon());
      entity.setVersion(mod.version());
      entity.setBuiltin(true);
      entity.setActive(builtinActive.getOrDefault(mod.key(), true));
      entity.setManagedBy("manual");
      entity.setRoles("");
      try {
        String secRolesJson =
            objectMapper.writeValueAsString(
                mod.securityRoles() != null ? mod.securityRoles() : List.of());
        entity.setSecurityRoles(secRolesJson);
      } catch (Exception e) {
        entity.setSecurityRoles("[]");
      }
      moduleRepo.save(entity);
      // Module content — preserve group_key/sort_order/parent_content_key for builtins (D4):
      // navigation edits made via the UI must survive restarts.
      for (EmbeddedCatalog.Entry ep : mod.moduleContents()) {
        Optional<ModuleContentEntity> epExisting =
            contentRepo.findByModuleKeyAndContentKey(mod.key(), ep.contentKey());
        ModuleContentEntity epEntity =
            epExisting.orElseGet(
                () -> {
                  ModuleContentEntity e = new ModuleContentEntity();
                  e.setModuleKey(mod.key());
                  e.setContentKey(ep.contentKey());
                  e.setSortOrder(ep.sortOrder());
                  return e;
                });
        epEntity.setCategory(ModuleContentCategory.parse(ep.category()));
        epEntity.setName(ep.name());
        epEntity.setType(ModuleContentType.EMBEDDED);
        epEntity.setLoadPath(ep.loadPath());
        epEntity.setColor(ep.color());
        epEntity.setRoles(ep.roles() != null ? String.join(",", ep.roles()) : "");
        epEntity.setActive(true);
        epEntity.setMulti(ep.multi());
        contentRepo.save(epEntity);
      }
      upserted++;
    }
    log.info("[reconcile] upserted {} builtin modules", upserted);
    // Remove retired builtins (guard: builtin=true only removeBuiltin)
    for (String retired : new String[] {"llm-providers", "ai-assistant"}) {
      moduleRepo
          .findById(retired)
          .ifPresent(
              m -> {
                if (Boolean.TRUE.equals(m.getBuiltin())) {
                  moduleRepo.delete(m);
                  log.info("[reconcile] removed retired builtin {}", retired);
                }
              });
    }
    // Retired builtin module content: settings-nav / user-nav were absorbed into the
    // portal-nav screen tabs; the ai-hub chat channels settings page was removed.
    // Delete leftover rows on existing installs.
    for (String[] retiredEp :
        new String[][] {
          {"navigation", "settings-nav"},
          {"navigation", "user-nav"},
          {"ai-hub", "channels"}
        }) {
      contentRepo
          .findByModuleKeyAndContentKey(retiredEp[0], retiredEp[1])
          .ifPresent(
              ep -> {
                contentRepo.delete(ep);
                log.info(
                    "[reconcile] removed retired builtin content {}:{}",
                    retiredEp[0],
                    retiredEp[1]);
              });
    }
  }

  /** Fetches an external manifest with 6 attempts, 3s apart; fail-fast after the last attempt. */
  private JsonNode fetchManifestWithRetry(String url) {
    int attempts = 6;
    long delayMs = 3000;
    RuntimeException lastError = null;
    for (int attempt = 1; attempt <= attempts; attempt++) {
      try {
        return manifestFetcher.fetchManifestFromUrl(url);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("manifest fetch interrupted: " + url, e);
      } catch (Exception e) {
        lastError = e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        if (attempt < attempts) {
          log.warn(
              "[reconcile] manifest fetch failed (attempt {}/{}) for {} — retrying in {}s",
              attempt,
              attempts,
              url,
              delayMs / 1000);
          try {
            Thread.sleep(delayMs);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("manifest fetch interrupted: " + url, ie);
          }
        }
      }
    }
    if (lastError == null) {
      throw new IllegalStateException("manifest fetch failed for " + url);
    }
    throw lastError;
  }

  /** Fail-fast: every builtin map key must exist in the embedded catalog (typo guard). */
  private void validateBuiltinMap(TenantConfigLoader.EffectiveTenantConfig tenant) {
    if (tenant.builtinActive() == null) {
      return;
    }
    Set<String> catalogKeys = new HashSet<>();
    for (EmbeddedCatalog.Module mod : EmbeddedCatalog.CATALOG) {
      catalogKeys.add(mod.key());
    }
    for (String key : tenant.builtinActive().keySet()) {
      if (!catalogKeys.contains(key)) {
        throw new IllegalStateException(
            "modules.builtin references unknown builtin module \"" + key + "\"");
      }
    }
  }

  /** Fail-fast: i18n policy consistency (default/fallback must be inside the enabled set). */
  private void validateI18nPolicy(TenantConfigLoader.I18nPolicy policy) {
    if (policy == null || policy.enabledLanguages() == null) {
      return;
    }
    if (policy.defaultLanguage() != null
        && !policy.enabledLanguages().contains(policy.defaultLanguage())) {
      throw new IllegalStateException(
          "i18n.defaultLanguage \""
              + policy.defaultLanguage()
              + "\" must be in i18n.enabledLanguages");
    }
    if (policy.fallbackLanguage() != null
        && !policy.enabledLanguages().contains(policy.fallbackLanguage())) {
      throw new IllegalStateException(
          "i18n.fallbackLanguage \""
              + policy.fallbackLanguage()
              + "\" must be in i18n.enabledLanguages");
    }
  }

  /** Seeds the AI Hub provider catalog — add-only, never overwrites existing rows. */
  private void reconcileProviders(TenantConfigLoader.EffectiveTenantConfig tenant) {
    int created = 0;
    for (String[] p : SEED_PROVIDERS) {
      if (providerRepo.findById(p[0]).isEmpty()) {
        AiHubProviderEntity e = new AiHubProviderEntity();
        e.setId(p[0]);
        e.setName(p[1]);
        e.setEnabled(false);
        e.setBaseUrl(p[2]);
        providerRepo.save(e);
        created++;
      }
    }
    log.info("[reconcile] ai-hub providers ensured ({} new)", created);
    // Pre-enable provider ids listed in tenant config ("present = config-owned"): ids are
    // validated against the seed catalog; listed rows are asserted enabled=true every boot.
    List<String> enabledProviders = tenant.enabledProviders();
    if (enabledProviders != null) {
      Set<String> seedIds = new HashSet<>();
      for (String[] p : SEED_PROVIDERS) {
        seedIds.add(p[0]);
      }
      for (String id : enabledProviders) {
        if (!seedIds.contains(id)) {
          throw new IllegalStateException(
              "aiHub.enabledProviders references unknown provider \"" + id + "\"");
        }
        providerRepo
            .findById(id)
            .ifPresent(
                p -> {
                  if (!Boolean.TRUE.equals(p.getEnabled())) {
                    p.setEnabled(true);
                    providerRepo.save(p);
                    log.info("[reconcile] ai-hub provider \"{}\" enabled via tenant policy", id);
                  }
                });
      }
    }
  }

  private void reconcileNavigationSettings(TenantConfigLoader.EffectiveTenantConfig tenant) {
    NavigationSettingsEntity settings =
        navigationSettingsRepo
            .findById(1)
            .orElseGet(
                () -> {
                  NavigationSettingsEntity s = new NavigationSettingsEntity();
                  s.setId(1);
                  s.setSettings("{}");
                  return s;
                });
    // Merge tenant settings over current JSON (tenant overlay wins). Defaults are NOT injected
    // here — _default/tenant.json settings{} is the config source; NavigationSettingsService
    // DEFAULT_SETTINGS covers reads when a row key is absent.
    ObjectNode merged;
    try {
      JsonNode current =
          objectMapper.readTree(settings.getSettings() == null ? "{}" : settings.getSettings());
      merged = current.isObject() ? (ObjectNode) current : objectMapper.createObjectNode();
    } catch (Exception e) {
      merged = objectMapper.createObjectNode();
    }
    for (Map.Entry<String, Object> entry : tenant.settings().entrySet()) {
      merged.putPOJO(entry.getKey(), entry.getValue());
    }
    settings.setSettings(merged.toString());
    navigationSettingsRepo.save(settings);
    log.info("[reconcile] navigation_settings ensured: {}", merged);
  }

  private void reconcileI18n(TenantConfigLoader.EffectiveTenantConfig tenant) {
    // Load existing rows once — up to hundreds of per-label findById SELECTs at every boot
    // were the reconciler's largest hidden cost (227 KB seed catalog).
    java.util.Set<String> existingLanguages = new java.util.HashSet<>();
    for (I18nLanguageEntity row : languageRepo.findAll()) {
      existingLanguages.add(row.getCode());
    }
    for (I18nCatalog.Language lang : I18nCatalog.LANGUAGES) {
      if (!existingLanguages.contains(lang.code())) {
        I18nLanguageEntity e = new I18nLanguageEntity();
        e.setCode(lang.code());
        e.setName(lang.name());
        e.setNativeName(lang.nativeName());
        e.setEnabled(lang.enabled());
        e.setSeeded(lang.seeded());
        e.setSortOrder(lang.sortOrder());
        languageRepo.save(e);
        // Track the fresh insert: the label pass below filters on this set, so
        // without it a first-ever boot would seed languages but zero labels.
        existingLanguages.add(lang.code());
      }
    }
    // Seed labels (insert-if-absent ≥ ON CONFLICT DO NOTHING); bump content_version
    // so clients invalidate their label cache when new seed labels appear on an
    // EXISTING install — fresh installs skip the bump.
    boolean existed = i18nSettingsRepo.findById(1).isPresent();
    I18nSettingsEntity s =
        i18nSettingsRepo
            .findById(1)
            .orElseGet(
                () -> {
                  I18nSettingsEntity n = new I18nSettingsEntity();
                  n.setId(1);
                  n.setDefaultLanguage(I18nCatalog.DEFAULT_LANGUAGE);
                  n.setFallbackLanguage(I18nCatalog.DEFAULT_LANGUAGE);
                  n.setOverrides("{}");
                  n.setContentVersion(1);
                  return n;
                });
    java.util.Set<String> existingLabelIds = new java.util.HashSet<>();
    for (I18nLabelEntity row : labelRepo.findAll()) {
      existingLabelIds.add(row.getLanguageCode() + "|" + row.getKey());
    }
    List<I18nLabelEntity> toInsert = new java.util.ArrayList<>();
    int newLabels = 0;
    for (Map.Entry<String, Map<String, String>> langEntry : I18nCatalog.LABELS.entrySet()) {
      String langCode = langEntry.getKey();
      if (!existingLanguages.contains(langCode)) {
        continue;
      }
      for (Map.Entry<String, String> label : langEntry.getValue().entrySet()) {
        String id = langCode + "|" + label.getKey();
        if (!existingLabelIds.contains(id)) {
          I18nLabelEntity e = new I18nLabelEntity();
          e.setLanguageCode(langCode);
          e.setKey(label.getKey());
          e.setValue(label.getValue());
          toInsert.add(e);
          newLabels++;
        }
      }
    }
    if (!toInsert.isEmpty()) {
      labelRepo.saveAll(toInsert);
    }
    // Language policy ("present = config-owned"): applies to seeded catalog rows only —
    // admin-added languages (seeded=false) stay runtime-owned.
    TenantConfigLoader.I18nPolicy policy = tenant.i18n();
    if (policy != null) {
      validateI18nPolicy(policy);
      if (policy.enabledLanguages() != null) {
        for (String code : policy.enabledLanguages()) {
          if (!existingLanguages.contains(code)) {
            throw new IllegalStateException(
                "i18n.enabledLanguages references unknown language \"" + code + "\"");
          }
        }
      }
      applyI18nPolicy(
          policy,
          languageRepo.findAll(),
          I18nCatalog.LANGUAGES.stream()
              .map(lang -> lang.code())
              .collect(java.util.stream.Collectors.toSet()),
          s);
    }
    i18nSettingsRepo.save(s);
    if (newLabels > 0 && existed) {
      s.setContentVersion(s.getContentVersion() + 1);
      i18nSettingsRepo.save(s);
    }
    log.info(
        "[reconcile] i18n seed: languages ensured, {} new labels, contentVersion={}",
        newLabels,
        s.getContentVersion());
  }

  /**
   * Applies the tenant language policy to DB rows ("present = config-owned").
   *
   * <p>Enabled set: asserted only for catalog-seeded rows (matched by code); admin-added rows
   * (seeded=false) are never touched, so runtime language management for custom languages keeps
   * working. Default/fallback override the settings row only when mentioned. No content_version
   * bump — policy changes never alter labels.
   */
  private void applyI18nPolicy(
      TenantConfigLoader.I18nPolicy policy,
      List<I18nLanguageEntity> rows,
      Set<String> catalogCodes,
      I18nSettingsEntity settings) {
    if (policy.enabledLanguages() != null) {
      Set<String> desired = new HashSet<>(policy.enabledLanguages());
      int flipped = 0;
      for (I18nLanguageEntity row : rows) {
        if (Boolean.TRUE.equals(row.getSeeded()) && catalogCodes.contains(row.getCode())) {
          boolean want = desired.contains(row.getCode());
          boolean has = Boolean.TRUE.equals(row.getEnabled());
          if (want != has) {
            row.setEnabled(want);
            languageRepo.save(row);
            flipped++;
          }
        }
      }
      log.info("[reconcile] i18n policy: enabled set asserted ({} rows updated)", flipped);
    } else {
      // No enabled set mentioned: a mentioned default/fallback must exist and stay enabled.
      for (String code : new String[] {policy.defaultLanguage(), policy.fallbackLanguage()}) {
        if (code == null) {
          continue;
        }
        I18nLanguageEntity row =
            rows.stream().filter(r -> r.getCode().equals(code)).findFirst().orElse(null);
        if (row == null) {
          throw new IllegalStateException(
              "i18n policy references unknown language \"" + code + "\"");
        }
        if (!Boolean.TRUE.equals(row.getEnabled())) {
          row.setEnabled(true);
          languageRepo.save(row);
          log.info(
              "[reconcile] i18n policy re-enabled \"{}\" (required as default/fallback)", code);
        }
      }
    }
    if (policy.defaultLanguage() != null
        && !policy.defaultLanguage().equals(settings.getDefaultLanguage())) {
      settings.setDefaultLanguage(policy.defaultLanguage());
    }
    if (policy.fallbackLanguage() != null
        && !policy.fallbackLanguage().equals(settings.getFallbackLanguage())) {
      settings.setFallbackLanguage(policy.fallbackLanguage());
    }
  }

  private void recordTenantMeta(TenantConfigLoader.EffectiveTenantConfig tenant) {
    saveMeta("slug", "\"" + tenant.slug() + "\"");
    saveMeta("name", "\"" + tenant.name() + "\"");
    saveMeta("lifecycle", "\"" + tenant.lifecycle() + "\"");
    saveMeta("revision", tenant.revision() != null ? "\"" + tenant.revision() + "\"" : "null");
    saveMeta("configDigest", "\"" + tenant.digest() + "\"");
    saveMeta("appliedAt", "\"" + Instant.now().toString() + "\"");
    TenantConfigLoader.Branding branding = tenant.branding();
    if (branding != null) {
      saveMeta("branding.name", jsonOrNull(branding.name()));
      saveMeta("branding.title", jsonOrNull(branding.title()));
      saveMeta("branding.logoUrl", jsonOrNull(branding.logoUrl()));
    }
  }

  /** JSON-encodes a branding value or null when absent/blank. */
  private static String jsonOrNull(String value) {
    return value != null && !value.isBlank() ? "\"" + value + "\"" : "null";
  }

  /**
   * Post-commit (no TX): best-effort creation of the portal's builtin realm roles.
   *
   * <p>Forking a tenant into a fresh Keycloak realm previously meant every admin screen 403'd until
   * realm.json was imported by hand. Roles declared in code are created idempotently here; groups,
   * users, and clients remain realm.json's responsibility. Skipped entirely when kc-admin is not
   * configured; failures are logged, never propagate.
   */
  private void syncBuiltinRealmRoles() {
    if (!kcAdminClient.isConfigured()) {
      return;
    }
    try {
      kcAdminClient.ensureRealmRoles(builtinRealmRoles());
      log.info("[reconcile] builtin realm roles ensured");
    } catch (Exception e) {
      log.warn("[reconcile] builtin realm-role sync failed (non-fatal): {}", e.getMessage());
    }
  }

  /**
   * Realm roles the portal enforces in code: every security-relevant role used by
   * {@code @PreAuthorize} checks plus every role declared as a builtin module securityRole.
   */
  static List<SecurityRoleDto> builtinRealmRoles() {
    List<SecurityRoleDto> roles = new ArrayList<>();
    roles.add(new SecurityRoleDto("portal-admin", "Portal admin", "Full portal administration"));
    roles.add(
        new SecurityRoleDto(
            "portal-settings-edit",
            "Portal settings editor",
            "Can manage portal instance settings"));
    roles.add(
        new SecurityRoleDto(
            "portal-registry-edit", "Registry editor", "Can manage the tenant module registry"));
    roles.add(
        new SecurityRoleDto(
            "portal-i18n-edit",
            "I18n editor",
            "Can manage portal languages and translated labels"));
    roles.add(
        new SecurityRoleDto(
            "portal-navigation-edit",
            "Navigation editor",
            "Can manage portal navigation configuration"));
    roles.add(
        new SecurityRoleDto(
            "portal-ai-hub-edit",
            "AI Hub editor",
            "Can manage the AI Hub (providers, tokens, channels)"));
    for (EmbeddedCatalog.Module mod : EmbeddedCatalog.CATALOG) {
      for (String key : mod.securityRoles()) {
        if (roles.stream().noneMatch(r -> r.key().equals(key))) {
          roles.add(new SecurityRoleDto(key, key, "Declared by builtin module securityRoles"));
        }
      }
    }
    return roles;
  }

  private void saveMeta(String key, String valueJson) {
    TenantMetaEntity e =
        tenantMetaRepo
            .findById(key)
            .orElseGet(
                () -> {
                  TenantMetaEntity m = new TenantMetaEntity();
                  m.setKey(key);
                  return m;
                });
    e.setValue(valueJson);
    tenantMetaRepo.save(e);
  }
}
