package com.crosshubber.portal.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * In-memory session registry.
 *
 * <p>Mirrors {@code portal/src/middleware/auth.ts} activeSessions Map + 10-min GC. Stores
 * additional session info including idToken for logout.
 */
@Service
public class SessionService {

  private static final Logger log = LoggerFactory.getLogger(SessionService.class);

  private final Map<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();

  public record SessionInfo(long exp, String idToken) {}

  public void registerSession(String token, long exp, String idToken) {
    activeSessions.put(token, new SessionInfo(exp, idToken));
    log.debug("[auth] registered session exp={}", exp);
  }

  public void registerSession(String token, long exp) {
    registerSession(token, exp, null);
  }

  public void revokeSession(String token) {
    activeSessions.remove(token);
    log.debug("[auth] revoked session");
  }

  public boolean isValid(String token) {
    SessionInfo info = activeSessions.get(token);
    if (info == null) {
      return false;
    }
    if (info.exp() < System.currentTimeMillis()) {
      activeSessions.remove(token);
      log.debug("[auth] session expired, evicted");
      return false;
    }
    return true;
  }

  public SessionInfo getSessionInfo(String token) {
    return activeSessions.get(token);
  }

  /** Evicts expired sessions every 10 minutes (unref in Node). */
  @Scheduled(fixedDelay = 600000)
  public void evictExpired() {
    long now = System.currentTimeMillis();
    int before = activeSessions.size();
    activeSessions.entrySet().removeIf(e -> e.getValue().exp() < now);
    int after = activeSessions.size();
    if (before != after) {
      log.info("[auth] evicted {} expired sessions ({} -> {})", before - after, before, after);
    }
  }
}
