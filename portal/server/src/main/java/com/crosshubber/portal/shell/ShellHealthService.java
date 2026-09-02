package com.crosshubber.portal.shell;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/** Shell health service — mirrors {@code portal/src/modules/portal/portal.service.ts}. */
@Service
public class ShellHealthService {

  private static final Logger log = LoggerFactory.getLogger(ShellHealthService.class);

  @PersistenceContext private EntityManager em;

  /**
   * Checks DB connectivity via SELECT 1.
   *
   * @return true if up
   */
  public boolean dbStatus() {
    try {
      Object result = em.createNativeQuery("SELECT 1").getSingleResult();
      return result != null;
    } catch (Exception e) {
      log.warn("[portal] dbStatus failed: {}", e.getMessage());
      return false;
    }
  }
}
