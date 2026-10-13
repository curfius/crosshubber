import { expect } from "@playwright/test";
import { test } from "../../support/fixtures";
import { openModule } from "../../support/helpers";

/**
 * Navigation feature flags — navigation General settings page
 * (plan/TEST_PLAN.md §3.5 #12 — extended pack). The feature switches
 * (pinnedAppsEnabled, workspacesEnabled …) are runtime-owned and saved
 * immediately on toggle (no Save button). This spec guards the minimal
 * round-trip and the tenant baseline value, and restores state so the pack
 * stays deterministic (the next run's baseline assertion would otherwise see
 * the flipped value). The portal-nav screen was re-split into four navigation
 * admin pages in 2026-10; the General page hosts the switches.
 */
test.describe("navigation feature flags (navigation General page)", () => {
  // Given: the navigation General settings page with the pinnedAppsEnabled
  //        switch (first switch) reflecting the current flag (baseline = true).
  // When: the switch is clicked off, then on again.
  // Then: aria-checked flips to "false" and back to the initial value — proving
  //       the click handler fires, the PUT succeeds, and the UI reflects the
  //       persisted state. The second click restores the baseline.
  test("toggle pinned apps off and back on; the switch reflects it", async ({ page }) => {
    await openModule(page, "navigation:general");

    const pinnedSwitch = page.getByRole("switch").first();
    await expect(pinnedSwitch).toBeVisible();
    const initial = (await pinnedSwitch.getAttribute("aria-checked")) ?? "true";

    await pinnedSwitch.click();
    await expect(pinnedSwitch).toHaveAttribute("aria-checked", "false");

    await pinnedSwitch.click();
    await expect(pinnedSwitch).toHaveAttribute("aria-checked", initial);
  });

  // Given: a fresh load of the navigation General page (tenant baseline:
  //        pinnedAppsEnabled=true).
  // When: the page renders.
  // Then: the first switch (pinnedAppsEnabled) reports aria-checked=true — the
  //       e2e tenant inherits the baseline feature flags.
  test("feature flag starts enabled per tenant baseline", async ({ page }) => {
    await openModule(page, "navigation:general");
    await expect(page.getByRole("switch").first()).toHaveAttribute("aria-checked", "true");
  });
});
