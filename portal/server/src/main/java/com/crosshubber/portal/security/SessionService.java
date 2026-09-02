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
 * <p>Mirrors {@code portal/src/middleware/auth.ts} activeSessions Map + 10-min GC.
 */
@Service
public class SessionService {

  private static final Logger log = LoggerFactory.getLogger(SessionService.class);

  private final Map<String, Long> activeSessions = new ConcurrentHashMap<>();

  public void registerSession(String token, long exp) {
    activeSessions.put(token, exp);
    log.debug("[auth] registered session exp={}", exp);
  }

  public void revokeSession(String token) {
    activeSessions.remove(token);
    log.debug("[auth] revoked session");
  }

  public boolean isValid(String token) {
    Long exp = activeSessions.get(token);
    if (exp == null) {
      return false;
    }
    if (exp < System.currentTimeMillis()) {
      activeSessions.remove(token);
      log.debug("[auth] session expired, evicted");
      return false;
    }
    return true;
  }

  /** Evicts expired sessions every 10 minutes (unref in Node). */
  @Scheduled(fixedDelay = 600000)
  public void evictExpired() {
    long now = System.currentTimeMillis();
    int before = activeSessions.size();
    activeSessions.entrySet().removeIf(e -> e.getValue() < now);
    int after = activeSessions.size();
    if (before != after) {
      log.info("[auth] evicted {} expired sessions ({} -> {})", before - after, before, after);
    }
  }
}
