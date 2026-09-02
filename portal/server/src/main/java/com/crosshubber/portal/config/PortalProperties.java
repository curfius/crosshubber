package com.crosshubber.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Binds all portal environment configuration from {@code application.yml}.
 *
 * <p>Mirrors {@code C:/playground/projects/genportal/portal/src/config.ts} env parsing and
 * validation. Fail-fast via Jakarta Validation. All fields map from {@code portal.*} prefix with
 * relaxed binding (kebab-case to camelCase).
 */
@Configuration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "portal")
@Validated
public class PortalProperties {

  /** Tenant slug — kebab-case, e.g. {@code dev} or {@code my-tenant}. */
  @NotBlank
  @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "tenantSlug must be kebab-case")
  private String tenantSlug;

  /** Tenant config directory override; empty uses repo {@code tenants/}. */
  private String tenantConfigDir = "";

  /** HTTP port — defaults to 3000, mirrors {@code config.port}. */
  private int port = 3000;

  /** Public base URL for callbacks, e.g. {@code http://localhost:3000}. */
  @NotBlank private String publicBaseUrl;

  /** OIDC issuer URL. */
  @NotBlank private String issuer;

  /** OIDC client secret. */
  @NotBlank private String clientSecret;

  /** Secret for HMAC-signing session cookies. */
  @NotBlank private String sessionSecret;

  /** 32-byte hex key (64 hex chars) for AES-256-GCM. */
  @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "encryptionKey must be 64 hex chars")
  private String encryptionKey;

  /** Session duration in hours. */
  private int sessionHours = 8;

  /** Session max age in milliseconds (8h = 28800000). */
  private long sessionMaxAge = 28800000L;

  /** Allow SSRF to private ranges when true. */
  private boolean ssrfAllowPrivate;

  /** Whether session cookie uses Secure flag. */
  private boolean cookieSecure;

  /** NATS HTTP monitoring URL. */
  private String natsHttpUrl = "";

  /** Public Keycloak URL override. */
  private String keycloakPublicUrl = "";

  /** Database connection properties (optional under portal.db). */
  private Db db;

  /** Keycloak admin client (nullable — all four must be present). */
  private KcAdmin kcAdmin;

  public String getTenantSlug() {
    return tenantSlug;
  }

  public void setTenantSlug(String tenantSlug) {
    this.tenantSlug = tenantSlug;
  }

  public String getTenantConfigDir() {
    return tenantConfigDir;
  }

  public void setTenantConfigDir(String tenantConfigDir) {
    this.tenantConfigDir = tenantConfigDir;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public String getPublicBaseUrl() {
    return publicBaseUrl;
  }

  public void setPublicBaseUrl(String publicBaseUrl) {
    this.publicBaseUrl = publicBaseUrl;
  }

  public String getIssuer() {
    return issuer;
  }

  public void setIssuer(String issuer) {
    this.issuer = issuer;
  }

  public String getClientSecret() {
    return clientSecret;
  }

  public void setClientSecret(String clientSecret) {
    this.clientSecret = clientSecret;
  }

  public String getSessionSecret() {
    return sessionSecret;
  }

  public void setSessionSecret(String sessionSecret) {
    this.sessionSecret = sessionSecret;
  }

  public String getEncryptionKey() {
    return encryptionKey;
  }

  public void setEncryptionKey(String encryptionKey) {
    this.encryptionKey = encryptionKey;
  }

  public int getSessionHours() {
    return sessionHours;
  }

  public void setSessionHours(int sessionHours) {
    this.sessionHours = sessionHours;
  }

  public long getSessionMaxAge() {
    return sessionMaxAge;
  }

  public void setSessionMaxAge(long sessionMaxAge) {
    this.sessionMaxAge = sessionMaxAge;
  }

  public boolean isSsrfAllowPrivate() {
    return ssrfAllowPrivate;
  }

  public void setSsrfAllowPrivate(boolean ssrfAllowPrivate) {
    this.ssrfAllowPrivate = ssrfAllowPrivate;
  }

  public boolean isCookieSecure() {
    return cookieSecure;
  }

  public void setCookieSecure(boolean cookieSecure) {
    this.cookieSecure = cookieSecure;
  }

  public String getNatsHttpUrl() {
    return natsHttpUrl;
  }

  public void setNatsHttpUrl(String natsHttpUrl) {
    this.natsHttpUrl = natsHttpUrl;
  }

  public String getKeycloakPublicUrl() {
    return keycloakPublicUrl;
  }

  public void setKeycloakPublicUrl(String keycloakPublicUrl) {
    this.keycloakPublicUrl = keycloakPublicUrl;
  }

  public Db getDb() {
    return db;
  }

  public void setDb(Db db) {
    this.db = db;
  }

  public KcAdmin getKcAdmin() {
    return kcAdmin;
  }

  public void setKcAdmin(KcAdmin kcAdmin) {
    this.kcAdmin = kcAdmin;
  }

  /**
   * Database nested properties.
   *
   * <p>Mirrors {@code config.db} in the Node config.
   */
  public static class Db {

    private String host;

    private Integer port;

    private String database;

    private String user;

    private String password;

    @Pattern(regexp = "^[a-z0-9_]+$", message = "schema must match ^[a-z0-9_]+$")
    private String schema;

    private Integer maxPoolSize = 5;

    public String getHost() {
      return host;
    }

    public void setHost(String host) {
      this.host = host;
    }

    public Integer getPort() {
      return port;
    }

    public void setPort(Integer port) {
      this.port = port;
    }

    public String getDatabase() {
      return database;
    }

    public void setDatabase(String database) {
      this.database = database;
    }

    public String getUser() {
      return user;
    }

    public void setUser(String user) {
      this.user = user;
    }

    public String getPassword() {
      return password;
    }

    public void setPassword(String password) {
      this.password = password;
    }

    public String getSchema() {
      return schema;
    }

    public void setSchema(String schema) {
      this.schema = schema;
    }

    public Integer getMaxPoolSize() {
      return maxPoolSize;
    }

    public void setMaxPoolSize(Integer maxPoolSize) {
      this.maxPoolSize = maxPoolSize;
    }
  }

  /** Keycloak admin client properties. All four fields must be set together; otherwise null. */
  public static class KcAdmin {

    private String baseUrl;

    private String clientId;

    private String clientSecret;

    private String realm;

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getClientId() {
      return clientId;
    }

    public void setClientId(String clientId) {
      this.clientId = clientId;
    }

    public String getClientSecret() {
      return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
      this.clientSecret = clientSecret;
    }

    public String getRealm() {
      return realm;
    }

    public void setRealm(String realm) {
      this.realm = realm;
    }
  }
}
