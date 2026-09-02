# Contract-diff report

Generated: 2026-09-02T14:50:22.254Z
Node stack: http://localhost:18084 Â· Java stack: http://localhost:28084

| Endpoint | Node | Java | Verdict |
|---|---|---|---|
| GET /healthz | 200 | 200 | OK |
| GET /api/config | 200 | 200 | BYTE |
| GET /api/settings | 200 | 200 | OK |
| GET /api/user-settings | 200 | 200 | OK |
| GET /api/user-settings/diff-scope | 200 | 200 | OK |
| GET /api/module-settings/diff-probe | 200 | 200 | OK |
| GET /api/module-settings/absent-key | 200 | 200 | OK |
| GET /api/navigation/layout | 200 | 200 | OK |
| GET /api/navigation/pinned-apps | 200 | 200 | OK |
| GET /api/navigation/user-settings | 200 | 200 | OK |
| GET /api/navigation/features | 200 | 200 | OK |
| GET /api/navigation/shell-tree | 400 | 400 | OK |
| GET /api/workspaces | 200 | 200 | BYTE |
| GET /api/workspaces/diff-ws | 200 | 200 | BYTE |
| GET /api/registry/modules | 200 | 200 | OK |
| GET /api/registry/entry-points | 200 | 200 | BYTE |
| GET /api/registry/entry-point-groups | 200 | 200 | OK |
| GET /api/registry/versions/diff-manifest-mod | 200 | 200 | BYTE |
| GET /api/registry/active-manifest/diff-manifest-mod | 200 | 200 | OK |
| GET /api/registry/version-manifest/diff-manifest-mod/999999 | 200 | 200 | OK |
| GET /api/i18n/config | 200 | 200 | BYTE |
| GET /api/i18n/labels/en | 404 | 404 | OK |
| GET /api/i18n/labels/EN-GB | 404 | 404 | OK |
| GET /api/ai-hub/providers | 200 | 200 | BYTE |
| GET /api/ai-hub/providers/diff-provider/models | 400 | 400 | OK |
| GET /api/ai-hub/conversations | 200 | 200 | BYTE |
| GET /api/ai-hub/conversations/:convId/messages | 200 | 200 | BYTE |
| GET /api/ai-hub/channels | 200 | 200 | BYTE |
| GET /api/ai-hub/channels/:chId | 200 | 200 | BYTE |
| GET /api/mfe/absent-module/index.js | 404 | 404 | OK |
| GET /api/mfe/diff-mod | 404 | 400 | accepted |
| POST /api/registry/entry-points/reorder | 200 | 200 | OK |
| POST /api/registry/entry-point-groups/reorder | 200 | 200 | OK |
| PUT /api/navigation/user-settings | 400 | 400 | OK |
| DELETE /api/workspaces/absent-ws | 200 | 200 | OK |
| DELETE /api/registry/modules/portal-dashboard | 409 | 409 | OK |
| POST /api/ai-hub/webhooks/telegram/:chId | 401 | 401 | OK |
| POST /api/ai-hub/chat | 400 | 400 | OK |
| GET /api/registry/version-manifest/diff-manifest-mod/:versionId | 200 | 200 | OK |
| GET /api/navigation/shell-tree (devuser â†’ expect 403) | 403 | 403 | OK |
| PUT /api/settings (devuser â†’ expect 403) | 403 | 403 | OK |
| PUT /api/i18n/labels/en (devuser â†’ expect 403) | 403 | 403 | OK |

Totals: 30 identical / 11 byte-diff (volatile fields only) / 0 semantic-diff/error / 1 accepted divergence (plan section 6)
