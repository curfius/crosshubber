import { execSync } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";

/**
 * Ran after every Playwright run. Tears down the ephemeral e2e stack that
 * webServer/stack.mjs booted, but only when this run owns the stack
 * (marker file .stack-owned exists). Use E2E_KEEP_STACK=1 to keep the
 * stack up for debugging — the marker is deliberately left behind.
 */
const root = process.cwd();
const marker = path.join(root, ".stack-owned");

export default function globalTeardown() {
  if (process.env.E2E_KEEP_STACK === "1") {
    console.log("[e2e] E2E_KEEP_STACK=1 - leaving the stack running");
    return;
  }
  if (!existsSync(marker)) {
    console.log("[e2e] stack was already running before the suite - not tearing it down");
    return;
  }
  execSync("node scripts/stack.mjs down", { stdio: "inherit", cwd: root });
}
