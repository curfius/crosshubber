# Sample Sender — message-center reference app (dev-only)

Reference implementation of the Crosshubber message-center **sender contract**
(`plan/archive/MESSAGE_CENTER_PLAN.md` §13). Plain Node 20 + `nats` (nats.js), zero framework, no
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

The manifest URL is fetched **by the portal container**, not by your browser — `http://localhost:28092`
resolves to the portal itself in there and fails with `code:"unreachable"` (the wizard shows a
reachability hint). Use `http://sample-sender:8092/.well-known/portal-module.json` or
`http://host.docker.internal:28092/.well-known/portal-module.json`.

## The UI

The composer speaks plain language — no envelope JSON or messaging terms needed:

- **Examples**: one-click example messages (approval, hours report, RSVP, claim pool,
  team announcement, saved form, broken-message demo).
- **Message**: choose Announcement / Message / Task; title + body.
- **Who should see it?**: Everyone / Teams / Roles / People, with chip inputs
  (no audience JSON to know).
- **Task settings**: Approval vs Information, one-person vs everyone-responds, claim
  toggle, deadline, a question builder (label/type/required/min-max/options) or a
  reference to a saved form from Task Template Studio.
- **Activity**: friendly feed — “You sent …”, “alice approved your task — …”, “The
  portal rejected a message” — with a **technical details** expander per row (raw
  subject + payload). Filter chips: All / Sent / Responses / Errors.
- **Advanced** (top-right, for developers): the raw levers — module key, transport
  (NATS / HTTP publish endpoint), audience/task/sections JSON, and a **raw envelope**
  field that is sent verbatim (used by the broken-message demo). While Advanced is
  open, these JSON fields replace the form settings when sending.

## Manual smoke checklist

1. Open the composer (iframe module in the portal, or the raw `:28092` UI).
2. Example **Approve an expense** → Send (NATS) → Message Center shows the task →
   approve in the inbox → **Activity** shows “<user> approved your task — “Expense approval””.
3. Example **Daily hours report** (“Everyone responds”) → two users respond →
   task flips done on the final one; each response line shows the submitted data.
4. Example **Laptop request (first come)** → user A **Take task**, Save draft, Release;
   user B **Take task**, **Adopt** the draft, edit, Submit → Activity shows
   “submitted a response” with the form data.
5. Example **Announcement to a team** → create group `back-office` first (user settings);
   only members receive it.
6. Example **Use a saved form** → publish a template in Task Template Studio first
   (e.g. `expense-approval` v1); the composer sends a `task.template{key,version}`
   reference; the portal resolves at publish and stores the expanded form.
7. Example **Broken message (demo)** → Advanced opens with the invalid envelope filled
   in → Send → friendly “Could not send” or, via NATS, Activity shows “The portal
   rejected a message” (also visible in Message Center Admin → DLQ).
8. Advanced toggle → Transport = **HTTP publish endpoint** → Send any example →
   same behavior through the authenticated endpoint.

## Trust level / limits

- No authentication on its own endpoints (dev insecure-by-design; shares `SESSION_SECRET` with
  the dogfood modules).
- It sits on the tenant network and can publish anything to the stream — same trust level as
  the dogfood modules; documented risk, observable via `portal.dlq.msgcenter` + the registry.
- The HTTP transport computes the publish secret locally:
  `base64url(HMAC-SHA256(SESSION_SECRET, "sample-sender"))` — same algorithm as the portal's
  `PublishAuthenticator.expectedSecret`.
