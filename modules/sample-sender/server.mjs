/**
 * Sample sender — the reference implementation of the message-center sender contract
 * (MESSAGE_CENTER_PLAN §13). Dev-only demo: plain Node 20, zero framework, nats.js as the only
 * runtime dependency, in-memory state (lost on restart — consistent with dev insecure-by-design).
 *
 * Surface:
 *   GET  /healthz                 NATS connection status (compose healthcheck)
 *   GET  /                        demo UI (public/index.html)
 *   POST /send                    body = envelope; light publish-time checks; publishes over NATS
 *                                 or HTTP (transport toggled per request)
 *   GET  /events                  SSE of everything sent/received (incl. DLQ echoes)
 *   GET  /api/config              presets + manifest info for the demo UI
 *
 * Trust level: sits on the tenant network and can publish anything to the stream — same trust
 * as the dogfood modules; documented risk (plan §13). Must never be installed on a real tenant.
 */
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { connect } from 'nats';

const PORT = Number(process.env.PORT || 8092);
const NATS_URL = process.env.NATS_URL || 'nats://localhost:4222';
const PORTAL_URL = process.env.PORTAL_URL || 'http://portal:3000';
const SESSION_SECRET = process.env.SESSION_SECRET || 'change-me-session-secret-32-chars-min';
const CALLER_KEY = 'sample-sender';

// The HTTP transport authenticates exactly like the portal's publish endpoint:
// X-MsgCenter-Secret = base64url(HMAC-SHA256(sessionSecret, callerKey)).
function publishSecret() {
  return crypto.createHmac('sha256', SESSION_SECRET).update(CALLER_KEY).digest('base64url');
}

// ── In-memory event log (send log + responses timeline) ─────────────────

const MAX_EVENTS = 500;
const events = [];
const sseClients = new Set();

function logEvent(kind, payload) {
  const entry = { at: new Date().toISOString(), kind, ...payload };
  events.push(entry);
  if (events.length > MAX_EVENTS) events.shift();
  for (const res of sseClients) {
    res.write(`data: ${JSON.stringify(entry)}\n\n`);
  }
}

// ── Light publish-time checks (mirror the portal's cheap end; not the full validator) ──

const LIGHT_CHECKS = [
  ['v', (v) => v === 1, 'v must be 1'],
  [
    'type',
    (v) => ['notification', 'message', 'task'].includes(v),
    'type must be notification|message|task',
  ],
  [
    'moduleKey',
    (v) => typeof v === 'string' && /^[a-z0-9][a-z0-9-]{0,63}$/.test(v),
    'moduleKey must be kebab-case',
  ],
  [
    'id',
    (v) => typeof v === 'string' && /^[0-9a-f-]{36}$/i.test(v),
    'id must be a UUID',
  ],
  ['audience', (v) => typeof v === 'object' && v !== null, 'audience object required'],
  ['title', (v) => typeof v === 'object' && v !== null && typeof v.en === 'string', 'title.en required'],
  ['body', (v) => typeof v === 'object' && v !== null && typeof v.en === 'string', 'body.en required'],
];

function lightCheck(envelope) {
  const problems = [];
  for (const [key, ok, message] of LIGHT_CHECKS) {
    if (!ok(envelope[key])) problems.push(message);
  }
  return problems;
}

// ── NATS connection ─────────────────────────────────────────────────────

let nc = null;
let responseSub = null;

async function connectNats() {
  nc = await connect({ servers: NATS_URL, maxReconnectAttempts: -1, reconnectTimeWait: 2000 });
  // Response timeline: everything addressed back to this module. The response tree is
  // portal.taskresponse.* (outside portal.task.> so the portal's durable consumer never
  // re-ingests completion events).
  responseSub = nc.subscribe(`portal.taskresponse.${CALLER_KEY}.>`);
  (async () => {
    for await (const msg of responseSub) {
      logEvent('received', {
        subject: msg.subject,
        payload: safeJson(msg.data),
      });
    }
  })().catch(() => {});
  // DLQ echo: when a published envelope is rejected by the portal's full validation, the
  // ingest loop copies it to portal.dlq.msgcenter with the reason.
  const dlqSub = nc.subscribe('portal.dlq.msgcenter');
  (async () => {
    for await (const msg of dlqSub) {
      const payload = safeJson(msg.data);
      logEvent('dlq', {
        subject: payload.originalSubject ?? msg.subject,
        payload,
      });
    }
  })().catch(() => {});
  logEvent('system', { message: `connected to ${NATS_URL}` });
}

