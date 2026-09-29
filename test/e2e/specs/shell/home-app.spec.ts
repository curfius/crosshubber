import { expect } from "@playwright/test";
import { test } from "../../support/fixtures";
import { EXPECTED } from "../../support/helpers";

/**
 * Home app deep-link (plan/TEST_PLAN.md §3.5 #8).
 * Driven by tenants-config/e2e/tenant.json:
 *   settings.homeApp = "navigation:portal". The shell's Home fixture mounts
 *   this module:content on cold load (resolved via WorkbenchService).
 * Guards the full home-resolution chain: instance settings → WorkbenchService
 * homeApp signal → navigation coordinator → URL ?app= plus heading render.
 */
test.describe("home app", () => {
  // Given: the tenant's homeApp points at navigation:portal (portal-navigation
  //        module, portal content).
  // When: the browser cold-loads "/" with no existing workspace.
  // Then: the URL gains ?app=navigation:portal and the Home fixture renders
  //       the Portal Navigation heading (English or pt-PT — regex covers both).
  test("cold load opens the configured home module", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("aside")).toBeVisible();

    await expect(page).toHaveURL(/app=navigation/);
    await expect(
      page.getByRole("heading", { name: /Portal Navigation|Navegação do Portal/ }).first(),
    ).toBeVisible();
  });
});
