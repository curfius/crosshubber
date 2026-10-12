---
status: archived
domain: ux
summary: "Sample Sender UX overhaul — plain-language Message Composer with guided forms, activity feed; committed e05babc"
---

# Sample Sender UX Overhaul — plain-language composer

## Goal

Make `modules/sample-sender/` usable in demos by people who don't know the
message/task framework: replace raw JSON editors and framework jargon with
guided forms and plain language, while keeping full raw capability for
developers behind an **Advanced** toggle. Decisions confirmed with user:

- Dev-only tool, cleaner UX (not user-facing, no i18n)
- Stay zero-build vanilla JS (no Angular port, no portal design-system dep)
- Audience pickers = friendly radios + free-text chips (no portal API calls)
- Raw JSON retained behind Advanced toggle

**No backend changes required** — this is a pure frontend rework of the
static assets in `modules/sample-sender/public/`. The envelope contract
published by `server.mjs` stays byte-identical; only how the UI collects
input and renders output changes.

## Terminology mapping (UI speak → framework concept)

| Friendly UI | Hides |
|---|---|
| Announcement / Message / Task (segmented control) | `type: notification \| message \| task` |
| "Who should see it?" Everyone / Teams / Roles / People | `audience.{allUsers,groups,roles,users}` |
| "What do you need?" Approval / Information | `task.kind: approval \| collect` |
| "Who responds?" One person is enough / Everyone responds | `task.completion: any \| each` |
| "Let people claim it" toggle | `task.claim.{enabled,mode:'single'}` |
| Deadline picker (datetime-local) | `task.expiresAt` ISO-8601 |
| Question builder rows | `task.fields[]` mini JSON-Schema |
| "Use a saved form" (template key + version) | `task.template.{key,version}` |
| Auto-generated response name (Advanced-only override) | `task.completionEvent` (required for collect) |
| "Send" button label adapts: Send announcement / Send message / Send task | — |
| Activity feed rows with "details" expander | raw subjects + payload JSON |

Module key, transport (NATS/HTTP), and Sections JSON move to Advanced only.

## File restructure

Split the single `public/index.html` (383 lines, inline CSS + JS) into:

```
modules/sample-sender/public/
  index.html        page structure (cards, form skeleton) — no inline JS
  app.css           all styles (extracted + extended from current inline CSS)
  app.js            all logic: state model, presets, form↔envelope mapping, SSE log
```

Still zero dependencies; `server.mjs` static-file serving already handles this
(no change needed — it serves `public/` generically, server.mjs:221–230).
Keep the existing dark visual style; improve layout for the new controls.

## Left card — Composer (→ "Compose")

1. **Examples** row (renamed from "Presets"): the same 7 presets with friendly
   names, same payloads (index.html:187–307 today):
   - Approve an expense · Daily hours report · Off-site RSVP ·
     Laptop request (first-come) · Announcement to a team ·
     Use a saved form · ⚠ Broken message (demo)
   - Clicking fills the form controls (and the Advanced JSON editors — one
     preset source of truth, both views derive from it).
2. **"What do you want to do?"** — segmented control replacing the Type select:
   - **Announcement** — "a one-way notice" (`notification`)
   - **Message** — "start a conversation" (`message`)
   - **Task** — "ask someone to do something" (`task`) → reveals task section
3. **Title** input + **Message** textarea (unchanged, labels simplified).
4. **"Who should see it?"** — radio row: Everyone / Teams / Roles / People.
   Selection swaps a chip input (Enter/Comma to add, × to remove) with
   placeholder examples (`back-office`, `admin`, `jane@contoso.com`).
   Builds `audience` JSON behind the scenes.
