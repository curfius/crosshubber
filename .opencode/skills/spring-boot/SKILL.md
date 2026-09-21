---
name: spring-boot
description: Use when scaffolding or refactoring the Crosshubber Spring Boot backend (Boot 4.x / Java 21 / Maven / JPA / Flyway / Spring Security OIDC / RestClient). Covers feature-based package layout, Boot 4 artifact names, Jackson 3, configuration properties, JPA entities, Flyway migrations, SecurityFilterChain, and Docker packaging.
---

# Spring Boot 4.x + Java 21 Architecture (Crosshubber)

Opinionated layout for Spring Boot 4 + Java 21 + Maven, optimized for the crosshubber
multi-tenant portal. Keep it simple — controller → service → repository — no hexagonal
overengineering unless 3+ integrations.

## Stack Baseline (verify against portal/server/pom.xml)

- Java 21 (LTS), virtual threads enabled (`spring.threads.virtual.enabled=true`)
- Spring Boot **4.1.x** parent — Boot 4 artifact names:
  - `spring-boot-starter-webmvc` (NOT `starter-web`)
  - `spring-boot-starter-restclient` (RestClient over WebClient for blocking calls)
  - `spring-boot-starter-test-classic` (JUnit 5 stack)
- **Jackson 3**: `tools.jackson.databind.*`, `tools.jackson.databind.node.ObjectNode` —
  NOT `com.fasterxml.jackson` (Jackson 3 moved packages; `asString()` not `asText()`)
- Build: Maven + `spring-boot-maven-plugin` + `maven-checkstyle-plugin` (runs at
  `validate`) + spotless (`google-java-format`, check bound to `verify`)
- DB: PostgreSQL 16, HikariCP `maximum-pool-size=5`, Flyway `V<n>__*.sql`, Data JPA,
  `hibernate.default_schema: ${PGSCHEMA:${TENANT_SLUG}}`, `open-in-view: false`
- Auth: `spring-boot-starter-security` + `spring-boot-starter-oauth2-client` (OIDC
  discovery/PKCE) + custom session cookie filter
- HTTP: shared `RestClient` customization in `config/HttpClientConfig` (10s connect /
  30s read) — never create ad-hoc `HttpClient`s
- Test: `testcontainers:postgresql` for ALL DB tests (no H2), `spring-security-test`

## Directory Structure

```
src/main/java/com/crosshubber/portal/
  PortalApplication.java                    # @SpringBootApplication @EnableScheduling
  config/
    PortalProperties.java                   # record + @ConfigurationProperties("portal")
    SecurityConfig.java                     # SecurityFilterChain bean
    JacksonConfig.java / HttpClientConfig.java / OAuth2ClientConfig.java
    GlobalExceptionHandler.java             # @RestControllerAdvice
  common/                                   # JsonUtils, NodeDates, Roles, SsrfGuard
  security/
    PortalSessionFilter.java                # OncePerRequestFilter (session cookie)
    SessionService.java / CryptoService.java (AES-256-GCM) / PortalUser.java
  auth/   (AuthController, KeycloakOidcUserService, OidcSuccessHandler, kcadmin/KcAdminClient)
  bootstrap/ (TenantConfigLoader, Reconciler, EmbeddedCatalog, I18nCatalog)
  modules/                                  # feature packages — see below
  shell/ workspaces/ proxy/
src/main/resources/
  application.yml                           # portal.* props, env-driven
  db/migration/V1__*.sql ...                # Flyway — DDL only, never edit applied files
src/test/java/...                          # PortalSmokeTest (Testcontainers) + unit tests
```

**Package rule:** `modules.<feature>` owns its entity/repository/service/controller/dto
(e.g. `modules.registry.{modules,entrypoints,entrypointgroups,manifest}`). `config`,
`common`, `security` are cross-cutting — never import feature code from them. Feature
modules never import each other's internals — inject services.

## Configuration