function safeJson(data) {
  try {
    return JSON.parse(new TextDecoder().decode(data));
  } catch {
    return new TextDecoder().decode(data);
  }
}

function natsHealthy() {
  return nc !== null && !nc.isClosed();
}

// ── Publishing ──────────────────────────────────────────────────────────

async function publishNats(envelope) {
  const subject =
    envelope.type === 'task'
      ? `portal.task.${envelope.moduleKey}.published`
      : `portal.msg.${envelope.moduleKey}.published`;
  await nc.publish(subject, JSON.stringify(envelope));
  await nc.flush();
  return { subject };
}

async function publishHttp(envelope) {
  const res = await fetch(`${PORTAL_URL}/api/msgcenter/publish`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-MsgCenter-Key': CALLER_KEY,
      'X-MsgCenter-Secret': publishSecret(),
    },
    body: JSON.stringify(envelope),
  });
  const body = await res.text();
  return { subject: `http:${res.status}`, status: res.status, body };
}

async function handleSend(body, transport) {
  let envelope;
  try {
    envelope = JSON.parse(body);
  } catch {
    return { status: 400, json: { error: 'invalid JSON' } };
  }
  const problems = lightCheck(envelope);
  if (problems.length > 0) {
    logEvent('rejected', { subject: 'local', payload: envelope, reason: problems.join('; ') });
    return { status: 422, json: { error: problems.join('; ') } };
  }
  try {
    const result =
      transport === 'http' ? await publishHttp(envelope) : await publishNats(envelope);
    logEvent('sent', { subject: result.subject, payload: envelope, transport });
    return { status: 200, json: { sent: true, ...result } };
  } catch (err) {
    logEvent('failed', { subject: 'local', payload: envelope, reason: String(err) });
    return { status: 502, json: { error: String(err) } };
  }
}

// ── HTTP server ─────────────────────────────────────────────────────────

const publicDir = path.join(path.dirname(fileURLToPath(import.meta.url)), 'public');

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  if (url.pathname === '/healthz') {
    res.writeHead(natsHealthy() ? 200 : 503, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: natsHealthy(), nats: natsHealthy() ? 'up' : 'down' }));
    return;
  }
  if (url.pathname === '/send' && req.method === 'POST') {
    let body = '';
    for await (const chunk of req) body += chunk;
    const transport = url.searchParams.get('transport') === 'http' ? 'http' : 'nats';
    const result = await handleSend(body, transport);
    res.writeHead(result.status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(result.json));
    return;
  }
  if (url.pathname === '/events') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    });
    res.write(`data: ${JSON.stringify({ kind: 'hello', at: new Date().toISOString() })}\n\n`);
    sseClients.add(res);
    req.on('close', () => sseClients.delete(res));
    return;
  }
  if (url.pathname === '/api/config') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ callerKey: CALLER_KEY, nats: natsHealthy(), portalUrl: PORTAL_URL }));
    return;
  }
  if (url.pathname === '/.well-known/portal-module.json') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(fs.readFileSync(path.join(publicDir, '.well-known', 'portal-module.json')));
    return;
  }
  // static files
  let file = url.pathname === '/' ? '/index.html' : url.pathname;
  const resolved = path.normalize(path.join(publicDir, file));
  if (!resolved.startsWith(publicDir) || !fs.existsSync(resolved) || !fs.statSync(resolved).isFile()) {
    res.writeHead(404);
    res.end('not found');
    return;
  }
  const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };
  res.writeHead(200, { 'Content-Type': types[path.extname(resolved)] ?? 'text/plain' });
  res.end(fs.readFileSync(resolved));
});

server.listen(PORT, () => {
  console.log(`[sample-sender] listening on ${PORT}`);
  connectNats().catch((err) => {
    console.error(`[sample-sender] NATS connect failed: ${err.message}`);
    logEvent('system', { message: `NATS connect failed: ${err.message}` });
    const retry = setInterval(() => {
      connectNats()
        .then(() => clearInterval(retry))
        .catch(() => {});
    }, 5000);
  });
});
