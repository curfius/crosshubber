package com.crosshubber.portal.modules.navigation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class NavigationValidationServiceTest {

  // --- validateShellTree ---

  @Test
  void validateShellTreeRejectsUnknownParent() {
    NavigationValidationService.Validation res =
        NavigationValidationService.validateShellTree(
            List.of(new NavigationValidationService.ShellGroup("nav-a", "A", "nav-x")), List.of());
    assertFalse(res.success());
    assertTrue(res.error().contains("unknown parent"));
  }

  @Test
  void validateShellTreeRejectsRootLevelItems() {
    NavigationValidationService.Validation res =
        NavigationValidationService.validateShellTree(
            List.of(new NavigationValidationService.ShellGroup("nav-a", "A", null)),
            List.of(new NavigationValidationService.ShellItem("m", "one", null)));
    assertFalse(res.success());
    assertTrue(res.error().contains("must live in a section"));
  }

  @Test
  void validateShellTreeRejectsUnknownItemGroup() {
    NavigationValidationService.Validation res =
        NavigationValidationService.validateShellTree(
            List.of(new NavigationValidationService.ShellGroup("nav-a", "A", null)),
            List.of(new NavigationValidationService.ShellItem("m", "one", "nav-b")));
    assertFalse(res.success());
    assertTrue(res.error().contains("unknown group"));
  }

  @Test
  void validateShellTreeAcceptsGroupedItems() {
    NavigationValidationService.Validation res =
        NavigationValidationService.validateShellTree(
            List.of(new NavigationValidationService.ShellGroup("nav-a", "A", null)),
            List.of(new NavigationValidationService.ShellItem("m", "one", "nav-a")));
    assertTrue(res.success());
  }
}
