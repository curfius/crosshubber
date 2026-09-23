package com.crosshubber.portal.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.crosshubber.portal.modules.navigation.layout.NavigationLayoutEntity;
import com.crosshubber.portal.modules.settings.instance.InstanceSettingsEntity;
import com.crosshubber.portal.modules.settings.modules.ModuleSettingsEntity;
import com.crosshubber.portal.workspaces.WorkspaceEntity;

import jakarta.persistence.Version;

/** OPTIMIZATIONS #12: optimistic locking maps to 409 and the four entities are versioned. */
class GlobalExceptionHandlerConflictTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void optimisticLockingFailureMapsTo409Envelope() {
    ResponseEntity<Map<String, String>> response =
        handler.handleOptimisticLock(new OptimisticLockingFailureException("stale"));
    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(
        "conflict: resource changed concurrently - reload and retry",
        response.getBody().get("error"));
  }

  @Test
  void versionedEntitiesCarryVersionField() throws Exception {
    assertNotNull(
        InstanceSettingsEntity.class.getDeclaredField("version").getAnnotation(Version.class));
    assertNotNull(
        NavigationLayoutEntity.class.getDeclaredField("version").getAnnotation(Version.class));
    assertNotNull(
        ModuleSettingsEntity.class.getDeclaredField("version").getAnnotation(Version.class));
    assertNotNull(WorkspaceEntity.class.getDeclaredField("version").getAnnotation(Version.class));
  }
}
