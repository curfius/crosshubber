package com.crosshubber.portal.auth;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.security.PortalSessionFilter;
import com.crosshubber.portal.security.PortalUser;
import com.crosshubber.portal.security.SessionData;
import com.crosshubber.portal.security.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Successful OIDC login — mints the {@code portalSession} cookie and redirects to {@code /}.
 *
 * <p>Builds the same {@link SessionData} payload as the Node login flow: {@code name} = {@code name
 * || preferred_username}, email omitted (null) when absent, refresh token from the authorized
 * client (requires the {@code offline_access} scope).
 */
@Component
public class OidcSuccessHandler implements AuthenticationSuccessHandler {

  private static final Logger log = LoggerFactory.getLogger(OidcSuccessHandler.class);

  private final PortalProperties props;
  private final SessionService sessionService;
  private final OAuth2AuthorizedClientService authorizedClientService;
  private final ObjectMapper mapper;

  public OidcSuccessHandler(
      PortalProperties props,
      SessionService sessionService,
      OAuth2AuthorizedClientService authorizedClientService,
      ObjectMapper mapper) {
    this.props = props;
    this.sessionService = sessionService;
    this.authorizedClientService = authorizedClientService;
    this.mapper = mapper;
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {
    if (!(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
      log.warn("[auth] OIDC success but principal is not OidcUser");
      response.sendRedirect("/");
      return;
    }

    // User fields mirror keycloak-identity-provider.ts LoginResult.user
    String sub = oidcUser.getSubject();
    String name = oidcUser.getClaimAsString("name");
    if (name == null || name.isBlank()) {
      name = oidcUser.getPreferredUsername();
    }
    if (name == null || name.isBlank()) {
      name = sub;
    }
    String email = oidcUser.getEmail();
    List<String> roles =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith("ROLE_"))
            .map(a -> a.substring("ROLE_".length()))
            .toList();
    PortalUser user = new PortalUser(sub, name, email, roles);

    String idToken = oidcUser.getIdToken().getTokenValue();
    String refreshToken = null;
    OAuth2AuthorizedClient authorizedClient =
        authorizedClientService.loadAuthorizedClient("portal", authentication.getName());
    if (authorizedClient != null && authorizedClient.getRefreshToken() != null) {
      refreshToken = authorizedClient.getRefreshToken().getTokenValue();
    }

    long exp = System.currentTimeMillis() + props.getSessionMaxAge();
    SessionData sessionData = new SessionData(user, idToken, refreshToken, exp);
    String token = PortalSessionFilter.encodeSession(sessionData, props.getSessionSecret(), mapper);
    sessionService.registerSession(token, exp, idToken);
    response.addHeader("Set-Cookie", PortalSessionFilter.sessionCookie(token, props));

    log.info(
        "[auth] OIDC login ok user={} roles={} refresh={}",
        name,
        roles,
        refreshToken != null ? "yes" : "no");
    response.sendRedirect("/");
  }
}
