# Portal Server — Java/Spring Optimization Plan

## Executive Summary

The codebase is a well-structured **Spring Boot 4.1.1 / Java 21 modular monolith** ported from Node.js/TypeScript. While architecturally sound, it carries legacy patterns from the TypeScript port and misses many Spring abstractions. This document outlines all identified optimizations organized by priority and category.

**Completed in Steps 1–8:** #2, #4, #5, #6, #7, #13, #14, #15, #17, #19, #20, #21, #22, #23, #24, #25

**Completed in Step 9 (2026-09-21):** #27 (Reconciler half — network I/O and KC sync moved out of the boot transaction; DB phase is atomic via `TransactionTemplate`), plus the following perf/cleanup pass not tracked as numbered items:

- **Reconciler N+1 at boot** — `reconcileI18n` did a per-label `findById` (hundreds of SELECTs on every boot against the 227 KB seed catalog). Now one `findAll` + in-memory set + `saveAll` batch.
- **InstallService** — KC `ensureRealmRoles` HTTP call removed from inside the install transaction; callers (`ManifestController`, `Reconciler`) invoke `syncRealmRoles(...)` post-commit. Entry-point upsert loads existing rows once per module (was one SELECT per entry).
- **ShellTreeService.saveShellTree** — per-group `findByGroupKey` and per-item `findByModuleKeyAndEntryKey` in the save loops replaced with preloaded maps (was up to 700 SELECTs per save).
- **EntryPointsService.reorder** — `findAllById` batch instead of a SELECT per id.
- **PinnedAppsService.savePinnedTree** — delete+reinsert now collects rows and batch-saves (client UUIDs make per-row flush unnecessary).
- **AiHubProvidersService.getAll** — tokens loaded once and grouped by provider (was one SELECT per provider). NOTE: token masking still decrypts per token by design (mask derives from plaintext; avoiding it needs a stored-mask column + backfill migration).
- **WorkspacesController** — list uses `findByUserIdOrderBySavedAtDesc` (was Java-side sort), update uses `findByUserIdAndId` (was load-all-then-filter); malformed UUID path ids still 404.
- **V22__add_remaining_indexes.sql** — `entry_point_groups(category)`, `entry_point_groups(parent_key)`, `navigation_pinned_apps(parent_id)`.
- **Dead code/deps removed** — `awaitility` + `h2` from pom, H2 datasource block from `application-test.yml`, dead repo methods (`WorkspaceRepository.findByUserId/deleteByUserIdAndName/existsByUserIdAndName`, `AiHubConversationRepository.findByUserId`, `NavigationPinnedAppRepository.findByUserIdOrderBySortOrderAsc`, `I18nLabelRepository.findByKey`), dead `InstallService.utf8` + duplicate `writeJson` overload.

---

## 🔴 HIGH PRIORITY

### 1. Replace `Map<String, Object>` DTOs with Typed Records

**Impact:** ~200+ lines eliminated, compile-time safety, OpenAPI-ready

Every service returns `Map<String, Object>` instead of typed DTOs. This is the single most pervasive anti-pattern across the codebase.

**Affected files:**
- `ModulesService.toOutput()` — lines 38-72
- `EntryPointsService.toOutput()` — lines 42-91
- `AiHubConversationsService.conversationDto()/messageDto()` — lines 31-51
- `ShellConfigService.buildConfig()` — lines 62-149 (87-line god method building Maps)
- `ShellTreeService.shellTreePayload()` — lines 42-57
- `InstanceSettingsService` — lines 41-72
- `UserSettingsService` — lines 39-78
- `WorkspacesController.listItem()/detail()` — lines 155-180 (controller has DTO logic)

**Approach:**
1. Create Java records per domain (e.g., `ModuleDto`, `EntryPointDto`, `ConversationDto`)
2. Use `@JsonInclude(NON_NULL)` annotations on records to handle optional fields
3. Replace `toOutput()` / `toPublic()` methods with record constructors or static factory methods
4. Ensure consistent JSON key casing (camelCase everywhere, fix snake_case in `AiHubConversationsService`)

