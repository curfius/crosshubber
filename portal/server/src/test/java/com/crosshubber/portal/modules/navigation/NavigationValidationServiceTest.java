package com.crosshubber.portal.modules.navigation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NavigationValidationServiceTest {

  // --- validateShellTree: unchanged baseline guard ---

  @Test
  void validateShellTreeRejectsUnknownParent() {
    NavigationValidationService.Validation res =
        NavigationValidationService.validateShellTree(
            java.util.List.of(new NavigationValidationService.ShellGroup("nav-a", "A", "nav-x")),
            java.util.List.of());
    assertFalse(res.success());
    assertTrue(res.error().contains("unknown parent"));
  }
}
