package com.crosshubber.portal.bootstrap;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.crosshubber.portal.modules.aihub.providers.AiHubProviderEntity;
import com.crosshubber.portal.modules.aihub.providers.AiHubProviderRepository;
import com.crosshubber.portal.modules.i18n.labels.I18nLabelEntity;
import com.crosshubber.portal.modules.i18n.labels.I18nLabelId;
import com.crosshubber.portal.modules.i18n.labels.I18nLabelRepository;
import com.crosshubber.portal.modules.i18n.languages.I18nLanguageEntity;
import com.crosshubber.portal.modules.i18n.languages.I18nLanguageRepository;
import com.crosshubber.portal.modules.i18n.settings.I18nSettingsEntity;
import com.crosshubber.portal.modules.i18n.settings.I18nSettingsRepository;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointEntity;
import com.crosshubber.portal.modules.registry.entrypoints.EntryPointRepository;
import com.crosshubber.portal.modules.registry.manifest.InstallService;
import com.crosshubber.portal.modules.registry.manifest.ManifestFetcher;
import com.crosshubber.portal.modules.registry.manifest.ManifestValidator;
import com.crosshubber.portal.modules.registry.modules.ModuleEntity;
import com.crosshubber.portal.modules.registry.modules.ModuleRepository;
import com.crosshubber.portal.modules.settings.instance.InstanceSettingsEntity;
import com.crosshubber.portal.modules.settings.instance.InstanceSettingsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reconciles DB state with embedded catalog and tenant config at boot.
 *
 * <p>Mirrors {@code portal/src/bootstrap/reconcile.ts} â€” upserts builtin modules/entry points,
 * seeds AI Hub providers, instance settings, i18n languages/labels, and tenant_meta. All steps are
 * idempotent and non-destructive (user data is never touched). Fails fast: any seeding error aborts
 * boot instead of serving an empty portal.
 */
