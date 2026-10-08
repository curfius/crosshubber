# Sample Sender — message-center reference app (dev-only)

Reference implementation of the Crosshubber message-center **sender contract**
(`plan/MESSAGE_CENTER_PLAN.md` §13). Plain Node 20 + `nats` (nats.js), zero framework, no
database — in-memory state, lost on restart. **Never install this on a real tenant.**

## What it proves

1. **Publish paths**: NATS-direct (`portal.msg./portal.task.` subjects on `PORTAL_MESSAGES`)
   and the portal HTTP endpoint (`POST /api/msgcenter/publish` with the HMAC secret header).
2. **Task lifecycle end-to-end**: presets for approval, `each` collect, claim-pool + draft
   takeover, group-addressed broadcast, template-referenced task, malformed → DLQ.
3. **Response loop**: subscribes `portal.taskresponse.sample-sender.>` and renders the
   completion events (responder, outcome, data, note) — the audit loop back to the sender.

## Run (compose)

```bash
docker compose up -d --build sample-sender portal
# UI:            http://localhost:28092
# In the portal: install the module via Module Registry → quick install
#   manifest URL: http://sample-sender:8092/.well-known/portal-module.json
#   (SSRF_ALLOW_PRIVATE=true is already set in the dev compose)
```

## Manual smoke checklist

1. Open the composer (iframe module in the portal, or the raw `:28092` UI).
2. Preset **1 · expense approval** → Send (NATS) → Message Center shows the task →
   approve in the inbox → the **Responses** timeline shows `outcome=approve`.
3. Preset **2 · hours report** (`each`) → two users respond → task flips done on the final one.
4. Preset **4 · claim pool** → user A **Take task**, Save draft, Release; user B **Take task**,
   **Adopt** the draft, edit, Submit → responses timeline shows `submit` with the data.
5. Preset **5 · group broadcast** → create group `back-office` first (user settings); only
   members receive it.
6. Preset **6 · template ref** → publish a template in Task Template Studio first
   (e.g. `expense-approval` v1); the composer sends `task.template{key,version}`; the portal
   resolves at publish and stores the expanded form.
7. Preset **× · malformed** → `portal.dlq.msgcenter` gets the copy (visible in Message Center
   Admin → DLQ).
8. Transport toggle **HTTP** on any preset → same behavior through the authenticated endpoint.

## Trust level / limits

- No authentication on its own endpoints (dev insecure-by-design; shares `SESSION_SECRET` with
  the dogfood modules).
- It sits on the tenant network and can publish anything to the stream — same trust level as
  the dogfood modules; documented risk, observable via `portal.dlq.msgcenter` + the registry.
- The HTTP transport computes the publish secret locally:
  `base64url(HMAC-SHA256(SESSION_SECRET, "sample-sender"))` — same algorithm as the portal's
  `PublishAuthenticator.expectedSecret`.
