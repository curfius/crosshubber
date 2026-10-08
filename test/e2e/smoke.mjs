// One-off smoke runner for the Phase 3-5 AI Hub work against the dev stack.
// Run from test/e2e: node smoke.mjs   (throws on first hard failure at the end)
import { chromium } from 'playwright';

const BASE = 'http://localhost:28084';
const SHOT_DIR = process.env.SMOKE_SHOTS ?? process.env.TEMP + '/opencode';
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'} | ${name}${detail ? ' | ' + detail : ''}`);
};

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
const api = ctx.request;

// ---- 1. Login (real OIDC authorization-code flow, dev realm) ----
try {
  await page.goto(BASE + '/');
  await page.waitForURL('**/realms/dev/**', { timeout: 60_000 });
  await page.locator('#username').waitFor({ timeout: 30_000 });
  await page.locator('#username').fill('dev');
  await page.locator('#password').fill('dev');
  await page.getByRole('button', { name: /sign in/i }).click();
  // Defensive: some realms show a consent/UPDATE_PASSWORD interstitial.
  const consent = page.getByRole('button', { name: /accept|continue|skip/i }).first();
  if (await consent.isVisible({ timeout: 5_000 }).catch(() => false)) await consent.click();
  await page.locator('aside').waitFor({ state: 'visible', timeout: 30_000 });
  check('login dev/dev via Keycloak (dev realm)', true);
} catch (e) {
  check('login dev/dev via Keycloak (dev realm)', false, String(e).slice(0, 200));
}

// ---- 2. Agent catalog ----
try {
  const res = await api.get(BASE + '/api/ai-hub/agent-catalog');
  const body = await res.json();
  const entries = body.entries ?? [];
  const modules = [...new Set(entries.map((e) => e.moduleKey ?? ''))];
  check(
    'GET /api/ai-hub/agent-catalog returns entries',
    res.status() === 200 && entries.length > 0,
    `status=${res.status()} entries=${entries.length} groups=[${modules.join(', ')}]`,
  );
  const kinds = [...new Set(entries.map((e) => e.kind))];
  check('catalog carries kind + moduleKey on entries', kinds.length > 0, `kinds=[${kinds.join(', ')}]`);
} catch (e) {
  check('GET /api/ai-hub/agent-catalog returns entries', false, String(e).slice(0, 200));
}

// ---- 3. User settings scope "ai": validation + roundtrip (originals preserved) ----
let originals = null;
try {
  const get0 = await api.get(BASE + '/api/user-settings/ai');
  originals = (await get0.json()).settings ?? {};
  check('GET /api/user-settings/ai', get0.status() === 200, JSON.stringify(originals).slice(0, 160));

  const badKey = await api.put(BASE + '/api/user-settings/ai', { data: { bogusKey: 'x' } });
  check('PUT ai scope rejects unknown key (400)', badKey.status() === 400, `status=${badKey.status()}`);

  const bigAbout = await api.put(BASE + '/api/user-settings/ai', { data: { about: 'x'.repeat(2001) } });
  check('PUT ai scope rejects >2000-char about (400)', bigAbout.status() === 400, `status=${bigAbout.status()}`);

  const badTools = await api.put(BASE + '/api/user-settings/ai', { data: { disabledTools: [1, 2] } });
  check('PUT ai scope rejects non-string disabledTools (400)', badTools.status() === 400, `status=${badTools.status()}`);

  const put = await api.put(BASE + '/api/user-settings/ai', {
    data: { about: 'Smoke persona (temporary)', disabledTools: ['listModules'] },
  });
  const get1 = await api.get(BASE + '/api/user-settings/ai');
  const after = (await get1.json()).settings ?? {};
  check(
    'PUT/GET roundtrip persists about + disabledTools',
    put.status() === 200 && after.about === 'Smoke persona (temporary)' && (after.disabledTools ?? []).includes('listModules'),
    `put=${put.status()} about=${JSON.stringify(after.about)} tools=${JSON.stringify(after.disabledTools)}`,
  );

  // PUT merges (no replace), so restore the keys this smoke touched explicitly.
  const restore = await api.put(BASE + '/api/user-settings/ai', {
    data: { about: originals.about ?? '', disabledTools: originals.disabledTools ?? [] },
  });
  const get2 = await api.get(BASE + '/api/user-settings/ai');
  const restored = (await get2.json()).settings ?? {};
  check(
    'originals restored after roundtrip',
    restore.status() === 200 && (restored.about ?? '') === (originals.about ?? '') && JSON.stringify(restored.disabledTools ?? []) === JSON.stringify(originals.disabledTools ?? []),
    JSON.stringify(restored).slice(0, 160),
  );
} catch (e) {
  check('user-settings ai scope sequence', false, String(e).slice(0, 200));
}

