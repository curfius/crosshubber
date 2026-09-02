package com.crosshubber.portal.auth.kcadmin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.crosshubber.portal.config.PortalProperties;

/**
 * Keycloak admin client (client-credentials) — mirrors {@code
 * portal/src/adapters/keycloak-admin/kc-admin-client.ts}. Used by the module registry to sync
 * manifest-declared realm roles.
 */
@Service
public class KcAdminClient {

  private static final Logger log = LoggerFactory.getLogger(KcAdminClient.class);

  private final PortalProperties props;
  private final RestClient restClient;
  private String accessToken;
  private Instant expiresAt;

  public KcAdminClient(PortalProperties props) {
    this.props = props;
    this.restClient = RestClient.create();
  }

  public boolean isConfigured() {
    PortalProperties.KcAdmin kcAdmin = props.getKcAdmin();
    return kcAdmin != null
        && notBlank(kcAdmin.getBaseUrl())
        && notBlank(kcAdmin.getClientId())
        && notBlank(kcAdmin.getClientSecret())
        && notBlank(kcAdmin.getRealm());
  }

  private String tokenUrl() {
    PortalProperties.KcAdmin kcAdmin = props.getKcAdmin();
    return stripTrailingSlash(kcAdmin.getBaseUrl())
        + "/realms/"
        + kcAdmin.getRealm()
        + "/protocol/openid-connect/token";
  }

  private String realmUrl() {
    PortalProperties.KcAdmin kcAdmin = props.getKcAdmin();
    return stripTrailingSlash(kcAdmin.getBaseUrl()) + "/admin/realms/" + kcAdmin.getRealm();
  }

  private synchronized String ensureToken() {
    if (accessToken != null
        && expiresAt != null
        && expiresAt.isAfter(Instant.now().plusSeconds(5))) {
      return accessToken;
    }
    PortalProperties.KcAdmin kcAdmin = props.getKcAdmin();
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", kcAdmin.getClientId());
    form.add("client_secret", kcAdmin.getClientSecret());
    @SuppressWarnings("unchecked")
    Map<String, Object> response =
        restClient
            .post()
            .uri(tokenUrl())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);
    if (response == null || response.get("access_token") == null) {
      throw new IllegalStateException("kc-admin token request returned no access_token");
    }
    this.accessToken = (String) response.get("access_token");
    Number expiresIn = (Number) response.getOrDefault("expires_in", 60);
    this.expiresAt = Instant.now().plusSeconds(expiresIn.longValue());
    return accessToken;
  }

  /** Creates the given realm roles (idempotent). An existing role gets its description updated. */
  public void ensureRealmRoles(List<Map<String, Object>> roles) {
    for (Map<String, Object> role : roles) {
      String key = String.valueOf(role.get("key"));
      String description =
          role.get("description") instanceof String d && !d.isBlank()
              ? d
              : String.valueOf(role.get("name"));
      int status = request("POST", "/roles", Map.of("name", key, "description", description));
      if (status == 409) {
        request("PUT", "/roles/" + key, Map.of("name", key, "description", description));
      } else if (status >= 300) {
        log.error("[kc-admin] failed to ensure role \"{}\": status {}", key, status);
      }
    }
  }

  public List<String> getClientRoles(String clientId) {
    try {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> data =
          restClient
              .get()
              .uri(realmUrl() + "/clients/" + clientId + "/roles")
              .headers(h -> h.setBearerAuth(ensureToken()))
              .retrieve()
              .body(List.class);
      List<String> names = new ArrayList<>();
      if (data != null) {
        for (Map<String, Object> role : data) {
          names.add(String.valueOf(role.get("name")));
        }
      }
      return names;
    } catch (Exception e) {
      log.warn("[kc-admin] getClientRoles failed: {}", e.getMessage());
      return List.of();
    }
  }

  /** Returns the HTTP status; never throws for non-2xx. */
  private int request(String method, String path, Object body) {
    try {
      RestClient.RequestBodySpec spec =
          method.equalsIgnoreCase("POST")
              ? restClient.post().uri(realmUrl() + path)
              : restClient.put().uri(realmUrl() + path);
      spec.headers(h -> h.setBearerAuth(ensureToken()));
      if (body != null) {
        spec.contentType(MediaType.APPLICATION_JSON).body(body);
      }
      return spec.retrieve().toEntity(Void.class).getStatusCode().value();
    } catch (org.springframework.web.client.HttpStatusCodeException e) {
      return e.getStatusCode().value();
    } catch (Exception e) {
      log.warn("[kc-admin] request {} {} failed: {}", method, path, e.getMessage());
      return 500;
    }
  }

  private static String stripTrailingSlash(String url) {
    return url.replaceAll("/+$", "");
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }
}
