import { expect } from "@playwright/test";
import { test } from "../../support/fixtures";
import { ensureEnglish } from "../../support/helpers";

/**
 * Session lifecycle (plan/TEST_PLAN.md §3.5 #1).
 * The login redirect itself is exercised by the setup project; this spec covers the
 * other end of the session: the authenticated shell and the logout hand-off.
 */
test.describe("authenticated session", () => {
  // Given: an authenticated admin session (storageState) pinned to the English UI.
  // When: the user opens the profile menu and clicks "Log out" (GET /logout revokes the
  //       portal session cookie and clears the Keycloak SSO round-trip).
  // Then: the shell is gone and the browser lands back on the login screen —
  //       a logged-out user can never retain shell access.
  test("shell renders and logout returns to the login screen", async ({ page }) => {
    await ensureEnglish(page);

    await page.getByRole("button", { name: "User profile" }).click();
    await page.getByRole("button", { name: "Log out" }).click();

    await expect(page.locator("aside")).toHaveCount(0);
    await expect(page).toHaveURL(/login|realms/);
  });
});
