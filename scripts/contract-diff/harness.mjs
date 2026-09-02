// Contract-diff harness â€” GAP_CLOSURE_IMPLEMENTATION_PLAN.md P9.2.
// One-off runtime verification: replays identical API calls against the Node reference stack
// (genportal) and the Java portal and reports identical | byte-diff | semantic-diff per endpoint.
//
// Usage:  node scripts/contract-diff/harness.mjs [--report <path>]
// Needs:  Node stack on :18084 and Java stack on :28084 (dev tenants, users dev/dev + devuser/dev).
//
// Auth mechanism (documented per plan Â§P9.2 step 2):
//   - Node stack: GET /api/login/start â†’ {flowId}; POST /api/login {flowId, username, password}
//     â†’ Set-Cookie portalSession.
//   - Java stack: browser-less OIDC dance â€” GET /api/login/start â†’ Spring oauth2-login â†’ Keycloak
//     login form (parsed + POSTed) â†’ /login/oauth2/code/portal â†’ real portalSession cookie with a
//     registered SessionService entry (minting directly would fail the SessionService.isValid check).
//   - Both stacks share SESSION_SECRET in the dev tenants (cookies are byte-compatible), but we
//     still log in properly on each so user-scoped data is owned by the same Keycloak user.

import { writeFileSync } from 'node:fs';

const NODE_BASE = process.env.NODE_BASE ?? 'http://localhost:18084';
const JAVA_BASE = process.env.JAVA_BASE ?? 'http://localhost:28084';
const USERS = {
  admin: { username: 'dev', password: 'dev' },
  limited: { username: 'devuser', password: 'dev' },
};
const REPORT_PATH = (() => {
  const i = process.argv.indexOf('--report');
  return i > -1 ? process.argv[i + 1] : 'scripts/contract-diff/contract-diff-report.md';
})();

// â”€â”€ HTTP plumbing â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

