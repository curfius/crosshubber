import { expect } from "@playwright/test";
import { test } from "../../support/fixtures";
import { EXPECTED, openLanguageMenu } from "../../support/helpers";

/**
 * Limited-identity regression (plan/TEST_PLAN.md §3.5 #2).
 *
 * Runs under the `limited` project (storageState of e2e-user — no realm roles).
 * Guards the authorization boundary of the tenant-fork features: role-less users
 * keep read access to the shell data they need, but every admin write path —
 * including the tenant-policy endpoints — rejects them with the 403 envelope.
 */
test.describe("limited user (no admin roles)", () => {
  // Given: an authenticated session with zero realm roles.
  // When: the shell boots and the toolbar language menu is opened.
  // Then: the shell renders normally and the tenant language policy is visible to
  //       everyone — exactly the enabled languages (en-GB, pt-PT) appear, the
  //       disabled catalog languages (fr-FR, es-ES) do not.
  test("shell renders and the language switcher is available", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("aside")).toBeVisible();

    await openLanguageMenu(page);
    for (const language of EXPECTED.languages) {
      await expect(page.getByRole("button", { name: language })).toBeVisible();
    }
    await expect(page.getByRole("button", { name: "Français (France)" })).toHaveCount(0);
  });

  // Given: an authenticated session with zero realm roles.
  // When: the admin write endpoints are called directly through the authenticated
  //       request context — instance settings, registry module availability, and the
  //       i18n label store. Bodies are valid on purpose: bean validation runs AFTER
  //       method security, so a malformed body would 400 and mask the role check.
  // Then: every write returns 403 — @PreAuthorize (portal-settings-edit,
  //       portal-registry-edit, portal-i18n-edit) holds for role-less users.
  test("admin writes are rejected with 403", async ({ page }) => {
    const settings = await page.request.put("/api/settings", {
      data: { homeApp: "portal-dashboard:main" },
    });
    expect(settings.status()).toBe(403);

    const registry = await page.request.patch("/api/registry/modules/sample-embedded/active", {
      data: { active: true },
    });
    expect(registry.status()).toBe(403);

    const i18n = await page.request.put("/api/i18n/labels/en-GB", {
      data: { entries: [{ key: "login.footer", value: "nope" }] },
    });
    expect(i18n.status()).toBe(403);
  });

  // Given: an authenticated session with zero realm roles.
  // When: the user's own shell config and the registry catalog are read.
  // Then: both return 200 — the shell must render and admin screens must be viewable
  //       read-only; only writes are gated.
  test("reads stay available", async ({ page }) => {
    const config = await page.request.get("/api/config");
    expect(config.status()).toBe(200);

    const modules = await page.request.get("/api/registry/modules");
    expect(modules.status()).toBe(200);
  });
});
