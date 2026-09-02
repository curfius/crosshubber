package com.crosshubber.portal.auth;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.security.PortalUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Keycloak identity provider — simplified password grant.
 *
 * <p>Mirrors {@code portal/src/adapters/keycloak/keycloak-identity-provider.ts} but uses direct
 * {@code grant_type=password} for simplicity (requires Direct Access Grants enabled for client
 * {@code portal}). For production, replace with PKCE scraping if needed.
 */
@Service
public class KeycloakService {

  private static final Logger log = LoggerFactory.getLogger(KeycloakService.class);

  private final PortalProperties props;
  private final ObjectMapper mapper;
  private final RestClient restClient;

  public KeycloakService(PortalProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
    this.restClient = RestClient.create();
  }

  public record LoginResult(
      boolean ok, PortalUser user, String idToken, String refreshToken, String error) {}

  public record RefreshResult(boolean ok, PortalUser user, String idToken, String refreshToken) {}

  /**
   * Authenticates via password grant.
   *
   * @param username Keycloak username
   * @param password password
   * @return LoginResult
   */
  public LoginResult authenticate(String username, String password) {
    String tokenUrl = props.getIssuer() + "/protocol/openid-connect/token";
    log.info("[auth] authenticating user={} via {}", username, tokenUrl);
    try {
      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      form.add("grant_type", "password");
      form.add("client_id", "portal");
      form.add("client_secret", props.getClientSecret());
      form.add("username", username);
      form.add("password", password);
      form.add("scope", "openid");

      String body =
          restClient
              .post()
              .uri(tokenUrl)
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(String.class);

      JsonNode root = mapper.readTree(body);
      String accessToken = root.has("access_token") ? root.get("access_token").asText() : null;
      String idToken = root.has("id_token") ? root.get("id_token").asText() : accessToken;
      String refreshToken = root.has("refresh_token") ? root.get("refresh_token").asText() : null;

      if (idToken == null) {
        log.warn("[auth] no id_token for user={}", username);
        return new LoginResult(false, null, null, null, "no token");
      }

      // Access token contains realm_access.roles while id_token may not
      String userJwt = accessToken != null ? accessToken : idToken;
      PortalUser user = parseUser(userJwt);
      log.info("[auth] login ok user={} roles={}", user.name(), user.roles());
      return new LoginResult(true, user, idToken, refreshToken, null);
    } catch (Exception e) {
      log.warn("[auth] login failed for user={}: {}", username, e.getMessage());
      return new LoginResult(false, null, null, null, e.getMessage());
    }
  }

  public RefreshResult refresh(String refreshToken) {
    String tokenUrl = props.getIssuer() + "/protocol/openid-connect/token";
    try {
      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      form.add("grant_type", "refresh_token");
      form.add("client_id", "portal");
      form.add("client_secret", props.getClientSecret());
      form.add("refresh_token", refreshToken);

      String body =
          restClient
              .post()
              .uri(tokenUrl)
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(String.class);

      JsonNode root = mapper.readTree(body);
      String accessToken = root.has("access_token") ? root.get("access_token").asText() : null;
      String idToken = root.has("id_token") ? root.get("id_token").asText() : accessToken;
      String newRefresh =
          root.has("refresh_token") ? root.get("refresh_token").asText() : refreshToken;

      if (idToken == null) {
        return new RefreshResult(false, null, null, null);
      }
      String userJwt = accessToken != null ? accessToken : idToken;
      PortalUser user = parseUser(userJwt);
      return new RefreshResult(true, user, idToken, newRefresh);
    } catch (Exception e) {
      log.warn("[auth] refresh failed: {}", e.getMessage());
      return new RefreshResult(false, null, null, null);
    }
  }

  public String logoutUrl(String idTokenHint, String postLogoutUri) {
    return props.getIssuer()
        + "/protocol/openid-connect/logout?post_logout_redirect_uri="
        + postLogoutUri
        + "&id_token_hint="
        + (idTokenHint != null ? idTokenHint : "");
  }

  private PortalUser parseUser(String jwt) {
    try {
      String[] parts = jwt.split("\\.");
      if (parts.length < 2) {
        return new PortalUser("unknown", "unknown", null, List.of());
      }
      String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
      JsonNode node = mapper.readTree(payload);
      String sub = node.has("sub") ? node.get("sub").asText() : "unknown";
      String name =
          node.has("name")
              ? node.get("name").asText()
              : node.has("preferred_username") ? node.get("preferred_username").asText() : sub;
      String email = node.has("email") ? node.get("email").asText() : null;
      List<String> roles = new ArrayList<>();
      if (node.has("realm_access") && node.get("realm_access").has("roles")) {
        for (JsonNode r : node.get("realm_access").get("roles")) {
          roles.add(r.asText());
        }
      }
      return new PortalUser(sub, name, email, roles);
    } catch (Exception e) {
      log.warn("[auth] jwt parse failed: {}", e.getMessage());
      return new PortalUser("unknown", "unknown", null, List.of());
    }
  }
}
