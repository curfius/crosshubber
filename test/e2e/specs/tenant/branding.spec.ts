import { expect } from "@playwright/test";
import { test } from "../../support/fixtures";
import { EXPECTED, expandSidebar } from "../../support/helpers";

/**
 * Tenant branding (plan/TEST_PLAN.md §3.5 #3).
 * Driven by tenants-config/e2e/tenant.json:
 *   branding.name = "Crosshubber E2E", branding.title = "Crosshubber E2E Portal".
 * Guards the Reconciler's tenant_meta branding rows end-to-end: served publicly by
 * GET /api/branding, applied to document.title by BrandingService, and rendered as
 * the sidebar wordmark. Fallback surfaces (no branding block) are covered by
 * BrandingControllerTest + BrandingService.spec on the unit level.
 */
test.describe("tenant branding", () => {
  // Given: a tenant whose branding block is present (config-owned, re-asserted at boot).
  // When: the shell boots (BrandingService fetched /api/branding).
  // Then: document.title = the tenant title and the sidebar wordmark shows the
  //       tenant name (requires the hover-expanded rail — the collapsed rail hides it).
  test("shell carries the tenant title and sidebar wordmark", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("aside")).toBeVisible();

    await expect(page).toHaveTitle(EXPECTED.brandTitle);
    await expandSidebar(page);
    await expect(
      page.locator("aside").getByText(EXPECTED.brandName, { exact: true }),
    ).toBeVisible();
  });

  // Given: the Reconciler wrote branding.name/title into tenant_meta at boot.
  // When: the public branding endpoint is queried (no auth — the login screen uses it).
  // Then: it serves exactly the tenant values, proving the write path + read path agree.
  test("public branding endpoint serves the tenant values", async ({ request }) => {
    const branding = await (await request.get("/api/branding")).json();
    expect(branding).toMatchObject({
      name: EXPECTED.brandName,
      title: EXPECTED.brandTitle,
    });
  });
});
