---
name: spring-boot
description: Use when scaffolding or refactoring Spring Boot 3.x / Java 21 backends with Maven, JPA, Flyway, Spring Security, and WebClient. Covers feature-based package layout, configuration properties, JPA entities, Flyway migrations, SecurityFilterChain, validation, and Docker packaging.
---

# Spring Boot 3.x + Java 21 Architecture

Opinionated layout for Spring Boot 3.4 + Java 21 + Maven, optimized for portal-style multi-tenant backends (similar to Next/Express ports). Keep it simple — controller → service → repository — no hexagonal overengineering unless 3+ integrations.

## Stack Baseline

- Java 21 (LTS, virtual threads `spring.threads.virtual.enabled=true` optional for polling/SSE)
- Spring Boot 3.4.5 (parent `spring-boot-starter-parent:3.4.5`)
- Build: Maven 3.9 + `spring-boot-maven-plugin` + `maven-checkstyle-plugin` + `spotless` (google-java-format)
- DB: PostgreSQL 16, HikariCP `maximum-pool-size=5`, Flyway `V1__*.sql`, Spring Data JPA + Hibernate 6
- Auth: `spring-boot-starter-security` + `spring-boot-starter-oauth2-client` (OIDC discovery/PKCE/client_credentials) or custom HMAC cookie filter
- HTTP: `spring-boot-starter-web` (Jackson), `spring-boot-starter-validation`, `WebClient` (non-blocking for LLM/MFE proxy), `Caffeine` cache
- Test: `spring-boot-starter-test` (Mockito, AssertJ), `testcontainers:postgresql`, `awaitility`, `h2` for slice tests
- Docs: `springdoc-openapi-starter-webmvc-ui` optional; container image via `eclipse-temurin:21-jre`

## Directory Structure

```
src/main/java/com/genportal/portal/
  PortalApplication.java                    # @SpringBootApplication @EnableScheduling
  config/
    PortalProperties.java                   # @ConfigurationProperties("portal")
    SecurityConfig.java                     # SecurityFilterChain bean
    WebConfig.java                          # SPA fallback, static handling
    JacksonConfig.java
  security/
    PortalSessionFilter.java                # OncePerRequestFilter
    SessionService.java                     # ConcurrentHashMap + @Scheduled GC
    CryptoService.java                      # AES-256-GCM
  bootstrap/
    TenantConfigLoader.java / Reconciler.java / EmbeddedCatalog.java
  modules/
    portal/   PortalController.java  PortalService.java
    auth/     AuthController.java    KeycloakIdentityProvider.java
    modules/  ModulesController.java ModuleEntity.java ModuleRepository.java
    entrypoints/ EntryPointsController.java EntryPointEntity.java
    workspaces/ WorkspacesController.java WorkspaceEntity.java
    navigation/ NavigationController.java PinnedAppEntity.java
    proxy/    ProxyController.java
    settings/ SettingsController.java
    registry/ RegistryController.java ManifestFetcher.java
    i18n/     I18nController.java
    aihub/    ProvidersController.java ChatController.java PipelineService.java PollingService.java
  shared/
    Roles.java  ValidationConstants.java
src/main/resources/
  application.yml
  db/migration/V1__baseline_schema.sql
  static/                                   # Angular build output
src/test/java/...  src/test/resources/application-test.yml
```

**Package rule:** `modules.<feature>` owns its `entity/repository/service/controller/dto`. `shared` and `config` and `security` are cross-cutting — never import feature code from them. Feature modules never import each other directly — inject services.

## Configuration

```java
// PortalProperties.java — single source, fail fast
@ConfigurationProperties(prefix = "portal")
public record PortalProperties(
    @NotBlank String tenantSlug,
    String tenantConfigDir,
    @NotNull Integer port,
    @NotBlank String publicBaseUrl,
    @NotBlank String issuer,
    @NotBlank String clientSecret,
    @NotBlank String sessionSecret,
    @NotBlank @Pattern(regexp="^[0-9a-fA-F]{64}$") String encryptionKey,
    Db db,
    KcAdmin kcAdmin,
    boolean ssrfAllowPrivate,
    boolean cookieSecure) {
  public record Db(@NotBlank String host, @NotNull Integer port, @NotBlank String database,
                   @NotBlank String user, @NotBlank String password,
                   @NotBlank @Pattern(regexp="^[a-z0-9_]+$") String schema, int maxPoolSize) {}
  public record KcAdmin(String baseUrl, String clientId, String clientSecret, String realm) {}
}
```
```yaml
# application.yml
server.port: ${PORT:3000}
portal:
  tenant-slug: ${TENANT_SLUG}
  public-base-url: ${PUBLIC_BASE_URL}
  issuer: ${OIDC_ISSUER}
spring:
  datasource:
    url: jdbc:postgresql://${PGHOST:localhost}:${PGPORT:5432}/${PGDATABASE:portal}
    username: ${PGUSER:portal}
    password: ${PGPASSWORD:}
    hikari.maximum-pool-size: 5
  jpa:
    hibernate.ddl-auto: validate
    properties.hibernate.default_schema: ${PGSCHEMA:${TENANT_SLUG}}
    open-in-view: false
  flyway:
    enabled: true
    schemas: ${PGSCHEMA:${TENANT_SLUG}}
    locations: classpath:db/migration
```

