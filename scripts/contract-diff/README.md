# Contract-diff Harness

Byte-level API parity checker between the Node reference stack (`genportal`) and the Java portal.

## Usage

```bash
# Prerequisites: Node stack on :18084, Java stack on :28084 (both dev tenants up)
node scripts/contract-diff/harness.mjs [--report <path>]
```

- Logs in as `dev/dev` (admin) and `devuser/dev` (limited) on **both** stacks.
- Seeds identical data through each stack's own API, replays ~40 endpoints, normalizes
  volatile fields (UUIDs, timestamps, masked secrets), and classifies each endpoint as
  `identical | byte-diff | semantic-diff`.
- Default report output: `scripts/contract-diff/contract-diff-report.md`.
- Endpoints with intentional, documented differences live in the `ACCEPTED` set in `harness.mjs`;
  the rationale for each is in the repo README's "Accepted Divergences" table.

## When to run

After any change that could alter an HTTP response shape or status code. The committed
`contract-diff-report.md` is a snapshot of the last run; regenerate rather than hand-edit it.

## Historical note

The former one-off scripts (`probe-config.mjs`, `probe-diffs.mjs`, `regen-i18n-catalog.mjs`,
`sync-genportal-roles.mjs`) served their purpose during the P9.2 triage (2026-09-02) and were
removed. Their repair technique (CP1252 double-encoding fix) is documented in
`docs/archive/GAP_CLOSURE_IMPLEMENTATION_PLAN.md`.
