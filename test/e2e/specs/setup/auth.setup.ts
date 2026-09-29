import { expect, test as setup } from "@playwright/test";

const adminState = "storageState-admin.json";
const limitedState = "storageState-limited.json";

const ADMIN_USER = process.env.E2E_ADMIN_USER ?? "e2e-admin";
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? "e2e-admin-password";
const LIMITED_USER = process.env.E2E_LIMITED_USER ?? "e2e-user";
const LIMITED_PASSWORD = process.env.E2E_LIMITED_PASSWORD ?? "e2e-user-password";

/**
 * Auth setup project (plan/TEST_PLAN.md §3.4).
 *
 * Performs the REAL OIDC authorization-code flow once per identity and saves the
 * resulting session as a Playwright storageState; every other spec reuses it via the
 * `admin` / `limited` projects instead of repeating the Keycloak round-trip.
 *
 * The two identities come from tenants-config/e2e/realm.json:
 *   - e2e-admin: member of /portal-admins → all five portal-*-edit realm roles
 *   - e2e-user:  no groups → zero roles (drives the limited-user 403 matrix)
 */

/**
 * Drives the real login redirect chain: `/` → SPA boot → 401 → `/login` →
 * `/api/login/start` → Keycloak login form → OIDC callback → portal shell.
 * The saved state captures the `portalSession` cookie (and Keycloak SSO cookie).
 */
async function login(page, username, password, statePath) {
  await page.goto("/");
  await page.waitForURL("**/realms/e2e/**", { timeout: 60_000 });
  await page.locator("#username").fill(username);
  await page.locator("#password").fill(password);
  await page.getByRole("button", { name: /sign in/i }).click();
  // Back in the portal: the shell sidebar renders once /api/config succeeds.
  await expect(page.locator("aside")).toBeVisible({ timeout: 30_000 });
  await page.context().storageState({ path: statePath });
}

// Given: the ephemeral e2e stack with the imported realm.
// When: e2e-admin signs in through the Keycloak form.
// Then: storageState-admin.json carries an authenticated admin session.
setup("authenticate as e2e-admin", async ({ page }) => {
  await login(page, ADMIN_USER, ADMIN_PASSWORD, adminState);
});

// Given: the ephemeral e2e stack with the imported realm.
// When: e2e-user (role-less) signs in through the Keycloak form.
// Then: storageState-limited.json carries an authenticated but unprivileged session.
setup("authenticate as e2e-user", async ({ page }) => {
  await login(page, LIMITED_USER, LIMITED_PASSWORD, limitedState);
});
