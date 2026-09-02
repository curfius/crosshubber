package com.crosshubber.portal.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.security.PortalSessionFilter;
import com.crosshubber.portal.security.SessionData;
import com.crosshubber.portal.security.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Auth routes — login start + logout.
 *
 * <p>Mirrors {@code portal/src/modules/auth/auth.routes.ts}: login start now redirects into the
 * standard OIDC authorization-code flow (Spring Security oauth2-client), and logout redirects to
 * the Keycloak end-session endpoint for valid sessions, {@code /login} for anonymous visitors.
 */
@RestController
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  private final KeycloakService keycloak;
  private final PortalProperties props;
  private final SessionService sessionService;
  private final ObjectMapper mapper;

  public AuthController(
      KeycloakService keycloak,
      PortalProperties props,
      SessionService sessionService,
      ObjectMapper mapper) {
    this.keycloak = keycloak;
    this.props = props;
    this.sessionService = sessionService;
    this.mapper = mapper;
  }

  /**
   * GET /api/login/start — 302 to the OAuth2 authorization endpoint.
   *
   * <p>Keeps the UI contract ({@code window.location.href = '/api/login/start'}); the
   * authorization-code + PKCE dance is owned by Spring Security's oauth2-client filters.
   */
  @GetMapping("/api/login/start")
  public void loginStart(HttpServletResponse response) throws Exception {
    log.info("[auth] login start — redirecting to OAuth2 authorization endpoint");
    response.sendRedirect("/oauth2/authorization/portal");
  }

  /**
   * GET /logout — clears the session cookie, then redirects: valid session → Keycloak end-session
   * with {@code id_token_hint}; anonymous or invalid/expired session → {@code /login} (mirrors
   * Node: {@code decodeSession} is expiry-checked, so stale cookies take the anonymous path).
   */
  @GetMapping("/logout")
  public void logout(HttpServletRequest request, HttpServletResponse response) throws Exception {
    String token = PortalSessionFilter.parseCookie(request.getHeader("Cookie"), "portalSession");
    SessionData session =
        token != null
            ? PortalSessionFilter.decodeRaw(token, props.getSessionSecret(), mapper)
            : null;
    // Expiry check — decodeSession semantics (decodeRaw skips it)
    if (session != null && session.exp() < System.currentTimeMillis()) {
      session = null;
    }
    if (token != null) {
      sessionService.revokeSession(token);
    }
    response.addHeader(
        "Set-Cookie",
        PortalSessionFilter.COOKIE_NAME + "=; Path=/; HttpOnly; Max-Age=0; SameSite=Lax");
    if (session != null) {
      String redirect = keycloak.logoutUrl(session.idToken(), props.getPublicBaseUrl() + "/login");
      log.info("[auth] logout → Keycloak end-session");
      response.sendRedirect(redirect);
      return;
    }
    log.info("[auth] logout anonymous → /login");
    response.sendRedirect("/login");
  }
}
