import { expect, type Page } from "@playwright/test";

/**
 * Pins the UI language to English (UK). The DB-persisted user preference (server-side)
 * wins over the browser locale, and some tests mutate it — every spec that asserts
 * English copy calls this first; it is idempotent and leaves the preference on en-GB.
 */
export async function ensureEnglish(page: Page): Promise<void> {
  await page.goto("/");
  await expect(page.locator("aside")).toBeVisible();
  if ((await page.locator("html").getAttribute("lang")) === "en-GB") return;
  await page.getByRole("button", { name: "Idioma" }).click();
  await page.getByRole("button", { name: "English (UK)" }).click();
  await expect(page.locator("html")).toHaveAttribute("lang", "en-GB");
}

/** Deep-links into an embedded module (`?app=moduleKey:contentKey`) with English UI. */
export async function openModule(page: Page, ref: string): Promise<void> {
  await ensureEnglish(page);
  await page.goto(`/?app=${ref}`);
  await expect(page.locator("aside")).toBeVisible();
  await expect(page.locator("app-workspace-toolbar")).toBeVisible();
}

/** Hovers the collapsed sidebar so full names/wordmark render. */
export async function expandSidebar(page: Page): Promise<void> {
  await page.locator("aside").hover();
  await page.waitForTimeout(400);
}

/** Opens the toolbar language menu (label follows the active UI language). */
export async function openLanguageMenu(page: Page): Promise<void> {
  const label =
    (await page.locator("html").getAttribute("lang")) === "pt-PT" ? "Idioma" : "Language";
  await page.getByRole("button", { name: label }).click();
}

/** The enable/disable switch in the provider detail header. */
export function providerHeaderSwitch(page: Page, providerName: string) {
  return page
    .getByRole("heading", { name: providerName })
    .locator("xpath=ancestor::div[3]")
    .getByRole("switch");
}

/** Selects a registry/provider tree item (node text carries a meta suffix like "\nintegrado"). */
export function treeItem(page: Page, name: string) {
  return page.locator("app-ds-tree button").filter({ hasText: name }).first();
}

export const EXPECTED = {
  brandName: "Crosshubber E2E",
  brandTitle: "Crosshubber E2E Portal",
  languages: ["English (UK)", "Português (Portugal)"],
  themes: ["Ocean", "Light", "Nord"],
  hiddenTheme: "Dark Slate",
  hiddenModule: "Sample embedded",
  builtinManagedHint: "Managed by tenant config",
  homeModuleHeading: "Portal Navigation",
};