```java
// PortalProperties.java — single source, fail fast
@ConfigurationProperties(prefix = "portal")
public record PortalProperties(
    @NotBlank String tenantSlug,
    String tenantConfigDir,
    @NotBlank String issuer,
    String clientSecret,
    @NotBlank String sessionSecret,
    String encryptionKey,
    Db db,
    KcAdmin kcAdmin,
    boolean ssrfAllowPrivate,
    boolean cookieSecure) {
  public record Db(...) {}
  public record KcAdmin(String baseUrl, String clientId, String clientSecret, String realm) {}
}
```

```yaml
# application.yml (dev defaults insecure-by-design; real secrets via env/compose)
portal:
  tenant-slug: ${TENANT_SLUG:dev}
  tenant-config-dir: ${TENANT_CONFIG_DIR:}
spring:
  datasource:
    hikari.maximum-pool-size: 5
  jpa:
    hibernate.ddl-auto: none     # prod; test profile: validate
    open-in-view: false
  flyway:
    schemas: ${PGSCHEMA:${TENANT_SLUG:dev}}
```

**Rules:** Bind via `@ConfigurationProperties` record + configuration-processor. Never
read `System.getenv` directly. Fail fast on bad config.

## JPA & Flyway

- Flyway owns all DDL. New index/table/column = new `V<n>__*.sql` migration; never edit
  applied migrations; `ddl-auto` stays `none` (prod) / `validate` (test).
- Entities: `@Entity @Table(name=...)` + default schema; `@JdbcTypeCode(SqlTypes.JSON)`
  for JSONB; `@IdClass` for composite PKs (e.g. `i18n_labels(locale,key)`,
  `workspaces(user_id,name)`).
- Repositories: `extends JpaRepository<Entity, Id>`; derived queries preferred; batch
  with `findAllById` / `saveAll` instead of per-row loops (reconciler + upserts are hot
  paths — hundreds of per-row SELECTs at boot were a real bug class).
- Blocking I/O (Keycloak admin, manifest fetches, LLM calls) must stay OUT of
  `@Transactional` — Hikari pool is 5; pattern: orchestrate outside TX, persist inside.

## Controllers (Presentation)

Controllers stay thin: routing + status codes. Business logic in services. Throw
`ResponseStatusException` (401/403/404/409/422) for expected failures; the global
handler maps to `{"error":"..."}` with the right status. `201` on create, `204` on
delete. Method security via `@PreAuthorize("hasAuthority('portal-...-edit')")`.
Security context is populated by `PortalSessionFilter` (session cookie →
`UsernamePasswordAuthenticationToken`).

## Security

- `PortalSessionFilter` — reads `portalSession` cookie, validates via `SessionService`
  (HMAC/timing-safe, AES-256-GCM encrypted session data via `CryptoService`), sets
  authorities from stored roles.
- `SecurityConfig`: `permitAll` on `/healthz`, `/api/login/**`, `/api/i18n/**`,
  `/api/ai-hub/webhooks/**`; everything else authenticated; CSRF disabled (cookie +
  same-site session model); session filter before the oauth2 filter chain.

## Cross-Cutting

- `GlobalExceptionHandler` → `{"error":"..."}` envelope for everything; contract
  requires exact message strings — do not "improve" them (see README divergences).
- JSON parse/write helpers live in `common/JsonUtils` — reuse, don't hand-roll.
- Caffeine for the MFE proxy cache (60s); runtime-built `ChatModel` clients cached 60s
  in `AiHubChatService`.
- Scheduling: `@EnableScheduling`; session GC via `@Scheduled`.

## Testing

- Unit: Mockito + AssertJ, mock repositories. Name mirrors service under test.
- Integration: `PortalSmokeTest` — `@SpringBootTest(RANDOM_PORT)` + `@Testcontainers`
  PostgreSQL 16; Flyway runs, `ddl-auto=validate`, checks `/healthz` + 401 JSON body.
- No H2 anywhere. Add a test class whenever you edit a service.
