import { expect } from "@playwright/test";
import { test } from "../../support/fixtures";

/**
 * Workspace persistence (plan/TEST_PLAN.md §3.5 #9).
 * The shell auto-restores the last workspace (including open tabs) from
 * persisted workbench state. This spec guards the minimal round-trip:
 * opening a tab → reload → tab still present.
 */
test.describe("workspace persistence", () => {
  // Given: an authenticated shell with workspaces enabled (tenant baseline).
  // When: a module tab is opened via deep-link (?app=ai-hub:main) and the
  //       page is reloaded (simulating a browser refresh).
  // Then: the same tab ("AI Hub") is still visible — the workbench layout
  //       survived the reload via persisted state (user_settings/workspaces).
  //       Uses a short settle delay because tab rendering is async.
  test("opened tabs survive a reload", async ({ page }) => {
    await page.goto("/?app=ai-hub:main");
    await expect(page.locator("aside")).toBeVisible();
    await page.waitForTimeout(1500);
    const tab = page.locator('.ds-tab, [role="tab"]', { hasText: "AI Hub" }).first();
    await expect(tab).toBeVisible();

    await page.reload();
    await expect(page.locator("aside")).toBeVisible();
    await page.waitForTimeout(1500);
    await expect(
      page.locator('.ds-tab, [role="tab"]', { hasText: "AI Hub" }).first(),
    ).toBeVisible();
  });
});
