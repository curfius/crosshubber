package com.crosshubber.portal.auth;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.security.PortalSessionFilter;
import com.crosshubber.portal.security.PortalUser;
import com.crosshubber.portal.security.SessionData;
import com.crosshubber.portal.security.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Auth routes — login, logout.
 *
 * <p>Mirrors {@code portal/src/modules/auth/auth.routes.ts}.
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

  /** GET /api/login/start — returns flowId (compat stub). */
  @GetMapping("/api/login/start")
  public ResponseEntity<Map<String, String>> loginStart() {
    String flowId = UUID.randomUUID().toString();
    log.info("[auth] login start flowId={}", flowId);
    return ResponseEntity.ok(Map.of("flowId", flowId));
  }

  /**
   * POST /api/login — {flowId, username, password} -> {ok, user} + Set-Cookie.
   *
   * <p>Mirrors Node login flow (without PKCE scraping, uses password grant).
   */
  @PostMapping("/api/login")
  public ResponseEntity<Map<String, Object>> login(
      @RequestBody Map<String, String> body, HttpServletResponse response) {
    String flowId = body.get("flowId");
    String username = body.get("username");
    String password = body.get("password");
    if (username == null || username.isBlank() || password == null || password.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "username and password required"));
    }
    log.info("[auth] login attempt user={} flowId={}", username, flowId);

    KeycloakService.LoginResult result = keycloak.authenticate(username, password);
    if (!result.ok()) {
      log.warn("[auth] login failed user={} error={}", username, result.error());
      return ResponseEntity.ok(
          Map.of(
              "ok",
              false,
              "error",
              result.error() != null ? result.error() : "invalid credentials"));
    }

    PortalUser user = result.user();
    long exp = System.currentTimeMillis() + props.getSessionMaxAge();
    SessionData data = new SessionData(user, result.idToken(), result.refreshToken(), exp);
    String token = PortalSessionFilter.encodeSession(data, props.getSessionSecret(), mapper);
    sessionService.registerSession(token, exp);

    response.addHeader("Set-Cookie", PortalSessionFilter.sessionCookie(token, props));
    log.info("[auth] login ok user={} exp={}", user.name(), exp);
    return ResponseEntity.ok(
        Map.of(
            "ok",
            true,
            "user",
            Map.of(
                "sub", user.sub(),
                "name", user.name(),
                "email", user.email() != null ? user.email() : "",
                "roles", user.roles())));
  }

  /** GET /logout — clears cookie and redirects to Keycloak logout. */
  @GetMapping("/logout")
  public void logout(HttpServletRequest request, HttpServletResponse response) throws Exception {
    String cookie = request.getHeader("Cookie");
    String token = PortalSessionFilter.parseCookie(cookie, "portalSession");
    String idToken = null;
    if (token != null) {
      sessionService.revokeSession(token);
      SessionData raw = PortalSessionFilter.decodeRaw(token, props.getSessionSecret(), mapper);
      if (raw != null) {
        idToken = raw.idToken();
      }
    }
    // Clear cookie
    response.addHeader("Set-Cookie", "portalSession=; Path=/; HttpOnly; Max-Age=0; SameSite=Lax");
    String redirect = keycloak.logoutUrl(idToken, props.getPublicBaseUrl() + "/login");
    log.info("[auth] logout redirect={}", redirect);
    response.sendRedirect(redirect);
  }
}