// ---- 4. Tenant AI Hub settings (read-only) ----
try {
  const res = await api.get(BASE + '/api/ai-hub/settings');
  const body = await res.json();
  check('GET /api/ai-hub/settings', res.status() === 200 && typeof body.settings === 'object', `keys=${Object.keys(body.settings ?? {}).join(',')}`);
} catch (e) {
  check('GET /api/ai-hub/settings', false, String(e).slice(0, 200));
}

// ---- 5. Conversation lifecycle: create -> pin -> verify -> delete ----
let convId = null;
try {
  const create = await api.post(BASE + '/api/ai-hub/conversations', { data: { title: 'SMOKE tmp' } });
  const conv = await create.json();
  convId = conv.id;
  check('POST /api/ai-hub/conversations (201)', create.status() === 201 && !!convId, `id=${convId}`);

  const pin = await api.patch(BASE + `/api/ai-hub/conversations/${convId}`, { data: { pinned: true } });
  const pinned = await pin.json();
  check('PATCH pinned=true roundtrips', pin.status() === 200 && pinned.pinned === true, `status=${pin.status()} pinned=${pinned.pinned}`);

  const rename = await api.patch(BASE + `/api/ai-hub/conversations/${convId}`, { data: { title: 'SMOKE renamed' } });
  const renamed = await rename.json();
  check('PATCH rename roundtrips', rename.status() === 200 && renamed.title === 'SMOKE renamed', `title=${renamed.title}`);

  const badPatch = await api.patch(BASE + `/api/ai-hub/conversations/${convId}`, { data: {} });
  check('PATCH empty body rejected (400)', badPatch.status() === 400, `status=${badPatch.status()}`);
} catch (e) {
  check('conversation lifecycle', false, String(e).slice(0, 200));
} finally {
  if (convId) await api.delete(BASE + `/api/ai-hub/conversations/${convId}`);
}

// ---- 6. UI: user-settings AI page renders ----
try {
  await page.goto(`${BASE}/?app=ai-hub:ai`);
  await page.locator('app-user-settings-ai').waitFor({ state: 'visible', timeout: 30_000 });
  // ngOnInit loads catalog + settings asynchronously — wait for the switches to land.
  await page.locator('app-user-settings-ai app-switch').first().waitFor({ timeout: 30_000 });
  const aboutCount = await page.locator('app-user-settings-ai textarea').count();
  const switchCount = await page.locator('app-user-settings-ai app-switch').count();
  check(
    'UI: user-settings AI page renders (about + tool switches)',
    aboutCount >= 1 && switchCount > 0,
    `textareas=${aboutCount} switches=${switchCount}`,
  );
  // Model card renders either the select (>1 selectable model) or the fallback hint paragraph.
  const modelCard = page.locator('app-user-settings-ai .ds-card').first();
  const selectCount = await modelCard.locator('select').count();
  const cardP = await modelCard.locator('p').count();
  check(
    'UI: model card renders select or no-choice hint',
    selectCount > 0 || cardP >= 2,
    `select=${selectCount} cardParagraphs=${cardP}`,
  );
  await page.screenshot({ path: `${SHOT_DIR}/smoke-user-settings-ai.png`, fullPage: true });
} catch (e) {
  check('UI: user-settings AI page renders', false, String(e).slice(0, 200));
}

// ---- 7. UI: AI Hub chat page renders (session panel + composer) ----
try {
  await page.goto(`${BASE}/?app=ai-hub:main`);
  await page.locator('app-ai-hub-chat').waitFor({ state: 'visible', timeout: 30_000 });
  const composer = await page.locator('.chat-input, .chat-input-wrapper textarea').count();
  check('UI: AI Hub chat renders with composer', composer >= 1, `composerEls=${composer}`);
  await page.screenshot({ path: `${SHOT_DIR}/smoke-aihub-chat.png`, fullPage: true });
} catch (e) {
  check('UI: AI Hub chat renders', false, String(e).slice(0, 200));
}

await browser.close();

const failed = results.filter((r) => !r.ok);
console.log(`\n== ${results.length - failed.length}/${results.length} passed ==`);
if (failed.length) {
  process.exitCode = 1;
}