**Example:**
```java
public record ModuleDto(
    String key,
    String name,
    String icon,
    String version,
    boolean active,
    boolean builtin,
    String category,
    String managedBy,
    String roles,
    String manifestDigest,
    String health,
    @JsonInclude.Include(JsonInclude.Include.NON_NULL) Instant createdAt,
    @JsonInclude.Include(JsonInclude.Include.NON_NULL) Instant updatedAt
) {
    public static ModuleDto fromEntity(ModuleEntity e) {
        return new ModuleDto(
            e.getKey(), e.getName(), e.getIcon(), e.getVersion(),
            e.isActive(), e.isBuiltin(), e.getCategory(), e.getManagedBy(),
            e.getRoles(), e.getManifestDigest(), e.getHealth(),
            e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}
```

---

### 2. Extract Duplicated `parseJson`/`writeJson` into Shared Utility

**Impact:** 10+ duplicate implementations eliminated

**Affected files:**
- `InstanceSettingsService.java` — lines 74-92
- `UserSettingsService.java` — lines 80-98
- `ShellConfigService.java` — lines 160-174
- `I18nService.java` — lines 187-198
- `NavigationUserSettingsService.java` — lines 185-196
- `NavigationLayoutService.java` — lines 71-83
- `AiHubChannelsService.java` — lines 214-231, 262
- `ModuleSettingsService.java` — lines 58-75
- `ChannelPipeline.java` — lines 245-254
- `WorkspacesController.java` — lines 182-193

**Approach:**
1. Create `com.crosshubber.portal.common.JsonUtils` Spring bean
2. Inject `ObjectMapper` via constructor
3. Provide `parseJson(String, TypeReference<T>)` and `writeJson(Object)` methods
4. Replace all 10+ local implementations with calls to this utility

**Example:**
```java
@Component
public class JsonUtils {

    private final ObjectMapper mapper;

    public JsonUtils(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public <T> T parseJson(String raw, TypeReference<T> typeRef) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return mapper.readValue(raw, typeRef);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse JSON", e);
        }
    }

    public Map<String, Object> parseMap(String raw) {
        if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
        try {
            return mapper.readValue(raw, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse JSON map", e);
        }
    }

    public String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }
}
```

---

### 3. Fix `ShellConfigService.buildConfig()` — Duplicate DB Queries + God Method

**Impact:** Performance + maintainability

**Issues:**
- `moduleRepo.findAll()` called **twice** (lines 68 and 73) — duplicate query
- All groups and entry points loaded from DB, then filtered in Java
- 87-line single method doing too much (God Method anti-pattern)
- Role-based filtering done in Java instead of SQL

**Approach:**
1. Remove the duplicate `moduleRepo.findAll()` call
2. Push role-based filtering to repository `@Query` methods:
   ```java
   @Query("SELECT m FROM ModuleEntity m WHERE m.active = true AND (m.roles = '' OR m.roles LIKE %:role%)")
   List<ModuleEntity> findActiveForRole(@Param("role") String role);
   ```
3. Decompose the method into smaller private methods:
   - `loadModulesForUser(PortalUser user)`
   - `loadGroupsForUser(PortalUser user)`
   - `loadEntryPointsForUser(PortalUser user)`
   - `buildModuleConfig(ModuleEntity, List<EntryPointEntity>, List<EntryPointGroupEntity>)`
4. Fix the O(n²) `noneMatch` pattern in group DTO construction (lines 114-134)

---

### 4. ~~Call `validate()` in `EntryPointsService.upsert()`~~

**Status:** ✅ RESOLVED — `EntryPointsController` already calls `validate()` before both `upsert()` paths (lines 40 and 65). Original claim was incorrect.

