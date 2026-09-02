// One-off: raw byte-level comparison of remaining byte-diffs + filtered semantic diffs.
const NODE_BASE = 'http://localhost:18084';
const JAVA_BASE = 'http://localhost:28084';
const admin = { username: 'dev', password: 'dev' };

class CookieJar {
  constructor() { this.cookies = new Map(); }
  absorb(res) {
    for (const sc of res.headers.getSetCookie?.() ?? []) {
      const pair = sc.split(';')[0];
      const idx = pair.indexOf('=');
      if (idx > 0) this.cookies.set(pair.slice(0, idx).trim(), pair.slice(idx + 1).trim());
    }
  }
  header() { return [...this.cookies.entries()].map(([k, v]) => `${k}=${v}`).join('; '); }
}
async function raw(jar, url, init = {}) {
  const headers = { ...(init.headers ?? {}) };
  if (jar.cookies.size) headers.cookie = jar.header();
  const res = await fetch(url, { ...init, headers, redirect: 'manual' });
  jar.absorb(res);
  return { res, text: await res.text() };
}
const locationOf = (res, base) => {
  const loc = res.headers.get('location');
  return loc ? new URL(loc, res.url || base).toString() : null;
};
async function nodeLogin(base, { username, password }) {
  const jar = new CookieJar();
  const { text } = await raw(jar, `${base}/api/login/start`);
  const { flowId } = JSON.parse(text);
  await raw(jar, `${base}/api/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ flowId, username, password }),
  });
  return jar.cookies.get('portalSession');
}
function parseKcForm(html, baseUrl) {
  const form = html.match(/<form[^>]*>/)?.[0] ?? '';
  const action = (form.match(/action="([^"]+)"/) ?? [])[1];
  const fields = {};
  for (const input of html.matchAll(/<input\b[^>]*>/g)) {
    const name = input[0].match(/name="([^"]+)"/)?.[1];
    const value = input[0].match(/value="([^"]*)"/)?.[1] ?? '';
    if (name && (input[0].match(/type="([^"]+)"/)?.[1] ?? 'text') === 'hidden') fields[name] = value;
  }
  return { action: new URL(action, baseUrl).toString(), fields };
}
async function javaLogin(base, { username, password }) {
  const jar = new CookieJar();
  let step = await raw(jar, `${base}/api/login/start`);
  let url = locationOf(step.res, base);
  let submitted = false;
  for (let hop = 0; hop < 12 && url; hop++) {
    step = await raw(jar, url);
    let next = locationOf(step.res, base);
    if (next) { url = next; continue; }
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
  return jar.cookies.get('portalSession');
}
async function call(base, token, method, path, body) {
  const headers = { cookie: `portalSession=${token}` };
  if (body !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(base + path, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch {}
  return { status: res.status, json, text };
}

const n = await nodeLogin(NODE_BASE, admin);
const j = await javaLogin(JAVA_BASE, admin);

const paths = [
  '/api/workspaces',
  '/api/registry/modules',
  '/api/registry/entry-points',
  '/api/registry/versions/diff-manifest-mod',
  '/api/ai-hub/providers',
  '/api/ai-hub/channels',
  '/api/ai-hub/conversations',
  '/api/i18n/config',
  '/api/config',
];
for (const path of paths) {
  const a = await call(NODE_BASE, n, 'GET', path);
  const b = await call(JAVA_BASE, j, 'GET', path);
  const pick = (obj, keys) => {
    for (const arrKey of Object.keys(obj ?? {})) {
      if (Array.isArray(obj[arrKey])) {
        const f = obj[arrKey].filter((it) => keys.some((k) => typeof it[k] === 'string' && it[k].toLowerCase().startsWith('diff')));
        return JSON.stringify(f);
      }
    }
    return JSON.stringify(obj);
  };
  console.log(`\n===== ${path} (${a.status}/${b.status}) =====`);
  if (a.text === b.text) { console.log('RAW IDENTICAL'); continue; }
  console.log('NODE:', pick(a.json, ['key', 'moduleKey', 'id', 'name', 'title']).slice(0, 1400));
  console.log('JAVA:', pick(b.json, ['key', 'moduleKey', 'id', 'name', 'title']).slice(0, 1400));
}
