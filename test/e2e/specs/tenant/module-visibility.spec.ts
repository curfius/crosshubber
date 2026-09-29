import { expect } from "@playwright/test";
import { test } from "../../support/fixtures";
import { EXPECTED, expandSidebar } from "../../support/helpers";

/**
 * Tenant module availability (plan/TEST_PLAN.md §3.5 #6).
 * Driven by tenants-config/e2e/tenant.json:
 *   modules.builtin.sample-embedded = false (per-key override map), modules.external = [].
 * Guards the fork contract "which modules a tenant sees": the Reconciler asserts
 * builtin active flags, ShellConfigService filters inactive modules out of /api/config,
 * and the UI never renders what the server no longer sends.
 */
test.describe("tenant module availability", () => {
  // Given: sample-embedded is disabled via the per-key builtin map; the other seven
  //        builtins are enabled.
  // When: the shell boots and the sidebar app grid is expanded.
  // Then: enabled builtins (AI Hub, Dashboard, ...) render and the disabled builtin
  //       is absent — availability is decided by tenant config, not the catalog.
  test("app grid shows enabled builtins only", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("aside")).toBeVisible();
    await expandSidebar(page);

    await expect(page.locator("aside").getByText("AI Hub", { exact: true })).toBeVisible();
    await expect(page.locator("aside").getByText("Dashboard", { exact: true })).toBeVisible();
    await expect(
      page.locator("aside").getByText(EXPECTED.hiddenModule, { exact: true }),
    ).toHaveCount(0);
  });

  // Given: a deep link targeting the disabled builtin's content key.
  // When: the shell cold-loads with ?app=sample-embedded:main.
  // Then: the module is NOT mounted anywhere — the coordinator's fallback chain
  //       takes over (home module) and no "Sample embedded" surface appears.
  test("disabled builtin deep-link falls back instead of opening the module", async ({ page }) => {
    await page.goto("/?app=sample-embedded:main");
    await expect(page.locator("aside")).toBeVisible();
    await page.waitForTimeout(1500);
    await expect(page.getByText(EXPECTED.hiddenModule)).toHaveCount(0);
  });

  // Given: the e2e tenant blanks modules.external (arrays REPLACE on deep-merge).
  // When: the registry catalog is queried.
  // Then: the demo external modules (huey, louie-mfe) were never installed —
  //       a fork boots with only the content its config declares.
  test("no external modules are registered", async ({ request }) => {
    const body = await (await request.get("/api/registry/modules")).json();
    const keys = body.modules.map((m: { key: string }) => m.key);
    expect(keys).not.toContain("huey");
    expect(keys).not.toContain("louie-mfe");
  });
});
