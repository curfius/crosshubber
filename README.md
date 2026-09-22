# Crosshubber — Modular Enterprise Portal

Crosshubber is a **modular enterprise portal** that serves as an integration platform enabling seamless interoperability between distinct organizational modules. It provides a unified shell where independently developed modules register their applications, services, permissions, and events, while the portal handles all cross-cutting concerns and enables AI-driven user assistance.

## Core Concept

The portal acts as an **integration backbone** that:

- **Enables module interoperability** — Modules remain self-contained but declare their capabilities (applications, settings, agents, tools, skills, permissions, published/consumed events) through a standardized manifest. The portal discovers, validates, and surfaces these capabilities to users and other modules.
- **Provides unified navigation** — A single shell composes entry points from all installed modules into a coherent navigation structure (applications, settings, features, user settings) with role-based visibility.
- **Delivers cross-cutting functionality** — Authentication (OIDC/OAuth2 with Keycloak), notifications, messaging, theming, internationalization (i18n), centralized instance settings, and per-user preferences are handled once at the portal level.
- **Implements an event broker** — A NATS-backed event bus enables asynchronous, decoupled integration between modules via published/consumed event declarations in manifests.
- **Embeds an AI super-agent** — The AI Hub is a first-class citizen: it consumes module-provided data, documentation, tools, and skills to guide users through the portal, execute tasks on their behalf, and enable workflow automation across module boundaries.

## Module Integration Model

Modules integrate with the portal by providing a **manifest** (`portal-module.json`) that declares:

| Manifest Section | Purpose |
|---|---|
| `content.applications` | UI entry points (iframe, embedded, MFE, link) surfaced in the main navigation |
| `content.features` | Feature toggles exposed to users |
| `content.adminSettings` / `userSettings` | Configuration forms injected into the portal's settings UI |
| `security.roles` | Module-specific permission keys (e.g., `module-x:admin`) |
| `capabilities` | Scoped capabilities the module provides to other modules |
| `events.published` / `events.consumed` | Event contracts for async integration via the broker |
| `agentContributions.tools` / `skills` | AI functions and skills the module contributes to the super-agent |
| `security.roles` | RBAC keys the module requires/defines |

Modules are installed, versioned, and managed through the **Registry** — a built-in module lifecycle manager supporting draft creation, validation, installation, rollback, and activation.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Angular 22 Shell (UI)                     │
│  Navigation │ Workspaces │ Pinned Apps │ Notifications │ Theme  │
└──────────────────────────────┬──────────────────────────────────┘
                               │ REST + SSE
┌──────────────────────────────┴──────────────────────────────────┐
│                Spring Boot 4.1 Server (Java 21)                 │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐            │
│  │ Registry │ │  Nav     │ │ Workspaces│ │  AI Hub  │  ...     │
│  │ Modules  │ │ EntryPts │ │ Workspaces│ │ (LLM,    │            │
│  │ Manifest │ │ Groups   │ │           │ │  Agents) │            │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘            │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐            │
│  │ Auth     │ │ Settings │ │  i18n    │ │  Event   │            │
│  │ (OIDC)   │ │ Instance │ │  (labels)│ │  Broker  │            │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘            │
└──────────────────────────────┬──────────────────────────────────┘
                               │
         ┌─────────────────────┼─────────────────────┐
         ▼                     ▼                     ▼
    PostgreSQL            Keycloak              NATS
   (multi-tenant)        (OIDC/OAuth2)         (Event Bus)
