import { execSync } from 'node:child_process';
import { existsSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..', '..');
const envFile = path.join(here, '..', '.env.e2e');
const marker = path.join(here, '..', '.stack-owned');
const project = 'crosshubber-e2e';
const portalUrl = process.env.E2E_PORTAL_URL ?? 'http://localhost:28094';
const expectedBranding = process.env.E2E_BRANDING_NAME ?? 'Crosshubber E2E';

function compose(args) {
  execSync(`docker compose -p ${project} --env-file "${envFile}" ${args}`, {
    stdio: 'inherit',
    cwd: root,
  });
}

async function fetchJson(url) {
  try {
    const res = await fetch(url);
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

async function sleep(ms) {
  await new Promise((r) => setTimeout(r, ms));
}

async function waitStack(timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const health = await fetchJson(`${portalUrl}/healthz`);
    if (health?.ok === true) {
      const branding = await fetchJson(`${portalUrl}/api/branding`);
      if (branding?.name === expectedBranding) return;
      if (branding != null) {
        throw new Error(
          `port ${portalUrl} serves tenant "${branding.name}" - expected the e2e tenant ("${expectedBranding}"). Stop whatever occupies these ports.`,
        );
      }
    }
    await sleep(3000);
  }
  throw new Error(`e2e stack did not become healthy within ${timeoutMs / 1000}s`);
}

const cmd = process.argv[2];
if (cmd === 'up') {
  const existing = await fetchJson(`${portalUrl}/api/branding`);
  if (existing?.name === expectedBranding) {
    console.log('[e2e-stack] already up with the e2e tenant');
    process.exit(0);
  }
  if (existing != null) {
    console.error(
      `port ${portalUrl} serves tenant "${existing.name}" - expected the e2e tenant. Stop the occupier first.`,
    );
    process.exit(1);
  }
  compose('up -d --build portal');
  writeFileSync(marker, new Date().toISOString());
  await waitStack(480_000);
  console.log('[e2e-stack] up');
  process.exit(0);
} else if (cmd === 'down') {
  if (existsSync(marker)) {
    compose('down -v');
    rmSync(marker, { force: true });
    console.log('[e2e-stack] down (volumes removed)');
  } else {
    console.log('[e2e-stack] not owned by this run - leaving it running');
  }
  process.exit(0);
} else {
  console.error('usage: node scripts/stack.mjs up|down');
  process.exit(2);
}