@Component
public class Reconciler implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

  /** Code-owned provider catalog â€” mirrors {@code providers.repository.ts} SEED_PROVIDERS. */
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
  private final EntryPointRepository entryPointRepo;
  private final InstanceSettingsRepository instanceSettingsRepo;
  private final I18nLanguageRepository languageRepo;
  private final I18nLabelRepository labelRepo;
  private final I18nSettingsRepository i18nSettingsRepo;
  private final AiHubProviderRepository providerRepo;
  private final TenantMetaRepository tenantMetaRepo;
  private final InstallService installService;
  private final ManifestValidator manifestValidator;
  private final ManifestFetcher manifestFetcher;
  private final ObjectMapper objectMapper;

  public Reconciler(
      TenantConfigLoader tenantLoader,
      ModuleRepository moduleRepo,
      EntryPointRepository entryPointRepo,
      InstanceSettingsRepository instanceSettingsRepo,
      I18nLanguageRepository languageRepo,
      I18nLabelRepository labelRepo,
      I18nSettingsRepository i18nSettingsRepo,
      AiHubProviderRepository providerRepo,
      TenantMetaRepository tenantMetaRepo,
      InstallService installService,
      ManifestValidator manifestValidator,
      ManifestFetcher manifestFetcher,
      ObjectMapper objectMapper) {
    this.tenantLoader = tenantLoader;
    this.moduleRepo = moduleRepo;
    this.entryPointRepo = entryPointRepo;
    this.instanceSettingsRepo = instanceSettingsRepo;
    this.languageRepo = languageRepo;
    this.labelRepo = labelRepo;
    this.i18nSettingsRepo = i18nSettingsRepo;
    this.providerRepo = providerRepo;
    this.tenantMetaRepo = tenantMetaRepo;
    this.installService = installService;
    this.manifestValidator = manifestValidator;
    this.manifestFetcher = manifestFetcher;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    // Fail-fast: a seeding error must abort boot â€” never serve an empty portal.
    TenantConfigLoader.EffectiveTenantConfig tenant = tenantLoader.load();
    log.info("[reconcile] starting for tenant \"{}\"", tenant.slug());
    reconcileBuiltins();
    reconcileExternalModules(tenant);
    reconcileProviders();
    reconcileInstanceSettings(tenant);
    reconcileI18n();
    recordTenantMeta(tenant);
    log.info("[reconcile] completed for tenant \"{}\"", tenant.slug());
  }

  private void reconcileBuiltins() {
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
      entity.setActive(true);
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
      // Entry points â€” preserve group_key/sort_order/parent_entry_key for builtins (D4):
      // navigation edits made via the UI must survive restarts.
      for (EmbeddedCatalog.Entry ep : mod.entryPoints()) {
        Optional<EntryPointEntity> epExisting =
            entryPointRepo.findByModuleKeyAndEntryKey(mod.key(), ep.entryKey());
        EntryPointEntity epEntity =
            epExisting.orElseGet(
                () -> {
                  EntryPointEntity e = new EntryPointEntity();
                  e.setModuleKey(mod.key());
                  e.setEntryKey(ep.entryKey());
                  e.setSortOrder(ep.sortOrder());
                  return e;
                });
        epEntity.setCategory(ep.category());
        epEntity.setName(ep.name());
        epEntity.setType("embedded");
        epEntity.setLoadPath(ep.loadPath());
        epEntity.setColor(ep.color());
        epEntity.setRoles(ep.roles() != null ? String.join(",", ep.roles()) : "");
        epEntity.setActive(true);
        epEntity.setMulti(ep.multi());
        entryPointRepo.save(epEntity);
      }
      upserted++;
    }
    log.info("[reconcile] upserted {} builtin modules", upserted);
    // Remove retired builtins (guard: builtin=true only â€” mirrors reconcile.ts removeBuiltin)
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
  }

  /**
   * Installs the tenant config's external modules — mirrors {@code reconcileExternalModules} in
   * {@code reconcile.ts}: inline manifest or fetch via {@code fetchManifestWithRetry} (6 attempts,
   * 3s apart; module services boot concurrently with the portal), schema-validate, key-guard,
   * install through the registry flow, then activate per config. Re-installed on every boot (Node
   * parity — each restart records a new module version). Any failure aborts boot.
   */
  private void reconcileExternalModules(TenantConfigLoader.EffectiveTenantConfig tenant) {
    List<String> installed = new java.util.ArrayList<>();
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
      if (!ext.key().equals(parsed.manifest().path("key").asText())) {
        throw new IllegalStateException(
            "external module \""
                + ext.key()
                + "\" manifest declares key \""
                + parsed.manifest().path("key").asText()
                + "\"");
      }
      installService.applyInstall(parsed.manifest(), "tenant-bootstrap:" + tenant.slug());
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

  /** Mirrors {@code fetchManifestWithRetry(url, attempts=6, delayMs=3000)}. */
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
    throw lastError;
  }

  /** Seeds the AI Hub provider catalog â€” add-only, never overwrites existing rows. */
  private void reconcileProviders() {
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
  }

  private void reconcileInstanceSettings(TenantConfigLoader.EffectiveTenantConfig tenant) {
    InstanceSettingsEntity settings =
        instanceSettingsRepo
            .findById(1)
            .orElseGet(
                () -> {
                  InstanceSettingsEntity s = new InstanceSettingsEntity();
                  s.setId(1);
                  s.setSettings("{}");
                  return s;
                });
    // Merge tenant settings over current JSON (tenant overlay wins, matches reconcile.ts)
    ObjectNode merged;
    try {
      JsonNode current =
          objectMapper.readTree(settings.getSettings() == null ? "{}" : settings.getSettings());
      merged = current.isObject() ? (ObjectNode) current : objectMapper.createObjectNode();
    } catch (Exception e) {
      merged = objectMapper.createObjectNode();
    }
    if (merged.isEmpty()) {
      // Defaults mirror Node settings.repository.ts DEFAULT_SETTINGS
      merged.put("homeApp", "portal-navigation:portal");
      merged.put("pinnedAppsEnabled", true);
      merged.put("workspacesEnabled", true);
    }
    for (Map.Entry<String, Object> entry : tenant.settings().entrySet()) {
      merged.putPOJO(entry.getKey(), entry.getValue());
    }
    settings.setSettings(merged.toString());
    instanceSettingsRepo.save(settings);
    log.info("[reconcile] instance_settings ensured: {}", merged);
  }

  private void reconcileI18n() {
    for (I18nCatalog.Language lang : I18nCatalog.LANGUAGES) {
      if (languageRepo.findById(lang.code()).isEmpty()) {
        I18nLanguageEntity e = new I18nLanguageEntity();
        e.setCode(lang.code());
        e.setName(lang.name());
        e.setNativeName(lang.nativeName());
        e.setEnabled(lang.enabled());
        e.setSeeded(lang.seeded());
        e.setSortOrder(lang.sortOrder());
        languageRepo.save(e);
      }
    }
    // Seed labels (insert-if-absent â‰™ ON CONFLICT DO NOTHING); bump content_version
    // so clients invalidate their label cache when new seed labels appear on an
    // EXISTING install â€” fresh installs skip the bump (mirrors i18n.repository.ts).
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
    int newLabels = 0;
    for (Map.Entry<String, Map<String, String>> langEntry : I18nCatalog.LABELS.entrySet()) {
      String langCode = langEntry.getKey();
      if (languageRepo.findById(langCode).isEmpty()) {
        continue;
      }
      for (Map.Entry<String, String> label : langEntry.getValue().entrySet()) {
        boolean exists = labelRepo.findById(new I18nLabelId(langCode, label.getKey())).isPresent();
        if (!exists) {
          I18nLabelEntity e = new I18nLabelEntity();
          e.setLanguageCode(langCode);
          e.setKey(label.getKey());
          e.setValue(label.getValue());
          labelRepo.save(e);
          newLabels++;
        }
      }
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

  private void recordTenantMeta(TenantConfigLoader.EffectiveTenantConfig tenant) {
    saveMeta("slug", "\"" + tenant.slug() + "\"");
    saveMeta("name", "\"" + tenant.name() + "\"");
    saveMeta("lifecycle", "\"" + tenant.lifecycle() + "\"");
    saveMeta("revision", tenant.revision() != null ? "\"" + tenant.revision() + "\"" : "null");
    saveMeta("configDigest", "\"" + tenant.digest() + "\"");
    saveMeta("appliedAt", "\"" + Instant.now().toString() + "\"");
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
