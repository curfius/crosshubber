package com.crosshubber.portal.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.crosshubber.portal.auth.KeycloakService;
import com.crosshubber.portal.config.PortalProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Validates the {@code portalSession} HMAC cookie and populates SecurityContext.
 *
 * <p>Cookie format: {@code base64url(JSON).base64url(hmacSHA256(secret))} — mirrors {@code
 * portal/src/session.ts}. Expired sessions with a refresh token are renewed transparently,
 * mirroring {@code requireAuth} in middleware/auth.ts.
 */
public class PortalSessionFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(PortalSessionFilter.class);
  public static final String COOKIE_NAME = "portalSession";

  private final PortalProperties props;
  private final SessionService sessionService;
  private final KeycloakService keycloak;
  private final ObjectMapper objectMapper;

  public PortalSessionFilter(
      PortalProperties props,
      SessionService sessionService,
      KeycloakService keycloak,
      ObjectMapper objectMapper) {
    this.props = props;
    this.sessionService = sessionService;
    this.keycloak = keycloak;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String token = parseCookie(request.getHeader("Cookie"), COOKIE_NAME);
    if (token != null) {
      SessionData session = decodeSession(token, props.getSessionSecret());
      if (session != null && sessionService.isValid(token)) {
        setAuthentication(session);
        log.debug("[auth] authenticated user={}", session.user().name());
      } else {
        tryRefresh(token, response);
      }
    }
    chain.doFilter(request, response);
  }

  /**
   * Expired-but-valid cookie with a refresh token — renew the session (mirrors {@code
   * refreshSession} in middleware/auth.ts).
   */
  private void tryRefresh(String token, HttpServletResponse response) {
    SessionData raw = decodeSessionRaw(token, props.getSessionSecret());
    if (raw == null || raw.refreshToken() == null || raw.refreshToken().isBlank()) {
      return;
    }
    KeycloakService.RefreshResult result = keycloak.refresh(raw.refreshToken());
    if (!result.ok()) {
      log.debug("[auth] refresh failed for user={}", raw.user().name());
      return;
    }
    long exp = System.currentTimeMillis() + props.getSessionMaxAge();
    SessionData renewed =
        new SessionData(result.user(), result.idToken(), result.refreshToken(), exp);
    String newToken = encodeSession(renewed, props.getSessionSecret(), objectMapper);
    sessionService.registerSession(newToken, exp, result.idToken());
    response.addHeader("Set-Cookie", sessionCookie(newToken, props));
    setAuthentication(renewed);
    log.info("[auth] session refreshed for user={}", result.user().name());
  }

  private void setAuthentication(SessionData session) {
    List<SimpleGrantedAuthority> authorities =
        session.user().roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(session.user(), session.idToken(), authorities);
    // Store full SessionData as details for logout
    auth.setDetails(session);
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  /** Builds the Set-Cookie value for the session cookie (mirrors auth.routes.ts). */
  public static String sessionCookie(String token, PortalProperties props) {
    return COOKIE_NAME
        + "="
        + token
        + "; Path=/; HttpOnly; SameSite=Lax; Max-Age="
        + props.getSessionHours() * 3600
        + (props.isCookieSecure() ? "; Secure" : "");
  }

  // -- Cookie + HMAC helpers (mirrors session.ts) --

  public static String parseCookie(String header, String name) {
    if (header == null) {
      return null;
    }
    for (String part : header.split(";")) {
      int idx = part.indexOf('=');
      if (idx == -1) {
        continue;
      }
      if (part.substring(0, idx).trim().equals(name)) {
        return part.substring(idx + 1).trim();
      }
    }
    return null;
  }

  static String hmac(String payload, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] out = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  SessionData decodeSession(String token, String secret) {
    if (token == null) {
      return null;
    }
    String[] parts = token.split("\\.", 2);
    if (parts.length != 2) {
      return null;
    }
    String body = parts[0];
    String sig = parts[1];
    String expected = hmac(body, secret);
    if (!timingSafeEqual(sig, expected)) {
      return null;
    }
    try {
      String json = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
      SessionData data = objectMapper.readValue(json, SessionData.class);
      if (data.exp() < System.currentTimeMillis()) {
        return null;
      }
      return data;
    } catch (Exception e) {
      return null;
    }
  }

  SessionData decodeSessionRaw(String token, String secret) {
    return decodeRaw(token, secret, objectMapper);
  }

  /** Decodes a signed cookie without expiry check (mirrors decodeSessionRaw in auth.ts). */
  public static SessionData decodeRaw(String token, String secret, ObjectMapper mapper) {
    if (token == null) {
      return null;
    }
    String[] parts = token.split("\\.", 2);
    if (parts.length != 2) {
      return null;
    }
    String body = parts[0];
    String sig = parts[1];
    if (!timingSafeEqual(sig, hmac(body, secret))) {
      return null;
    }
    try {
      String json = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
      return mapper.readValue(json, SessionData.class);
    } catch (Exception e) {
      return null;
    }
  }

  static boolean timingSafeEqual(String a, String b) {
    byte[] ab = a.getBytes(StandardCharsets.UTF_8);
    byte[] bb = b.getBytes(StandardCharsets.UTF_8);
    if (ab.length != bb.length) {
      return false;
    }
    return MessageDigest.isEqual(ab, bb);
  }

  /** Encodes the session for Set-Cookie (mirrors session.ts encodeSession). */
  public static String encodeSession(SessionData data, String secret, ObjectMapper mapper) {
    try {
      String json = mapper.writeValueAsString(data);
      String body =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(json.getBytes(StandardCharsets.UTF_8));
      return body + "." + hmac(body, secret);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
