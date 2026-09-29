import { expect } from "@playwright/test";
import { test } from "../../support/fixtures";
import { openModule, treeItem } from "../../support/helpers";

/**
 * Module registry — catalog rendering (plan/TEST_PLAN.md §3.5 #13 — extended pack).
 * Guards both the UI (ds-tree + detail panel) and the API (builtin/active flags)
 * for the post-normalization registry surface (moduleContents, contentKey).
 */
test.describe("module registry contents", () => {
  // Given: the module registry with the builtin catalog seeded.
  // When: the "AI Hub" module row is selected in the left tree.
  // Then: its four catalog contents (main, settings, providers, quick-chat) are
  //       visible in the detail panel — the catalog reached the browser.
  test("builtin module contents tree renders with the catalog entries", async ({ page }) => {
    await openModule(page, "module-registry:main");

    await treeItem(page, "AI Hub").click();

    for (const contentKey of ["main", "settings", "providers", "quick-chat"]) {
      await expect(page.getByText(contentKey, { exact: true }).first()).toBeVisible();
    }
  });

  // Given: the full registry catalog (GET /api/registry/modules is the source
  //        of truth for the tree).
  // When: the registry is queried.
  // Then: known catalog modules report builtin=true and the e2e tenant's
  //       disabled builtin (sample-embedded) reports active=false — proving the
  //       per-key builtin map from tenant.json reached the DB.
  test("registry lists all catalog modules with builtins flagged", async ({ request }) => {
    const body = await (await request.get("/api/registry/modules")).json();
    const byKey = new Map(body.modules.map((m: { key: string; builtin: boolean }) => [m.key, m]));
    for (const key of ["portal-dashboard", "ai-hub", "navigation"]) {
      expect(byKey.get(key)?.builtin, `${key} should be builtin`).toBe(true);
    }
    expect(byKey.get("sample-embedded")?.active).toBe(false);
  });
});
