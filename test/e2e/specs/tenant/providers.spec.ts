import { expect } from "@playwright/test";
import { test } from "../../support/fixtures";
import { openModule, providerHeaderSwitch, treeItem } from "../../support/helpers";

/**
 * AI Hub provider pre-enable policy (plan/TEST_PLAN.md §3.5 #7).
 * Driven by tenants-config/e2e/tenant.json:
 *   aiHub.enabledProviders = ["anthropic"] — SEED_PROVIDERS catalog seeded
 *   enabled=false, then the Reconciler re-asserts listed ids enabled=true
 *   at every boot (present = config-owned; unlisted = admin-owned).
 * Guards the bootstrap chain end-to-end: the provider exists, its detail
 * panel renders, and its enable switch reflects the tenant policy.
 */
test.describe("AI Hub provider pre-enable policy", () => {
  // Given: the e2e tenant pre-enables only "anthropic" (all other seed
  //        providers stay disabled).
  // When: the AI Hub providers screen is opened and each provider's detail
  //       panel is selected via the left tree.
  // Then: Anthropic's header switch is on (aria-checked=true) and every
  //       other listed vendor (OpenAI, Ollama) is off — proving the
  //       Reconciler's pre-enable assertion reached the UI.
  test("anthropic is pre-enabled, the rest are not", async ({ page }) => {
    await openModule(page, "ai-hub:providers");

    await treeItem(page, "Anthropic").click();
    await expect(page.getByRole("heading", { name: "Anthropic", exact: true })).toBeVisible();
    await expect(providerHeaderSwitch(page, "Anthropic")).toHaveAttribute("aria-checked", "true");

    for (const vendor of ["OpenAI", "Ollama (local)"]) {
      await treeItem(page, vendor).click();
      await expect(providerHeaderSwitch(page, vendor)).toHaveAttribute("aria-checked", "false");
    }
  });
});