**Approach:**
1. Call `validate(body)` at the start of `upsert()`
2. Alternatively, migrate validation to a DTO with `@Valid` annotations (see item #9)
3. Remove the now-unused `validate()` method from the service

---

### 5. ~~URL-encode Parameters in `KeycloakService.logoutUrl()`~~

**Status:** ✅ DONE (Step 6) — `URLEncoder.encode()` applied + `KeycloakServiceTest` with injection-attempt assertions.

**Approach:**
```java
String logoutUrl() {
    return kcBaseUrl + "/realms/" + URLEncoder.encode(kcRealm, StandardCharsets.UTF_8)
        + "/protocol/openid-connect/logout"
        + "?client_id=" + URLEncoder.encode(kcClientId, StandardCharsets.UTF_8)
        + "&post_logout_redirect_uri=" + URLEncoder.encode(publicBaseUrl, StandardCharsets.UTF_8);
}
```

---

### 6. ~~Add Missing Database Indexes~~

**Status:** ✅ DONE (Step 7) — V21 migration created with composite `(user_id, origin, updated_at DESC)`, `entry_points(category)`, `entry_points(group_key)`, `module_versions(module_key, installed_at DESC)`.

| Table | Column(s) | Query Pattern | Priority |
|---|---|---|---|
| `ai_hub_conversations` | `(user_id)` | `findByUserId` — main user list | High |
| `ai_hub_conversations` | `(user_id, origin, updated_at)` | `findByUserIdAndOriginOrderByUpdatedAtDesc` | High |
| `entry_points` | `(category)` | `findByCategory` — category filter | Medium |
| `entry_points` | `(group_key)` | `findByGroupKey` — group lookup | Medium |
| `module_versions` | `(module_key, installed_at)` | `findByModuleKeyOrderByInstalledAtDesc` | Medium |
| `module_versions` | `(module_key, status)` | `findByModuleKeyAndStatus` | Low |
| `ai_hub_channels` | `(enabled)` | `findByEnabledTrue` | Low |

**Approach:** Create a new Flyway migration `V12__add_performance_indexes.sql`:
```sql
CREATE INDEX IF NOT EXISTS idx_ai_hub_conversations_user
    ON ai_hub_conversations (user_id);

CREATE INDEX IF NOT EXISTS idx_ai_hub_conversations_user_origin_updated
    ON ai_hub_conversations (user_id, origin, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_entry_points_category
    ON entry_points (category);

CREATE INDEX IF NOT EXISTS idx_entry_points_group_key
    ON entry_points (group_key);

CREATE INDEX IF NOT EXISTS idx_module_versions_module_installed
    ON module_versions (module_key, installed_at DESC);
```

---

### 7. ~~Enable Spring Data JPA Auditing~~

**Status:** ❌ CANCELLED (Step 7) — `BaseEntity` callbacks correct + centralized; NOT NULL constraints + `ddl-auto=validate` smoke test cover regressions. Migrating to `@CreatedDate`/`AuditorAware` is stylistic churn with regression risk.

**Approach:**
1. Add `@EnableJpaAuditing` to a configuration class
2. Implement `AuditorAware<String>` that resolves from Spring Security principal
3. Replace manual `@PrePersist`/`@PreUpdate` callbacks with auditing annotations:
   ```java
   @CreatedDate
   @Column(updatable = false)
   private Instant createdAt;

   @LastModifiedDate
   private Instant updatedAt;
   ```

---

## 🟡 MEDIUM PRIORITY

### 8. Remove Web-Layer Exceptions from Service Layer

**Impact:** Architecture — services should not depend on Spring MVC exceptions

**Issue:** Services throw `ResponseStatusException` directly:
- `ModulesService.java` — lines 79, 83, 109, 120, 122
- `EntryPointsService.java` — line 311
- `AiHubConversationsService.java` — line 158

**Approach:**
1. Create domain exceptions:
   - `EntityNotFoundException` → 404
   - `ConflictException` → 409
   - `ValidationException` → 400
   - `BusinessRuleException` → 422
2. Map these in `GlobalExceptionHandler` to appropriate HTTP status codes
3. Services throw domain exceptions, controllers remain HTTP-agnostic

---

### 9. Adopt Jakarta Bean Validation (`@Valid`)

**Impact:** ~100+ lines of manual validation eliminated

**Issue:** Zero use of `@Valid`, `@NotNull`, `@NotBlank` in controllers. Validation is entirely manual and inconsistent.

**Approach:**
1. Define DTO records with validation annotations:
   ```java
   public record CreateModuleRequest(
       @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,63}$") String key,
       @NotBlank @Size(max = 100) String name,
       String icon,
       String version
   ) {}
   ```
2. Use `@Valid` on controller parameters
3. Remove manual validation from services
4. The existing `MethodArgumentNotValidException` handler in `GlobalExceptionHandler` already handles validation errors

---

### 10. Extract Shared Constants and Helper Methods

**Impact:** DRY — eliminate 10+ copies of identical code

| Duplicated | Copies | Location |
|---|---|---|
| `KEY_RE` regex | 5 | `ModulesService`, `EntryPointsService`, `EntryPointGroupsService`, `UserSettingsController`, `ManifestValidator` |
| `LANG_CODE_RE` regex | 3 | `I18nLabelsController`, `I18nLanguagesController`, `I18nSettingsController` |
| `REF_RE` regex | 2 | `SettingsController`, `NavigationValidationService` |
| `string(Object)` helper | 4 | `ModulesService`, `EntryPointsService`, `AiHubChatController`, `ManifestController` |
| `notBlank(String)` helper | 3 | `ModulesService`, `EntryPointsService`, `KcAdminClient` |
| `currentUserSub()`/`currentUserRoles()` | 2 | `ModuleSettingsController`, `I18nLabelsController` |

**Approach:**
1. Create `DomainConstants` for regex patterns
2. Create `StringUtils` for `string()`, `notBlank()`, `orEmpty()`, `stringOr()`, `intOr()`, `boolOr()`
3. Create `SecurityUtils` for `currentUserSub()`, `currentUserRoles()`

---

### 11. Add `@Enumerated(EnumType.STRING)` to String Fields

**Impact:** Type safety — 8+ fields with DB CHECK constraints mapped as raw String

| Entity | Field | DB CHECK constraint |
|---|---|---|
| `EntryPointEntity` | `category` | `'applications','settings','features','admin-settings','user-settings'` |
| `EntryPointEntity` | `type` | `'iframe','embedded','mfe','link'` |
| `AiHubChannelEntity` | `type` | `'telegram','whatsapp'` |
| `AiHubChannelEntity` | `deliveryMode` | `'webhook','polling'` |
| `AiHubConversationEntity` | `origin` | `'portal','telegram','whatsapp'` |
| `AiHubMessageEntity` | `role` | `'user','assistant'` |
| `ModuleVersionEntity` | `status` | `'active','inactive'` |

**Approach:**
1. Create Java enums matching DB CHECK constraints
2. Add `@Enumerated(EnumType.STRING)` to entity fields
3. Update services to use enum types instead of strings

---

### 12. Add Optimistic Locking (`@Version`) on Singleton Entities

**Impact:** Data integrity — concurrent updates can cause lost updates

**Affected entities:**
- `InstanceSettingsEntity` (singleton row id=1)
- `NavigationLayoutEntity` (singleton row id=1)
- `ModuleSettingsEntity` (per-module singleton)
- `WorkspaceEntity` (concurrent saves from same user)

**Approach:**
1. Add `@Version` field to affected entities
2. Add `OptimisticLockingFailureException` handler in `GlobalExceptionHandler`
3. Return 409 Conflict when version mismatch occurs

---

### 13. Use Spring Abstractions for HTTP Clients

**Impact:** Connection pooling, metrics, consistency

**Issue:** Three places create raw `HttpClient` or `RestClient` manually:
- `ChatCompletionService.java` — line 42: `HttpClient.newBuilder().connectTimeout(...).build()`
- `ProxyService.java` — lines 34-38: `HttpClient.newBuilder().build()`
- `KcAdminClient.java` — line 35: `RestClient.create()` without base URL or timeouts

**Approach:**
1. Create a `RestClient` bean with configured timeouts and connection pooling
2. Inject it into services instead of creating clients manually
3. Alternatively, use Spring's `RestTemplate` or `WebClient` for reactive streaming

---

### 14. ~~Standardize HTTP Status Codes~~

**Status:** ✅ DONE (Step 8) — DELETE endpoints returning trivial `{"ok": true}` → 204 No Content (AiHub conversations, providers tokens, providers). POST resource creation → 201 Created (workspaces, modules, entry points, entry point groups, providers, conversations, manifest install). Auth failure handling was already correct.

**Approach:** Standardize across all controllers:
- `POST` create → `201 Created` with `Location` header
- `DELETE` → `204 No Content` (empty body)
- `PUT`/`PATCH` → `200 OK` with updated resource
- Auth failures → `401 Unauthorized`

---

### 15. ~~Fix Silent Exception Swallowing~~

**Status:** ✅ DONE (Step 6) — `ShellConfigService.parseJson` + `ModulesService.parseSecurityRoles` now log. `ChatCompletionService.pumpStream` deleted during Spring AI 2.0 migration (fixed by `doOnError`). `KeycloakService.parseUser` / `KcAdminClient.getClientRoles` already had logging.

**Approach:**
1. Add `log.warn(...)` or `log.error(...)` to all catch blocks
2. For `ChatCompletionService`, add structured logging with conversation context
3. Consider using `log.warn("Fallback used", e)` to preserve exception details

---

### 16. Use `@Cacheable` Instead of Manual Caffeine Cache

**Impact:** Declarative caching, testability

**Issue:** `ProxyService` manually manages a `Caffeine.newBuilder()` cache (lines 28-33).

**Approach:**
1. Configure Spring Cache with Caffeine cache manager
2. Replace manual cache with `@Cacheable("entryPoints")` on the lookup method
3. Use `@CacheEvict` for cache invalidation
4. Configure TTL via `application.yml`

---

### 17. Use `@PreAuthorize` Consistently

**Impact:** Security consistency — mixed auth patterns

**Issue:** Manual `user == null` checks in:
- `UserSettingsController.java` — lines 44, 53, 67 (manual 401)
- `ShellConfigController.java` — lines 24-25 (manual 401)
- `AiHubChatController.java` — **no auth annotation at all** (security gap)

While other controllers correctly use `@PreAuthorize`.

**Approach:**
1. Add `@PreAuthorize("isAuthenticated()")` to all protected endpoints
2. Remove manual `user == null` checks — Spring Security handles this
3. Ensure `AiHubChatController` has proper authentication
4. Use `@AuthenticationPrincipal PortalUser user` parameter injection consistently

---

## 🟢 LOW PRIORITY

### 18. Replace In-Memory `ConcurrentHashMap` Sessions with Spring Session

**Issue:** `SessionService` (line 21) uses plain `ConcurrentHashMap`:
- Sessions lost on restart
- Not shared across instances
- Full token stored as map key (memory waste)

**Approach:**
1. Configure Spring Session with Redis or JDBC backend
2. Remove manual session management code
3. Use `@EnableSpringHttpSession` with proper backend

---

### 19. ~~Fix `GlobalExceptionHandler` — Return All Validation Errors~~

**Status:** ✅ DONE (Step 8) — Changed from `.findFirst()` to `.reduce((a, b) -> a + "; " + b)` to return all field errors joined.
```java
.map(f -> f.getField() + " " + f.getDefaultMessage())
.findFirst()
```

**Approach:** Return all errors as a list:
```java
var errors = ex.getBindingResult().getFieldErrors().stream()
    .map(f -> Map.of("field", f.getField(), "message", f.getDefaultMessage()))
    .toList();
return ResponseEntity.badRequest().body(Map.of("errors", errors));
```

---

### 20. ~~Fix DNS Rebinding in `SsrfGuard`~~

**Status:** ✅ DONE (Step 8) — Restructured to expose `resolveAndValidate(rawUrl, allowPrivate)` returning validated `InetAddress[]`. Private-domain early-return paths now also resolve and validate. `assertSafeUrl` delegates to `resolveAndValidate`.

**Approach:** Resolve DNS once, pass resolved `InetAddress` to caller for connection:
```java
public InetAddress resolveAndValidate(String host) {
    InetAddress address = InetAddress.getByName(host);
    if (isPrivate(address)) {
        throw new SsrfBlockedException("Blocked private address: " + host);
    }
    return address;
}
```

---

### 21. ~~Thread-Safety in `CryptoService`~~

**Status:** ✅ DONE (Step 6) — `volatile` + double-checked locking applied.

**Approach:**
1. Make `keyBytes` volatile
2. Or use eager initialization in constructor
3. Or use `Supplier` with double-checked locking

---

### 22. ~~Health Endpoint Should Return 503 When DB Is Down~~

**Status:** ✅ DONE (Step 6) — Returns 503 + `ok:false` when DB is down.

**Approach:**
```java
HttpStatus status = up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
return ResponseEntity.status(status).body(Map.of("ok", up, ...));
```

---

### 23. ~~Add `@JsonIgnore` on Sensitive Entity Fields~~

**Status:** ✅ RESOLVED — `AiHubTokenEntity.encryptedKey` → `WRITE_ONLY` (Step 4). `AiHubChannelEntity` doesn't exist in Java port (channels feature not ported).

**Approach:**
```java
@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
private String credentialsEncrypted;
```

---

### 24. ~~Standardize JSON Key Casing~~

**Status:** ✅ STALE — `ConversationDto` already uses camelCase (`userId`, `createdAt`, `updatedAt`).

**Approach:** Normalize to camelCase across the codebase. Use `@JsonProperty("user_id")` only for backward-compatible external APIs.

---

### 25. ~~Remove Redundant Repository Methods~~

**Status:** ✅ DONE (Step 8) — Removed unused methods: `EntryPointRepository.findByCategory(String)`, `findByActiveTrue()`, `findByModuleKeyAndCategory(String, String)`. `EntryPointGroupRepository.findByCategory(String)`, `findByParentKey(String)`. `AiHubTokenRepository` methods are all used (different callers need sorted vs unsorted).
- `EntryPointRepository.findByCategory` vs `findByCategoryOrderBySortOrderAscNameAsc`
- `AiHubMessageRepository.findByConversationId` vs `findByConversationIdOrderByIdAsc`
- `AiHubTokenRepository.findByProviderId` vs `findByProviderIdOrderByNameAsc`
- `EntryPointGroupRepository.findByCategory` vs `findByCategoryOrderBySortOrderAscNameAsc`

**Approach:** Remove unsorted variants; update callers to use sorted versions.

---

### 26. Add Projections for List Endpoints

**Issue:** All queries return full entities even when only a few fields are needed.

**Approach:** Create interface-based projections for read-heavy list endpoints:
```java
public interface ModuleSummary {
    String getKey();
    String getName();
    String getVersion();
    boolean isActive();
}
```

---

### 27. Break Long Transactions

**Issue:**
- `ShellTreeService.saveShellTree()` — 153-line method in single `@Transactional`
- `Reconciler.run()` — entire bootstrap in one transaction

**Status:** 🔶 HALF-DONE (Step 9) — `Reconciler.run()` is no longer transactional: manifest fetches (with retry/sleeps) run before the DB phase, the DB phase is one atomic `TransactionTemplate` block, and KC role sync runs post-commit. `ShellTreeService.saveShellTree` remains a single transaction (acceptable: it is all-DB, client-triggered, and its validation now runs before any write). Still open if the tree save ever grows remote I/O.

**Approach:** Move read-heavy validation outside transaction boundary; split write operations into smaller transactional methods.

---

## Newly identified (Step 9 audit, 2026-09-21)

- **`ManifestController.download`** — calls `installService.listVersions(moduleKey)` which hydrates every stored manifest JSONB just to find one version. Add a `findByModuleKeyAndId` repository lookup.
- **`ProxyService`** — buffers the whole upstream asset in memory (no size cap, own raw `HttpClient`); consider streaming with a size cap and reusing the shared `RestClient` customization.
- **`InstallService` (579 lines)** — split install/versions/drafts into focused collaborators.
- **Token masking** (see Step 9 note) — stored-mask column + backfill would remove the per-token decrypt on list.
- **Legacy tables** — `favorites`, `chat_conversations`, `chat_messages` (V1) have no Java entities; drop in a future cleanup migration after confirming no external consumers.

---

## Implementation Roadmap

| Phase | Focus | Items | Est. Effort |
|---|---|---|---|
| **Phase 1** | Foundation (DTOs + Utilities) | #1, #2, #10 | Large |
| **Phase 2** | Security + Correctness | #4, #5, #7, #17, #15 | Medium |
| **Phase 3** | Performance | #3, #6, #13, #16 | Medium |
| **Phase 4** | Spring Abstractions | #8, #9, #11, #12 | Medium |
| **Phase 5** | API Polish | #14, #19, #22, #24 | Small |
| **Phase 6** | Production Hardening | #18, #20, #21, #23, #25-27 | Small |
