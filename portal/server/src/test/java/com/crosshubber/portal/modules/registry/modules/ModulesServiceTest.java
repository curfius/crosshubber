package com.crosshubber.portal.modules.registry.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.common.JsonUtils;
import com.crosshubber.portal.modules.settings.modules.ModuleSettingsService;

/** Uninstall pruning: remove() deletes the module and prunes its settings row (Phase 4). */
class ModulesServiceTest {

  private ModuleRepository repo;
  private ModuleSettingsService settings;
  private ModulesService service;

  @BeforeEach
  void setUp() {
    repo = mock(ModuleRepository.class);
    settings = mock(ModuleSettingsService.class);
    service = new ModulesService(repo, mock(JsonUtils.class), settings);
  }

  private static ModuleEntity module(boolean builtin) {
    ModuleEntity m = new ModuleEntity();
    m.setBuiltin(builtin);
    return m;
  }

  @Test
  void removePrunesSettingsWithTheModule() {
    ModuleEntity m = module(false);
    when(repo.findById("demo")).thenReturn(Optional.of(m));

    assertTrue(service.remove("demo"));

    verify(settings).delete("demo");
    verify(repo).delete(m);
  }

  @Test
  void removeRejectsBuiltinModules() {
    when(repo.findById("portal-dashboard")).thenReturn(Optional.of(module(true)));

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> service.remove("portal-dashboard"));

    assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    verify(settings, never()).delete("demo");
    verify(repo, never()).delete(any());
  }

  @Test
  void removeMissingModuleReturnsFalse() {
    when(repo.findById("ghost")).thenReturn(Optional.empty());

    assertFalse(service.remove("ghost"));

    verify(settings, never()).delete("ghost");
    verify(repo, never()).delete(any());
  }
}