class CookieJar {
  constructor() {
    this.cookies = new Map();
  }
  absorb(res) {
    const setCookies = res.headers.getSetCookie?.() ?? [];
    for (const sc of setCookies) {
      const pair = sc.split(';')[0];
      const idx = pair.indexOf('=');
      if (idx > 0) this.cookies.set(pair.slice(0, idx).trim(), pair.slice(idx + 1).trim());
    }
  }
  header() {
    return [...this.cookies.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
  }
}

/** Non-following request with cookie jar; returns {res, text}. */
async function raw(jar, url, init = {}) {
  const headers = { ...(init.headers ?? {}) };
  if (jar.cookies.size) headers.cookie = jar.header();
  const res = await fetch(url, { ...init, headers, redirect: 'manual' });
  jar.absorb(res);
  const text = await res.text();
  return { res, text };
}

function locationOf(res, base) {
  const loc = res.headers.get('location');
  return loc ? new URL(loc, res.url || base).toString() : null;
}

/** Node stack login (flowId API). Returns portalSession token. */
async function nodeLogin(base, { username, password }) {
  const jar = new CookieJar();
  const start = await raw(jar, `${base}/api/login/start`);
  const { flowId } = JSON.parse(start.text);
  const login = await raw(jar, `${base}/api/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ flowId, username, password }),
  });
  const payload = JSON.parse(login.text);
  if (!payload.ok) throw new Error(`node login failed: ${login.text}`);
  return jar.cookies.get('portalSession');
}

function parseKcForm(html, baseUrl) {
  const form = html.match(/<form[^>]*>/)?.[0] ?? '';
  const action = (form.match(/action="([^"]+)"/) ?? [])[1];
  const fields = {};
  for (const input of html.matchAll(/<input\b[^>]*>/g)) {
    const tag = input[0];
    const name = tag.match(/name="([^"]+)"/)?.[1];
    const value = tag.match(/value="([^"]*)"/)?.[1] ?? '';
    const type = tag.match(/type="([^"]+)"/)?.[1] ?? 'text';
    if (name && type === 'hidden') fields[name] = value;
  }
  return { action: new URL(action, baseUrl).toString(), fields };
}

/** Java stack login: browser-less OIDC authorization-code dance. Returns portalSession token. */
async function javaLogin(base, { username, password }) {
  const jar = new CookieJar();
  let step = await raw(jar, `${base}/api/login/start`);
  let url = locationOf(step.res, base);
  let submitted = false;
  for (let hop = 0; hop < 12 && url; hop++) {
    step = await raw(jar, url);
    let next = locationOf(step.res, base);
    if (next) {
      url = next;
      continue;
    }
    if (step.res.status === 200 && !submitted) {
      if (!step.text.includes('<form')) break;
      const { action, fields } = parseKcForm(step.text, url);
      fields.username = username;
      fields.password = password;
      step = await raw(jar, action, {
        method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams(fields).toString(),
      });
      submitted = true;
      next = locationOf(step.res, base);
    }
    url = next;
  }
  const token = jar.cookies.get('portalSession');
  if (!token) throw new Error('java login produced no portalSession cookie');
  return token;
}

/** Authenticated JSON call. Returns {status, json, text}. */
async function call(base, token, method, path, body) {
  const headers = { cookie: `portalSession=${token}` };
  if (body !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(base + path, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }
  return { status: res.status, json, text };
}

// â”€â”€ Normalization (plan Â§P9.2 step 5) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ISO_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/;
const CONV_RE = /^conv_\d+_[0-9a-z]+$/;
const PREFIXED_RE = /^(tok|ch|dra)_[0-9a-z]+$/;

function normalize(value, key) {
  if (Array.isArray(value)) {
    const out = value.map((v) => normalize(v, key));
    // roles are a set â€” token/realm order is environment-dependent, sort for comparison
    return key === 'roles' ? out.sort() : out;
  }
  if (value !== null && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = normalize(v, k);
    return out;
  }
  if (typeof value === 'string') {
    if (UUID_RE.test(value)) return '<uuid>';
    if (ISO_RE.test(value)) return '<iso-ts>';
    if (CONV_RE.test(value)) return '<conv-id>';
    if (PREFIXED_RE.test(value)) return `<${value.slice(0, value.indexOf('_'))}-id>`;
    if (/^whseâ€¢â€¢â€¢/.test(value) || key === 'webhookSecretMasked') return '<masked-secret>';
    if (key === 'webhookUrl') return '<webhook-url>';
    if (value.includes('psql -U')) return '<psql-hint>';
    // UUIDs embedded in prose (e.g. installedBy "dev/Dev User (<sub>)") are stack-local
    const withUuid = value.replace(
      /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi,
      '<uuid>',
    );
    return withUuid;
  }
  if (typeof value === 'number') {
    if (key === 'savedAt') return '<epoch-ms>';
    if (key === 'contentVersion') return '<content-version>';
    if (key === 'versionId') return '<version-id>';
    if (key === 'manifestDigest') return '<digest>';
    if (key === 'id') return '<id>';
    return value;
  }
  return value;
}

function sortDeep(value) {
  if (Array.isArray(value)) return value.map(sortDeep);
  if (value !== null && typeof value === 'object') {
    const out = {};
    for (const k of Object.keys(value).sort()) out[k] = sortDeep(value[k]);
    return out;
  }
  return value;
}

// List endpoints accumulate unrelated historical data per stack â€” restrict the comparison to
// the items this run seeded (diff-* / captured ids), so pre-existing rows do not pollute verdicts.
function filterForPath(json, path, ids) {
  const startsWithDiff = (v, keys) =>
    keys.some((k) => typeof v[k] === 'string' && v[k].toLowerCase().startsWith('diff'));
  if (path === '/api/registry/modules' && json?.modules) {
    return { modules: json.modules.filter((m) => startsWithDiff(m, ['key'])) };
  }
  if (path === '/api/registry/entry-points' && json?.entryPoints) {
    return { entryPoints: json.entryPoints.filter((m) => startsWithDiff(m, ['moduleKey'])) };
  }
  if (path === '/api/config' && json?.entryPoints) {
    const entryPoints = json.entryPoints.filter((m) => startsWithDiff(m, ['moduleKey']));
    const usedGroupKeys = new Set(entryPoints.map((e) => e.groupKey).filter(Boolean));
    return {
      ...json,
      entryPoints,
      groups: (json.groups ?? []).filter((g) => usedGroupKeys.has(g.key) || startsWithDiff(g, ['key', 'name'])),
      // services[] is env-dependent (NATS_HTTP_URL / KEYCLOAK_PUBLIC_URL presence differs
      // between the two dev compose files) â€” compare the deterministic postgres entry only
      services: (json.services ?? []).filter((s) => s.key === 'postgres'),
    };
  }
  if (path === '/api/ai-hub/providers' && json?.providers) {
    return {
      providers: json.providers
        .filter((p) => startsWithDiff(p, ['id']))
        .map((p) => ({
          ...p,
          // tokens accumulate one per run (random ids) â€” compare only the one from this run
          tokens: ids.tokenId ? p.tokens.filter((t) => t.id === ids.tokenId) : p.tokens,
        })),
    };
  }
  if (path === '/api/ai-hub/channels' && json?.channels) {
    return {
      channels: ids.channelId
        ? json.channels.filter((c) => c.id === ids.channelId)
        : json.channels.filter((c) => startsWithDiff(c, ['name', 'id'])),
    };
  }
  if (path === '/api/ai-hub/conversations' && json?.conversations) {
    return {
      conversations: ids.conversationId
        ? json.conversations.filter((c) => c.id === ids.conversationId)
        : json.conversations.filter((c) => startsWithDiff(c, ['title', 'id'])),
    };
  }
  if (path === '/api/workspaces' && json?.workspaces) {
    return { workspaces: json.workspaces.filter((w) => startsWithDiff(w, ['name'])) };
  }
  if (path?.startsWith('/api/registry/versions/') && json?.versions?.length) {
    // versions accumulate one per run and list order differs per stack — compare the ACTIVE one
    const active = json.versions.filter((v) => v.status === 'active');
    return { versions: active.length ? [active.at(-1)] : [json.versions.at(-1)] };
  }
  return json;
}

// â”€â”€ Verdicts â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

function compare(node, java, path, nodeIds, javaIds) {
  if (node.status !== java.status) return 'semantic-diff';
  if (node.json === null || java.json === null) {
    return (node.text ?? '') === (java.text ?? '') ? 'identical' : 'semantic-diff';
  }
  const filteredA = filterForPath(node.json, path, nodeIds);
  const filteredB = filterForPath(java.json, path, javaIds);
  const normA = JSON.stringify(normalize(filteredA, ''));
  const normB = JSON.stringify(normalize(filteredB, ''));
  if (normA === normB) {
    return JSON.stringify(filteredA) === JSON.stringify(filteredB) ? 'identical' : 'byte-diff';
  }
  return 'semantic-diff';
}

// â”€â”€ Seed payloads (identical on both stacks) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

const MANIFEST = {
  manifestVersion: 1,
  key: 'diff-manifest-mod',
  name: 'Diff Manifest Mod',
  baseUrl: 'https://diff.example.com',
  health: '/healthz',
  content: {
    applications: [
      { key: 'diff-m-entry', name: 'Diff M Entry', type: 'iframe', url: 'https://diff.example.com/app' },
    ],
    features: [],
    adminSettings: [],
    userSettings: [],
  },
  security: { roles: [{ key: 'diff-m-role', name: 'Diff Role' }] },
};

async function seedStack(base, token) {
  const put = (path, body) => call(base, token, 'PUT', path, body);
  const post = (path, body) => call(base, token, 'POST', path, body);
  const out = {};
  await put('/api/settings', { pinnedAppsEnabled: true, workspacesEnabled: true });
  await put('/api/user-settings/diff-scope', { hello: 'world', n: 1, nested: { a: true } });
  await put('/api/navigation/user-settings', {
    sidebar: { showPinned: true, showWorkspaces: true, apps: ['diff-app-1'] },
    sidebarExpanded: ['diff-app-1'],
  });
  await put('/api/navigation/pinned-apps', {
    items: [{ nodeType: 'item', id: 'diff-app-1', name: 'Diff App', ref: 'diff-app-1' }],
  });
  await put('/api/module-settings/diff-probe', { greeting: 'hello', limit: 5 });
  await post('/api/workspaces', {
    name: 'diff-ws',
    description: 'diff workspace',
    layout: { z: 1 },
    groups: {},
    color: '#123456',
    status: 'active',
  });
  await post('/api/registry/modules', { key: 'diff-mod', name: 'Diff Mod', icon: 'box', roles: [], active: true });
  await post('/api/registry/entry-points', {
    moduleKey: 'diff-mod',
    entryKey: 'diff-entry',
    category: 'applications',
    name: 'Diff Entry',
    type: 'iframe',
    url: 'https://diff.example.com/',
    sandbox: ['allow-scripts'],
  });
  await post('/api/registry/install', { manifest: MANIFEST });
  // Layout after the EP exists (both stacks validate item refs against known entry points).
  await put('/api/navigation/layout', {
    pinnedSectionEnabled: true,
    sections: [
      { id: 'diff-sec-1', name: 'Diff Section', children: [{ id: 'diff-item-1', type: 'item', ref: 'diff-mod:diff-entry' }] },
    ],
  });
  await put('/api/navigation/shell-tree/settings', {
    groups: [{ groupKey: 'diff-group', name: 'Diff Group' }],
    items: [],
  });
  await put('/api/i18n/labels/en-GB', { entries: [{ key: 'diff.label', value: 'Hello Diff' }] });
  await post('/api/ai-hub/providers', { id: 'diff-provider', name: 'Diff Provider', baseURL: '' });
  const tokenRes = await post('/api/ai-hub/providers/diff-provider/tokens', {
    name: 'zeta-token',
    apiKey: 'diff-key-123',
  });
  out.tokenId = tokenRes.json?.id;
  const conv = await post('/api/ai-hub/conversations', { title: 'Diff Conversation' });
  out.conversationId = conv.json?.conversation?.id;
  if (out.conversationId) {
    await post(`/api/ai-hub/conversations/${out.conversationId}/messages`, { role: 'user', content: 'Hello diff' });
  }
  const channel = await post('/api/ai-hub/channels', {
    type: 'telegram',
    name: 'Diff Channel',
    credentials: { botToken: '123456:diff-token' },
  });
  // Node wraps the created channel (with registration extras); Java returns the public shape.
  out.channelId = channel.json?.channel?.id ?? channel.json?.id;
  return out;
}

// â”€â”€ Endpoint replay list (plan Â§P9.2 step 6; <convId>/<chId> = stack-local ids) â”€â”€

function replayEndpoints() {
  return [
    ['GET', '/healthz'],
    ['GET', '/api/config'],
    ['GET', '/api/settings'],
    ['GET', '/api/user-settings'],
    ['GET', '/api/user-settings/diff-scope'],
    ['GET', '/api/module-settings/diff-probe'],
    ['GET', '/api/module-settings/absent-key'],
    ['GET', '/api/navigation/layout'],
    ['GET', '/api/navigation/pinned-apps'],
    ['GET', '/api/navigation/user-settings'],
    ['GET', '/api/navigation/features'],
    ['GET', '/api/navigation/shell-tree'],
    ['GET', '/api/workspaces'],
    ['GET', '/api/workspaces/diff-ws'],
    ['GET', '/api/registry/modules'],
    ['GET', '/api/registry/entry-points'],
    ['GET', '/api/registry/entry-point-groups'],
    ['GET', '/api/registry/versions/diff-manifest-mod'],
    ['GET', '/api/registry/active-manifest/diff-manifest-mod'],
    // version-manifest with a MISSING version id (200 {"manifest":null} on both stacks); the
    // existing-version case is probed dynamically below (version ids are stack-local sequences).
    ['GET', '/api/registry/version-manifest/diff-manifest-mod/999999'],
    ['GET', '/api/i18n/config'],
    ['GET', '/api/i18n/labels/en'],
    ['GET', '/api/i18n/labels/EN-GB'],
    ['GET', '/api/ai-hub/providers'],
    ['GET', '/api/ai-hub/providers/diff-provider/models'],
    ['GET', '/api/ai-hub/conversations'],
    ['GET', '/api/ai-hub/conversations/<convId>/messages'],
    ['GET', '/api/ai-hub/channels'],
    ['GET', '/api/ai-hub/channels/<chId>'],
    ['GET', '/api/mfe/absent-module/index.js'],
    ['GET', '/api/mfe/diff-mod'],
    // Safe mutations with deterministic responses.
    ['POST', '/api/registry/entry-points/reorder', { ids: [] }],
    ['POST', '/api/registry/entry-point-groups/reorder', { keys: [] }],
    ['PUT', '/api/navigation/user-settings', { sidebar: { showPinned: null } }],
    ['DELETE', '/api/workspaces/absent-ws'],
    ['DELETE', '/api/registry/modules/portal-dashboard'],
    ['POST', '/api/ai-hub/webhooks/telegram/<chId>', { update_id: 1 }],
    ['POST', '/api/ai-hub/chat', { providerId: 'diff-provider', model: 'm', messages: [{ role: 'user', content: 'hi' }] }],
  ];
}

const ROLE_PROBES = [
  ['GET', '/api/navigation/shell-tree'],
  ['PUT', '/api/settings', { homeApp: 'x' }],
  ['PUT', '/api/i18n/labels/en', { entries: [{ key: 'a.b', value: 'c' }] }],
];

// Documented accepted divergences (plan Â§6) â€” excluded from the pass/fail totals.
const ACCEPTED = new Set(['GET /api/mfe/diff-mod']);

// â”€â”€ Runner â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

const results = [];
async function probe(label, fn) {
  try {
    const { node, java, verdict } = await fn();
    results.push({ label, nodeStatus: node?.status, javaStatus: java?.status, verdict });
  } catch (err) {
    results.push({ label, nodeStatus: 'ERR', javaStatus: 'ERR', verdict: `error: ${err.message}` });
  }
}

const resolvePath = (path, ids) => path.replace('<convId>', ids.conversationId ?? 'missing-conv').replace('<chId>', ids.channelId ?? 'missing-ch');
const displayPath = (path) => path.replace('<convId>', ':convId').replace('<chId>', ':chId');

console.log('[harness] logging in to both stacksâ€¦');
const nodeAdmin = await nodeLogin(NODE_BASE, USERS.admin);
const javaAdmin = await javaLogin(JAVA_BASE, USERS.admin);
const nodeLimited = await nodeLogin(NODE_BASE, USERS.limited);
const javaLimited = await javaLogin(JAVA_BASE, USERS.limited);
console.log('[harness] auth ok');

console.log('[harness] seedingâ€¦');
const nodeIds = await seedStack(NODE_BASE, nodeAdmin);
const javaIds = await seedStack(JAVA_BASE, javaAdmin);
console.log('[harness] seeded', { nodeIds, javaIds });

for (const [method, path, body] of replayEndpoints()) {
  await probe(`${method} ${displayPath(path)}`, async () => {
    const node = await call(NODE_BASE, nodeAdmin, method, resolvePath(path, nodeIds), body);
    const java = await call(JAVA_BASE, javaAdmin, method, resolvePath(path, javaIds), body);
    return { node, java, verdict: compare(node, java, path, nodeIds, javaIds) };
  });
}

// version-manifest with a version id that EXISTS on each stack (ids are stack-local sequences)
await probe('GET /api/registry/version-manifest/diff-manifest-mod/:versionId', async () => {
  const nv = (await call(NODE_BASE, nodeAdmin, 'GET', '/api/registry/versions/diff-manifest-mod')).json?.versions?.at(-1)?.id;
  const jv = (await call(JAVA_BASE, javaAdmin, 'GET', '/api/registry/versions/diff-manifest-mod')).json?.versions?.at(-1)?.id;
  const node = await call(NODE_BASE, nodeAdmin, 'GET', `/api/registry/version-manifest/diff-manifest-mod/${nv}`);
  const java = await call(JAVA_BASE, javaAdmin, 'GET', `/api/registry/version-manifest/diff-manifest-mod/${jv}`);
  return { node, java, verdict: compare(node, java, '/none', {}) };
});

for (const [method, path, body] of ROLE_PROBES) {
  await probe(`${method} ${path} (devuser â†’ expect 403)`, async () => {
    const node = await call(NODE_BASE, nodeLimited, method, path, body);
    const java = await call(JAVA_BASE, javaLimited, method, path, body);
    return { node, java, verdict: compare(node, java) };
  });
}

// â”€â”€ Report â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

const verdictIcon = { identical: 'OK', 'byte-diff': 'BYTE', 'semantic-diff': 'SEMANTIC' };
for (const r of results) {
  if (r.verdict !== 'identical' && ACCEPTED.has(r.label)) r.verdict = 'accepted';
}
const rows = results.map(
  (r) => `| ${r.label} | ${r.nodeStatus} | ${r.javaStatus} | ${verdictIcon[r.verdict] ?? r.verdict} |`,
);
const totals = {
  identical: results.filter((r) => r.verdict === 'identical').length,
  byte: results.filter((r) => r.verdict === 'byte-diff').length,
  diff: results.filter((r) => r.verdict === 'semantic-diff' || r.verdict.startsWith('error')).length,
  accepted: results.filter((r) => r.verdict === 'accepted').length,
};
const report = [
  '# Contract-diff report',
  '',
  `Generated: ${new Date().toISOString()}`,
  `Node stack: ${NODE_BASE} Â· Java stack: ${JAVA_BASE}`,
  '',
  '| Endpoint | Node | Java | Verdict |',
  '|---|---|---|---|',
  ...rows,
  '',
  `Totals: ${totals.identical} identical / ${totals.byte} byte-diff (volatile fields only) / ${totals.diff} semantic-diff/error / ${totals.accepted} accepted divergence (plan section 6)`,
  '',
].join('\n');
writeFileSync(REPORT_PATH, report);
console.log(report);
console.log(`[harness] report written to ${REPORT_PATH}`);
