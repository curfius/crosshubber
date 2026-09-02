package com.crosshubber.portal.auth;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * OIDC user service — maps Keycloak realm roles to {@code ROLE_} authorities.
 *
 * <p>Roles come from {@code realm_access.roles} of the access token with ID-token fallback,
 * mirroring {@code keycloak-identity-provider.ts} ({@code accessClaims.realm_access ??
 * idClaims.realm_access}).
 */
public class KeycloakOidcUserService extends OidcUserService {

  private static final Logger log = LoggerFactory.getLogger(KeycloakOidcUserService.class);

  private static final TypeReference<Map<String, Object>> CLAIMS_TYPE = new TypeReference<>() {};

  private final ObjectMapper mapper;

  public KeycloakOidcUserService(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest userRequest) {
    OidcUser user = super.loadUser(userRequest);
    // Node parity: access token realm_access first, ID token realm_access as fallback
    List<String> roles =
        realmRolesFrom(decodeJwtClaims(userRequest.getAccessToken().getTokenValue()))
            .or(() -> realmRolesFrom(user.getIdToken().getClaims()))
            .orElse(List.of());
    Set<GrantedAuthority> authorities = new LinkedHashSet<>();
    roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
    log.info("[auth] OIDC login roles={} user={}", roles, user.getPreferredUsername());
    return new DefaultOidcUser(
        authorities, user.getIdToken(), user.getUserInfo(), "preferred_username");
  }

  private Optional<List<String>> realmRolesFrom(Map<String, Object> claims) {
    Object realmAccess = claims == null ? null : claims.get("realm_access");
    if (!(realmAccess instanceof Map<?, ?> realmMap)) {
      return Optional.empty();
    }
    Object rolesObj = realmMap.get("roles");
    if (!(rolesObj instanceof List<?> rolesList)) {
      return Optional.empty();
    }
    List<String> roles = new ArrayList<>();
    for (Object role : rolesList) {
      if (role != null) {
        roles.add(String.valueOf(role));
      }
    }
    return Optional.of(roles);
  }

  /** Decodes the JWT payload of {@code token} (empty map when unreadable). */
  private Map<String, Object> decodeJwtClaims(String token) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length < 2) {
        return Map.of();
      }
      String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
      return mapper.readValue(payload, CLAIMS_TYPE);
    } catch (Exception e) {
      log.warn("[auth] access token claim decode failed: {}", e.getMessage());
      return Map.of();
    }
  }
}
