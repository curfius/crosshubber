import { expect } from "@playwright/test";
import { test } from "../../support/fixtures";
import { EXPECTED, openModule, treeItem } from "../../support/helpers";

/**
 * Builtin registry guard (plan/TEST_PLAN.md §3.5 #10).
 * Driven by tenants-config/e2e/tenant.json:
 *   modules.builtin.sample-embedded = false — builtins are config-owned.
 * Guards both layers of the ownership rule:
 *   - API: PATCH /active on a builtin is rejected with 409 tenant-config-owned.
 *   - UI:  the module-registry detail header shows the "Managed by tenant config"
 *          hint and hides the enable/disable switch for builtins.
 */
test.describe("builtin registry guard", () => {
  // Given: sample-embedded is config-owned (builtin map, present = config-owned).
  // When: an admin tries to flip its active flag via the REST API.
  // Then: the server rejects with 409 and the error envelope contains
  //       "tenant-config-owned" — the ownership rule is enforced server-side.
  test("API rejects builtin active changes with 409", async ({ request }) => {
    const res = await request.patch("/api/registry/modules/sample-embedded/active", {
      data: { active: true },
    });
    expect(res.status()).toBe(409);
    expect((await res.json()).error).toContain("tenant-config-owned");
  });

  // Given: the module registry with a builtin module selected (AI Hub).
  // When: the detail header renders.
  // Then: the "Managed by tenant config" hint is visible and no enable/disable
  //       switch exists — the UI hides tenant-owned controls (honest, no silent
  //       revert on next boot).
  test("registry UI shows the managed hint instead of a switch for builtins", async ({ page }) => {
    await openModule(page, "module-registry:main");

    await treeItem(page, "AI Hub").click();
    // Detail header: h1 → inner div → gap-3 div → flex-wrap row holding hint + actions.
    const header = page
      .getByRole("heading", { name: "AI Hub", exact: true })
      .locator("xpath=ancestor::div[3]");
    await expect(header).toBeVisible();
    await expect(header.getByText(EXPECTED.builtinManagedHint)).toBeVisible();
    await expect(header.getByRole("switch")).toHaveCount(0);
  });
});