**Rules:** Bind via `@ConfigurationProperties` + `spring-boot-configuration-processor`. Never read `System.getenv` directly beyond properties. Fail fast with `@Validated` + `@NotBlank`.

## JPA & Flyway

- Flyway migrations `src/main/resources/db/migration/V1__*.sql` — additive only, `IF NOT EXISTS`, never edit applied files. Use `spring.flyway.schemas` for per-tenant schema.
- Entities: `@Entity @Table(schema = "${pgschema:public}", name="modules")` or rely on `hibernate.default_schema`. Use `@JdbcTypeCode(SqlTypes.JSON)` for JSONB, `@Convert` for `TEXT[]`, `@Id` String for `modules.key`, `@IdClass` for `workspaces(user_id,name)` (note `id uuid` is NOT PK per migrator `PRIMARY KEY (user_id,name)`).
- Repositories: `extends JpaRepository<Entity,Id>`. Derived queries preferred. For JSONB merge use `@Modifying @Query("UPDATE ... SET settings = settings || :patch::jsonb")`.
- Keep `ddl-auto=validate` — Flyway owns DDL.

## Controllers (Presentation)

```java
@RestController
@RequestMapping("/api/registry/modules")
public class ModulesController {
  private final ModuleService service;
  public ModulesController(ModuleService service) { this.service = service; }

  @GetMapping
  public Map<String, Object> list(Authentication auth) {
    requireAuth(auth);
    return Map.of("modules", service.list());
  }
  @PostMapping
  public ResponseEntity<?> create(@Valid @RequestBody ModuleDto dto, Authentication auth) {
    requireAdmin(auth, "portal-registry-edit");
    return ResponseEntity.ok(service.upsert(dto));
  }
}
```

**Rules:** Parse request → call service → format response. No SQL, no business rules. Throw `ResponseStatusException(401/403/404/409/422)` for expected failures; global handler maps to `{error:"..."}`. Use `ResponseEntity` with correct status (`201` on create).

## Services (Business)

```java
@Service
public class ModuleService {
  private static final Logger log = LoggerFactory.getLogger(ModuleService.class);
  private final ModuleRepository repo;
  public ModuleService(ModuleRepository repo) { this.repo = repo; }

  public List<Module> list() { return repo.findAll(); }

  public Module upsert(ModuleDto dto) {
    // validation, ownership, role checks
    return repo.save(entity);
  }
}
```

- Services hold validation (`validate -> throw 422`), authz, orchestration. No `HttpServletRequest`.
- Return domain objects; log with `log.info("[modules] ...")`.

## Security

- `PortalSessionFilter extends OncePerRequestFilter` — read `portalSession` cookie, `HMAC-SHA256` timingSafe (`MessageDigest.isEqual`), check `SessionService.activeSessions`, set `SecurityContextHolder` `UsernamePasswordAuthenticationToken(user, null, authorities)`. If expired but refreshToken present → call `KeycloakIdentityProvider.refresh()` via `WebClient` `refresh_token` grant, re-issue cookie `SameSite=Lax HttpOnly Max-Age=28800 Secure?`.
- `SecurityConfig` bean: `http.authorizeHttpRequests(a -> a.requestMatchers("/healthz","/api/i18n/**","/api/ai-hub/webhooks/**","/api/login/**","/logout").permitAll().anyRequest().authenticated()).csrf(AbstractHttpConfigurer::disable).addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class)`.
- `RawBodyFilter` (order -101) caches `byte[]` for `POST /api/ai-hub/webhooks/whatsapp/*` before Jackson.

## Cross-Cutting

- `GlobalExceptionHandler @RestControllerAdvice` → `catch ResponseStatusException` → `Map.of("error", ex.getReason())` with `ex.getStatusCode()`; catch `Exception` → `500 {error:"internal server error"}` + `log.error("[portal] ...", ex)`.
- Bean Validation: `@Valid` on DTOs, Jakarta annotations `@Pattern @Size @NotBlank`.
- WebClient: `WebClient.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(2*1024*1024)).build()` for LLM; `SseEmitter` or `Flux<ServerSentEvent>` for streaming.
- Caching: `CaffeineCacheManager` for MFE proxy 60s.
- Scheduling: `@EnableScheduling` + `@Scheduled(fixedDelay=600_000)` for session GC.

## Testing

- Unit: `@ExtendWith(MockitoExtension.class)` + `Mockito` + `AssertJ`. Mock repositories, stub WebClient via `@Mock WebClient`.
- Slice: `@WebMvcTest`, `@DataJpaTest` with `h2` or Testcontainers.
- Integration: `@SpringBootTest` + `@Testcontainers @Container PostgreSQLContainer("postgres:16-alpine")` + `@DynamicPropertySource` wiring `PGHOST/PGDATABASE`.
- Async polling: `Awaitility.await().atMost(2, SECONDS).until(...)`.

## Packaging

```xml
<build><plugins>
  <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
  <plugin><groupId>com.diffplug.spotless</groupId><artifactId>spotless-maven-plugin</artifactId>
    <configuration><java><googleJavaFormat><version>1.24.0</version></googleJavaFormat></java></configuration></plugin>
</plugins></build>
```
Docker multi-stage: `node:22-alpine` build Angular → `maven:3-eclipse-temurin-21` package → `eclipse-temurin:21-jre` run `java -jar app.jar` serving `static/` (SPA fallback via `WebConfig implements WebMvcConfigurer addResourceHandlers/addViewControllers`).
