# Config Management — Minimal (Deferred)

This folder is intentionally thin for the initial Java clone. Full Java port of
`backoffice-tools` (CLI + provision/deploy/plan) is deferred — see work-in-progress
plan. Only the essentials to boot a Docker tenant are kept.

## What's here

- `tenants-config/_default/` — baseline profile (never deployed) + `init.sql` / `seed.sql`
  (both minimal, real DDL is via Flyway in `portal/server`).
- `tenants-config/dev/` — persistent local dev tenant (`slug=dev`, no external modules).
- `tenants-config/demo|qa|acme/` — examples (copy of originals, `secrets.env` excluded).
- `tenants-config/*/realm.json` — Keycloak realm templates with `${VAR}` placeholders
  resolved inside Keycloak container via compose env.
- `tenants-config/*/secrets.env` — **GITIGNORED**, operator-owned. A `secrets.env.example`
  is committed if present; real file is scaffolded on first `generateDeployment`.

Original source: `C:/playground/projects/genportal/backoffice-tools/tenants-config/`

## How it connects to portal/server

At boot, `portal/server` reads:

```
TENANT_SLUG=dev
TENANT_CONFIG_DIR=/app/tenants  (mounted)
PGSCHEMA=dev  (defaults to TENANT_SLUG — see portal/src/config.ts:37)
```

- `bootstrap/TenantConfigLoader.java` loads `_default/tenant.json` + `dev/tenant.json`
  and deep-merges (raw JSON before defaults) — same semantics as
  `backoffice-tools/src/tenant-store.ts` / `portal/src/bootstrap/tenant-config.ts`.
- `Reconciler.java` then upserts builtin catalog + settings (non-destructive).
- Env `TENANT_CONFIG_DIR` can override path for testing.

## Deployments (generated)

`config-management/deployments/<slug>/` is **generated** (`pure function of inputs`) and
gitignored. Minimal `dev` deployment is pre-baked under `portal/server` docker flow
(see `portal/server/docker-compose.dev.yml` placeholder). To re-generate with original
Node tooling:

```bash
# from genportal/backoffice-tools
npm run tenants -- compose dev
```

Future Java `config-management/` will add `java -jar config-management.jar list|plan|up|...`
mirroring that CLI — tracked separately.

## Secrets

Do NOT commit `secrets.env`. Changing `POSTGRES_PASSWORD` after `pgdata` init has no effect
(the password lives inside the Docker volume). See `backoffice-tools/src/provision/inputs.ts`.
