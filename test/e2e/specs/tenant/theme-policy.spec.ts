import { expect } from "@playwright/test";
import { test } from "../../support/fixtures";
import { EXPECTED, openModule } from "../../support/helpers";

/**
 * Tenant theme policy (plan/TEST_PLAN.md §3.5 #4).
 * Driven by tenants-config/e2e/tenant.json:
 *   settings.defaultTheme = "ocean", settings.enabledThemes = [ocean, light, nord].
 * Guards the ThemeService policy chain end-to-end: instance settings → GET /api/settings
 * → shell boot (setPolicy before preference correction) → picker catalog restriction.
 */
test.describe("tenant theme policy", () => {
  // Given: a fresh browser context — no localStorage cache, no stored user preference.
  // When: the shell boots and applies the tenant theme policy (setPolicy).
  // Then: data-theme = "ocean" — the tenant default wins over the compile-time
  //       DEFAULT_THEME fallback ("dark-slate", which is not even enabled here).
  test("tenant default theme is applied on boot", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("aside")).toBeVisible();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "ocean");
  });

  // Given: enabledThemes restricts the selectable catalog to Ocean/Light/Nord.
  // When: the General user-settings screen renders the picker.
  // Then: exactly the three enabled themes are offered and the disabled themes
  //       (e.g. "Dark Slate") are absent — the picker binds the narrowed list.
  test("theme picker lists exactly the enabled set", async ({ page }) => {
    await openModule(page, "user-settings:general");

    for (const theme of EXPECTED.themes) {
      await expect(page.getByRole("button", { name: theme, exact: true })).toBeVisible();
    }
    await expect(page.getByRole("button", { name: EXPECTED.hiddenTheme, exact: true })).toHaveCount(
      0,
    );
  });

  // Given: the tenant-restricted picker.
  // When: the user selects "Light" and reloads the page.
  // Then: data-theme = "light" survives the reload (localStorage cache + persisted
  //       user preference, and "light" is inside the enabled set so the policy keeps it).
  // NOTE: restores "Ocean" afterwards — the choice persists in user_settings across
  // runs and would otherwise flip the boot-theme assertion above on the next run.
  test("switching a theme applies it and survives reload", async ({ page }) => {
    await openModule(page, "user-settings:general");
    await page.getByRole("button", { name: "Light", exact: true }).click();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "light");

    await page.reload();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "light");

    // Restore: theme choice persists per user; leave the tenant default for other specs.
    await page.getByRole("button", { name: "Ocean", exact: true }).click();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "ocean");
  });
});
