import { expect } from "@playwright/test";
import { test } from "../../support/fixtures";
import { openModule } from "../../support/helpers";

/**
 * Navigation feature flags — portal-nav editor (plan/TEST_PLAN.md §3.5 #12 — extended pack).
 * The two feature switches (pinnedAppsEnabled, workspacesEnabled) are runtime-owned
 * and saved immediately on toggle (no Save button). This spec guards the minimal
 * round-trip and the tenant baseline value, and restores state so the pack stays
 * deterministic (the next run's baseline assertion would otherwise see the flipped
 * value).
 */
test.describe("navigation feature flags (portal-nav editor)", () => {
  // Given: the portal-nav editor with the pinnedAppsEnabled switch (first switch
  //        on the page) reflecting the current flag (baseline = true).
  // When: the switch is clicked off, then on again.
  // Then: aria-checked flips to "false" and back to the initial value — proving
  //       the click handler fires, the PUT succeeds, and the UI reflects the
  //       persisted state. The second click restores the baseline.
  test("toggle pinned apps off and back on; the switch reflects it", async ({ page }) => {
    await openModule(page, "navigation:portal-nav");

    const pinnedSwitch = page.getByRole("switch").first();
    await expect(pinnedSwitch).toBeVisible();
    const initial = (await pinnedSwitch.getAttribute("aria-checked")) ?? "true";

    await pinnedSwitch.click();
    await expect(pinnedSwitch).toHaveAttribute("aria-checked", "false");

    await pinnedSwitch.click();
    await expect(pinnedSwitch).toHaveAttribute("aria-checked", initial);
  });

  // Given: a fresh load of the portal-nav editor (tenant baseline: pinnedAppsEnabled=true).
  // When: the page renders.
  // Then: the first switch (pinnedAppsEnabled) reports aria-checked=true — the
  //       e2e tenant inherits the baseline feature flags.
  test("feature flag starts enabled per tenant baseline", async ({ page }) => {
    await openModule(page, "navigation:portal-nav");
    await expect(page.getByRole("switch").first()).toHaveAttribute("aria-checked", "true");
  });
});
