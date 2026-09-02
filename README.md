# crosshubber — Java Portal Clone

Java (Spring Boot + Maven) clone of the portal component from `C:/playground/projects/genportal`,
rebranded as **Crosshubber**.

Structure:

```
portal/
  ui/         Angular 22 shell (cloned from genportal/portal/ui, branded Crosshubber)
  server/     Spring Boot 3.4 + Java 21 backend (port of portal/src)
              base package com.crosshubber.portal, packaged by feature
config-management/
  tenants-config/   minimal copy of backoffice-tools/tenants-config (baseline + dev)
```

Backend feature packages: `portal`, `auth`, `modules`, `modulesettings`, `registry`,
`entrypoints`, `workspaces`, `navigation`, `settings`, `usersettings`, `i18n`, `aihub`,
`proxy` — each owns its entities and repositories. Cross-cutting: `config`, `security`,
`bootstrap`, `common`.

## Prerequisites

- Java 21, Maven 3.9, Node 22 + npm 11, Docker + Compose

## Quick start (dev tenant)

```bash
# Run the full stack (UI + server build inside Docker; Postgres 16 + Keycloak 26.7 + NATS + Portal)
docker compose up --build -d
# -> portal http://localhost:28084, keycloak http://localhost:28080, pg 25432, nats 32252

# Local build (optional)
cd portal/ui && npm ci && npm run build
cd ../server && mvn -DskipTests package
```

Login: `dev/dev` (admin, all `portal-*` roles) or `devuser/dev`.

Env (`TENANT_SLUG`, `PUBLIC_BASE_URL`, `OIDC_ISSUER`, `PGHOST` etc.) mirrors
`C:/playground/projects/genportal/portal/src/config.ts` — see `portal/server/src/main/resources/application.yml`.

## Seeding

- `tenants-config/_default/init.sql` + `seed.sql` are mounted into `/docker-entrypoint-initdb.d`
  (run once on a fresh pgdata volume; they are intentionally minimal).
- Portal DDL is owned by **Flyway** (`V1..V11` in `portal/server/src/main/resources/db/migration`).
- Data seeding is owned by the **Reconciler** at boot (builtin modules, entry points,
  AI Hub provider catalog, instance settings, i18n languages/labels, tenant_meta).
  It is fail-fast: a seeding error aborts boot.

## Style

- Google Java Style (2 spaces, 100 col) — `mvn spotless:check` + `mvn checkstyle:check`
- Logging prefixes `[portal]`, `[auth]`, `[reconcile]` etc.

Skills: `.opencode/skills/spring-boot` + `google-java-style` (also in `~/.config/opencode/skills`).
