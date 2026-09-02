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
import org.springframework.web.util.UriComponentsBuilder;

import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.security.PortalUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Keycloak identity provider — token refresh + logout URL building.
 *
 * <p>The password-grant (ROPC) login path was removed in favor of the standard OIDC
 * authorization-code redirect flow (see {@link OidcSuccessHandler} and {@code SecurityConfig});
 * only the transparent-refresh grant remains, mirroring {@code refreshTokenGrant} in {@code
 * keycloak-identity-provider.ts}.
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

  public record RefreshResult(boolean ok, PortalUser user, String idToken, String refreshToken) {}

  /** Refresh-token grant — mirrors {@code refreshTokenGrant}. */
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

  /**
   * Keycloak end-session URL — mirrors {@code logoutUrl(idTokenHint, postLogoutUri)}: public
   * issuer, {@code id_token_hint} set only when present, then {@code post_logout_redirect_uri}.
   */
  public String logoutUrl(String idTokenHint, String postLogoutUri) {
    UriComponentsBuilder builder =
        UriComponentsBuilder.fromHttpUrl(
            props.getEffectiveIssuer() + "/protocol/openid-connect/logout");
    if (idTokenHint != null && !idTokenHint.isBlank()) {
      builder.queryParam("id_token_hint", idTokenHint);
    }
    builder.queryParam("post_logout_redirect_uri", postLogoutUri);
    return builder.build().encode().toUriString();
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