5. **Task section** (visible when Task):
   - *What do you need?* → Approval (Approve/Deny buttons) | Information
     (a form) → `kind`
   - *Who responds?* → "One person is enough" | "Everyone responds"
     → `completion` (note: claim requires `any` per EnvelopeValidator —
     when "Let people claim it" is switched on, force/disable to "One person")
   - *Let people claim it* toggle → `claim:{enabled,mode:'single'}`
   - *Deadline* datetime-local, optional → `expiresAt`
   - *Questions* builder (Information only): rows of {label, type
     (text/number/yes-no/choice), required, min/max, choices-for-enum},
     add/remove buttons → `task.fields[]`. Labels become `label:{en}` +
     auto `name` slug.
   - *Or use a saved form* alternative: key + version inputs → `task.template`
     (mutually exclusive with the question builder, matching the
     template/fields XOR rule).
   - `completionEvent` auto-generated from a title slug (`expense-approval.approved`
     style default `task.submitted`); shown/editable only in Advanced.
6. **Advanced toggle** (switch at card top-right). When on, reveals:
   - Module key input (default `sample-sender`), Transport select
     (NATS direct / HTTP publish endpoint), and the three JSON textareas
     (Audience / Task / Sections) exactly as today.
   - JSON editors become the source of truth for Send when open; forms when
     closed. The malformed preset sets a raw override that bypasses forms.
7. **Send** button + status line: friendly success/error text
   ("Sent ✓ over NATS" / "Could not send: portal rejected the message").
   The raw subject/status shown today moves into the activity feed details.

Envelope assembly (`buildEnvelope()`) produces the exact same v1 shape as
index.html:324–351 today.

## Right card — Activity (→ renamed from "Send log + responses")

Friendly rows instead of raw `HH:MM:SS [kind] subject <json>` lines, with a
color/icon per kind:

- `sent` → "You sent ‘Expense approval’ — Task · everyone"
- `received` (taskresponse) → "Alice approved your request" / "bob submitted
  hours: 6, comment: …" (parse `responder`, `outcome`, `data`, `note` from
  the payload already delivered via SSE)
- `dlq` → "⚠ The portal rejected a message (invalid) — DLQ demo"
- `rejected` / `failed` → "Could not send: <error>"
- Every row has a **details** expander showing the raw subject + payload JSON
  (keeps dev debugging power).
- Filter chips: All / Sent / Responses / Errors + existing Clear button.

Status pill in the header stays (connecting… / nats up/down), wording
simplified to "Live" / "Offline".

## Docs & verification

1. Update `modules/sample-sender/README.md`: describe the new UI, refresh the
   manual smoke checklist (walk each example, check activity translation +
   details expander, Advanced JSON round-trip, malformed demo → friendly
   error + DLQ row, iframe view in the portal at :28092).
2. Manual verification with the dev stack:
   `docker compose up -d --build sample-sender portal` → open
   http://localhost:28092 (direct) and via portal Module Registry iframe;
   send each preset; complete a task from the portal Message Center inbox
   and confirm the friendly response row appears.
3. No Java/TS touched → no `mvn verify` / `npm test` required.
   No automated tests exist for this vanilla module (none added — consistent
   with its current state; e2e msgcenter coverage is already tracked as
   separate backlog work in AGENTS.md).

## Out of scope

- i18n / `?lang=` handling (hardcoded English stays; dev-only tool)
- Fetching real users/roles/groups from the portal admin API
- Angular / design-system port
- Portal-side msg-center task-panel hardcoded English strings (found during
  research in `task-panel.component.html` — separate follow-up if wanted)
- Any change to `server.mjs` or the envelope contract

## Risks / gotchas

- **Form ↔ JSON sync**: presets and Advanced editors must not fight; use a
  single in-memory state model, with the active input mode (forms vs raw
  JSON) owning the Send payload.
- **Validator rules**: claim requires `completion:any`; collect requires
  `completionEvent`; template XORs inline fields — form logic must enforce
  these so users never hit 422s that only make sense in framework terms.
- **Preset "broken message"** must keep bypassing the forms (raw v:2 override)
  so the DLQ demo still works.
- Keep the module manifest (`public/.well-known/portal-module.json`)
  description in sync ("Message Composer" wording already fine).
