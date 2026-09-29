import { expect } from "@playwright/test";
import { test } from "../../support/fixtures";
import { EXPECTED, openLanguageMenu } from "../../support/helpers";

/**
 * Tenant language policy (plan/TEST_PLAN.md §3.5 #5).
 * Driven by tenants-config/e2e/tenant.json:
 *   i18n.enabledLanguages = [en-GB, pt-PT], i18n.defaultLanguage = "pt-PT".
 * Guards the reconciler's language assertions across the whole stack: the i18n
 * config endpoint, the toolbar switcher, and per-user language persistence.
 * The DB-persisted user preference deliberately beats the browser locale —
 * specs here toggle language explicitly and restore en-GB before finishing.
 */
test.describe("tenant language policy", () => {
  // Given: only en-GB and pt-PT are enabled for this tenant (fr-FR/es-ES seeded but
  //        asserted disabled by the Reconciler).
  // When: the toolbar language menu is opened.
  // Then: exactly the two enabled languages are offered; the disabled catalog
  //       languages never appear — the enabled flag gates the switcher.
  test("switcher offers exactly the enabled languages", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("aside")).toBeVisible();

    await openLanguageMenu(page);
    for (const language of EXPECTED.languages) {
      await expect(page.getByRole("button", { name: language })).toBeVisible();
    }
    await expect(page.getByRole("button", { name: "Français (France)" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Español (España)" })).toHaveCount(0);
  });

  // Given: the switcher with the tenant's two languages.
  // When: the user switches to pt-PT and reloads.
  // Then: <html lang> = "pt-PT" survives the reload — the choice persists to
  //       localStorage AND user_settings (server-side), and applyServerPreference
  //       re-applies it on the next boot.
  // NOTE: restores en-GB afterwards — the preference persists server-side across
  // runs and would otherwise flip every English-copy spec on the next run.
  test("language switch persists across reload and restores cleanly", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("aside")).toBeVisible();

    await openLanguageMenu(page);
    await page.getByRole("button", { name: "Português (Portugal)" }).click();
    await expect(page.locator("html")).toHaveAttribute("lang", "pt-PT");

    await page.reload();
    await expect(page.locator("html")).toHaveAttribute("lang", "pt-PT");

    // Restore: server-side preference is persisted; leave it on en-GB for other specs.
    await openLanguageMenu(page);
    await page.getByRole("button", { name: "English (UK)" }).click();
    await expect(page.locator("html")).toHaveAttribute("lang", "en-GB");
  });

  // Given: the Reconciler asserted i18n.defaultLanguage + the enabled set at boot.
  // When: the public config endpoint is queried.
  // Then: defaultLanguage = pt-PT and exactly en-GB + pt-PT report enabled=true —
  //       the server-side source of truth the UI switcher derives from.
  test("server reports the configured default language", async ({ request }) => {
    const config = await (await request.get("/api/i18n/config")).json();
    expect(config.defaultLanguage).toBe("pt-PT");
    const enabled = config.languages
      .filter((l: { enabled: boolean }) => l.enabled)
      .map((l: { code: string }) => l.code);
    expect(enabled.sort()).toEqual(["en-GB", "pt-PT"]);
  });
});