```

### Technology Stack

| Layer | Technology |
|---|---|
| **Backend** | Spring Boot 4.1.1, Java 21, Maven |
| **Frontend** | Angular 22, TypeScript, RxJS |
| **Database** | PostgreSQL 16 (multi-tenant via per-tenant schemas) |
| **Identity** | Keycloak 26 (OIDC Authorization Code + PKCE) |
| **Messaging** | NATS (event broker) |
| **Build** | Multi-stage Docker (UI + Server) |
| **Style** | Google Java Style (Spotless + Checkstyle) |

### Feature Packages (Server)

Each feature owns its entities, repositories, and REST endpoints — packaged by feature under `com.crosshubber.portal.modules.*`:

- `auth` — OIDC login, session management, transparent token refresh
- `modules` / `registry` — Module registry, manifest validation, install/upgrade/rollback
- `entrypoints` / `entrypointgroups` — Navigation entry points and groups
- `navigation` — Shell tree, layout, pinned apps, user settings, features
- `workspaces` — User workspaces with layout/groups persistence
- `settings` / `usersettings` — Instance and per-user settings
- `i18n` — Languages, labels, content versioning
- `aihub` — Providers, tokens, models, conversations, channels, agent tools/skills
- `proxy` — MFE/iframe proxy with SSRF guard
- `bootstrap` — Tenant config loader, boot-time reconciler

Cross-cutting: `config`, `security`, `common`.

## Multi-Tenancy

Each tenant runs in its own **PostgreSQL schema** (`PGSCHEMA`). The reconciler runs at boot to:

1. Load tenant config (`_default/tenant.json` + `tenant-slug/tenant.json`)
2. Upsert builtin modules & entry points from the embedded catalog
3. Install/activate external modules declared in `modules.external[]`
4. Seed AI Hub providers, instance settings, i18n languages/labels
5. Record tenant metadata (digest, revision, applied-at)

## AI Hub — The Super Agent

The AI Hub is the portal's **cognitive layer**. It aggregates:

- **Providers & Models** — Configurable LLM providers (Anthropic, OpenAI, Google, Ollama, etc.) with per-provider tokens and model catalogs
- **Tools** — Module-contributed functions (e.g., "create Jira ticket", "query Salesforce") with JSON Schema input/output
- **Skills** — Higher-level workflows composed from tools
- **Channels** — Telegram/WhatsApp webhooks for chat-based interaction
- **Conversations** — Persisted chat history with provider/model metadata

The agent can **guide users through the portal**, **execute cross-module tasks**, and **automate workflows** by chaining tools across module boundaries.

## Quick Start (Dev Tenant)

```bash
# Full stack (UI + Server + Postgres + Keycloak + NATS)
docker compose up --build -d
# Portal: http://localhost:28084
# Keycloak: http://localhost:28080
# Postgres: 25432
# NATS: 32252

# Local development
cd portal/ui && npm ci && npm run build
cd ../server && mvn -DskipTests package
```

**Login:** `dev/dev` (admin, all `portal-*` roles) or `devuser/dev`.

All configuration is environment-overridable — see `portal/server/src/main/resources/application.yml`.

## Seeding & Reconciliation

- **Flyway** (`V1..V24`) owns DDL — runs automatically on boot.
- **Reconciler** (fail-fast) seeds at boot:
  - Builtin modules & entry points from embedded catalog
  - External modules from `modules.external[]` (retry + manifest fetch + validation)
  - AI Hub provider catalog
  - Instance settings, i18n languages/labels
  - Tenant metadata (`tenant_meta` table)

## API Ownership

Crosshubber's API is **self-owned**. The portal started as a port of an earlier Node
implementation, but that reference has been decommissioned: good ideas were imported and
kept, the rest was dropped. Response shapes evolve when Crosshubber needs them to —
changes are covered by `mvn verify` and `npm test`, not by a parity harness.

Historical porting plans live under `docs/archive/`.

## Design notes

Intentional API/storage decisions (several date back to the original port):

| # | Decision | Rationale |
|---|---|---|
| 1 | Proxy upstream fetch failure → 502 `{"error":"upstream fetch failed"}` | More accurate status than a generic 500 |
| 2 | Scalar JSON body on PUT/POST → 400 `{"error":"invalid request body"}` | Correct status |
| 3 | Tenant config digest = `Object.hashCode()` hex | Only recorded in `tenant_meta` |
| 4 | Models listing picks the first **enabled** token | More sensible than any-state |
| 5 | `roles` stored comma-joined TEXT (V11) | Role keys are kebab-case (validated) — no commas possible; API output stays an array |
| 6 | `GET /api/mfe/foo` (no trailing path) → 400 `{"error":"bad path"}` | JSON is more consistent than HTML |
| 7 | Navigation `hidden` flags (V23) | Per-row hidden/visible toggle for the navigation editors; omitted when `false` so payloads stay minimal |

## Development

```bash
# Server
cd portal/server
mvn spotless:apply      # format
mvn verify              # build + checkstyle + tests

# UI
cd portal/ui
npm ci
npm run build           # or ng serve for dev
```

**Code style:** Google Java Style (2 spaces, 100 col) — enforced via `mvn spotless:check` + `mvn checkstyle:check`.

**Skills:** `.opencode/skills/spring-boot` + `google-java-style` (also in `~/.config/opencode/skills`).