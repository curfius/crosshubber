# Config Management — Minimal (Deferred)

This folder is intentionally thin for the initial Java clone. Full Java port of
`backoffice-tools` (CLI + provision/deploy/plan) is deferred. Only the essentials to
boot a Docker tenant are kept.

## What's here

- `tenants-config/_default/` — baseline profile (never deployed) + `init.sql` / `seed.sql`
  (both minimal, real DDL is via Flyway in `portal/server`).
- `tenants-config/dev/` — persistent local dev tenant (`slug=dev`, blanks out the
  `_default` demo external modules).
- `tenants-config/dev/realm.json` — Keycloak realm template; `${VAR}` placeholders are
  resolved inside the Keycloak container via compose env.
- `tenants-config/dev/secrets.env` — **GITIGNORED**, operator-owned. A committed
  `secrets.env.example` documents the expected variables; the real file is created by
  the operator on first setup.

Additional tenants (`demo`, `qa`, ...) are created by copying `_default` + adding a
tenant-specific folder as needed — no examples are committed.

## How it connects to portal/server

At boot, `portal/server` reads:

```
TENANT_SLUG=dev
TENANT_CONFIG_DIR=/app/tenants  (mounted, see docker-compose.yml)
PGSCHEMA=dev  (defaults to TENANT_SLUG)
```

- `bootstrap/TenantConfigLoader.java` loads `_default/tenant.json` + `<slug>/tenant.json`
  and deep-merges (overlay wins; arrays are replaced, not merged).
- `Reconciler.java` then upserts builtin catalog + settings (non-destructive, fail-fast).
- Env `TENANT_CONFIG_DIR` can override the path for local `mvn` runs; compose always sets
  it to the mount point.

## Secrets

Do NOT commit `secrets.env`. Changing `POSTGRES_PASSWORD` after `pgdata` init has no
effect (the password lives inside the Docker volume) — wipe the volume to re-seed.
