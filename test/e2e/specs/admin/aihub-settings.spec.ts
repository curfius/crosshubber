import { expect } from "@playwright/test";
import { test } from "../../support/fixtures";
import { openModule, providerHeaderSwitch, treeItem } from "../../support/helpers";

/**
 * AI Hub settings admin (plan/TEST_PLAN.md §3.5 #14 — extended pack).
 * The e2e tenant pre-enables only "anthropic"; every other seed provider stays
 * disabled. Enabling a provider is a runtime-owned write (unlisted id) and must
 * be revertible in-place so the pack stays deterministic.
 */
test.describe("AI Hub settings admin", () => {
  // Given: the AI Hub providers screen with "OpenAI" disabled (tenant pre-enable
  //        list contains only anthropic).
  // When: the OpenAI detail is opened, its enable switch is clicked on, then off.
  // Then: aria-checked flips false → true → false — the runtime toggle works and
  //       the second click restores the tenant baseline (anthropic-only).
  test("enable a provider at runtime and revert it", async ({ page }) => {
    await openModule(page, "ai-hub:providers");

    await treeItem(page, "OpenAI").click();
    await expect(page.getByRole("heading", { name: "OpenAI", exact: true })).toBeVisible();

    const enableSwitch = providerHeaderSwitch(page, "OpenAI");
    await expect(enableSwitch).toHaveAttribute("aria-checked", "false");

    await enableSwitch.click();
    await expect(enableSwitch).toHaveAttribute("aria-checked", "true");

    await enableSwitch.click();
    await expect(enableSwitch).toHaveAttribute("aria-checked", "false");
  });
});
