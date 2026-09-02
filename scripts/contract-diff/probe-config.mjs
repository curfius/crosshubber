// One-off: dump /api/config minus the noisy collections for diff triage.
const NODE_BASE = 'http://localhost:18084';
const JAVA_BASE = 'http://localhost:28084';
const admin = { username: 'dev', password: 'dev' };

class CJ {
  constructor() { this.c = new Map(); }
  a(r) {
    for (const sc of r.headers.getSetCookie?.() ?? []) {
      const p = sc.split(';')[0];
      const i = p.indexOf('=');
      if (i > 0) this.c.set(p.slice(0, i).trim(), p.slice(i + 1).trim());
    }
  }
  h() { return [...this.c.entries()].map(([k, v]) => `${k}=${v}`).join('; '); }
}
const raw = async (j, u, i = {}) => {
  const h = { ...(i.headers ?? {}) };
  if (j.c.size) h.cookie = j.h();
  const r = await fetch(u, { ...i, headers: h, redirect: 'manual' });
  j.a(r);
  return { res: r, text: await r.text() };
};
const loc = (r, b) => {
  const l = r.headers.get('location');
  return l ? new URL(l, r.url || b).toString() : null;
};
async function nl(base, u2) {
  const j = new CJ();
  const s = await raw(j, `${base}/api/login/start`);
  const { flowId } = JSON.parse(s.text);
  await raw(j, `${base}/api/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ flowId, username: u2.username, password: u2.password }),
  });
  return j.c.get('portalSession');
}
function kf(html, baseUrl) {
  const form = html.match(/<form[^>]*>/)?.[0] ?? '';
  const action = form.match(/action="([^"]+)"/)?.[1];
  const fs = {};
  for (const input of html.matchAll(/<input\b[^>]*>/g)) {
    const n = input[0].match(/name="([^"]+)"/)?.[1];
    const v = input[0].match(/value="([^"]*)"/)?.[1] ?? '';
    if (n && (input[0].match(/type="([^"]+)"/)?.[1] ?? 'text') === 'hidden') fs[n] = v;
  }
  return { action: new URL(action, baseUrl).toString(), fields: fs };
}
async function jl(base, u2) {
  const j = new CJ();
  let st = await raw(j, `${base}/api/login/start`);
  let url = loc(st.res, base);
  let s = false;
  for (let x = 0; x < 12 && url; x++) {
    st = await raw(j, url);
    let n = loc(st.res, base);
    if (n) { url = n; continue; }
    if (st.res.status === 200 && !s) {
      if (!st.text.includes('<form')) break;
      const { action, fields } = kf(st.text, url);
      fields.username = u2.username;
      fields.password = u2.password;
      st = await raw(j, action, {
        method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams(fields).toString(),
      });
      s = true;
      n = loc(st.res, base);
    }
    url = n;
  }
  return j.c.get('portalSession');
}
const call = async (b, t, p) => (await fetch(b + p, { headers: { cookie: `portalSession=${t}` } })).json();

const nt = await nl(NODE_BASE, admin);
const jt = await jl(JAVA_BASE, admin);
const a = await call(NODE_BASE, nt, '/api/config');
const b = await call(JAVA_BASE, jt, '/api/config');
delete a.entryPoints;
delete b.entryPoints;
delete a.groups;
delete b.groups;
console.log('NODE:', JSON.stringify(a, null, 1));
console.log('JAVA:', JSON.stringify(b, null, 1));
