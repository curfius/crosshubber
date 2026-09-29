import { expect } from "@playwright/test";
import { test } from "../../support/fixtures";
import { openModule } from "../../support/helpers";

const KEY = "login.footer";
const ORIGINAL = "Authenticated via Keycloak — one session for every app.";
const EDITED = "E2E edited footer value.";

/**
 * I18n labels admin (plan/TEST_PLAN.md §3.5 #11 — extended pack).
 * Exercises the edit-save-round-trip for runtime-owned labels (admin-edited values
 * are insert-if-absent in the catalog). The chosen key (login.footer) is present
 * in every language and its English value is stable (contains an em-dash, so the
 * file must stay UTF-8 — PS Set-Content would corrupt it).
 *
 * Determinism: the spec resets the key to the seed value before editing and
 * restores it afterwards — prior failed runs could leave EDITED in the DB
 * (filling the same value is a no-op → no dirty flag → no save), and the E2E
 * tenant must stay deterministic for future runs.
 */
test.describe("i18n labels admin", () => {
  // Given: the label key exists with its seed value.
  // When: the admin edits the value via the UI, saves, and the API is queried.
  // Then: the PUT succeeds (<300), GET returns the edited value, and a second
  //       PUT restores the seed — the cycle proves the admin write path + API
  //       persistence + the restore needed for cross-run determinism.
  test("edit a label, save, see it in the API; restore afterwards", async ({ page, request }) => {
    // Self-healing start: a prior failed run could have left EDITED in the DB;
    // filling the same value would be a no-op (dirty=false → save disabled).
    const reset = await request.put("/api/i18n/labels/en-GB", {
      data: { entries: [{ key: KEY, value: ORIGINAL }] },
    });
    expect(reset.ok()).toBeTruthy();

    await openModule(page, "i18n-settings:labels");
    await expect(page.locator("#i18n-labels-lang")).toBeVisible();

    await page.locator("#i18n-labels-lang").selectOption("en-GB");
    await page.locator("#i18n-labels-search").fill(KEY);
    const row = page.locator("tr", { hasText: KEY });
    await expect(row).toBeVisible();

    await row.locator("input.ds-input").first().fill(EDITED);
    await expect(page.locator(".ds-badge-warning").first()).toBeVisible();

    // Save button sits in the editor header (previous sibling of the card holding the search box).
    const saveButton = page.locator(
      "xpath=//input[@id='i18n-labels-search']/ancestor::div[3]/preceding-sibling::div[1]//button[contains(@class,'ds-btn-primary')]",
    );
    await expect(saveButton).toBeEnabled();

    const saveResponse = page.waitForResponse(
      (r) => r.url().includes("/api/i18n/labels/en-GB") && r.request().method() === "PUT",
    );
    await saveButton.click();
    expect((await saveResponse).status()).toBeLessThan(300);

    const labels = await (await request.get("/api/i18n/labels/en-GB")).json();
    const actual = labels.labels ? labels.labels[KEY] : labels[KEY];
    expect(actual).toBe(EDITED);

    // Restore the seed value so the tenant stays deterministic for future runs.
    const restore = await request.put("/api/i18n/labels/en-GB", {
      data: { entries: [{ key: KEY, value: ORIGINAL }] },
    });
    expect(restore.ok()).toBeTruthy();

    const restored = await (await request.get("/api/i18n/labels/en-GB")).json();
    const restoredValue = restored.labels ? restored.labels[KEY] : restored[KEY];
    expect(restoredValue).toBe(ORIGINAL);
  });
});
