-- Uninstall pruning: module_settings never got the ON DELETE CASCADE FK that its
-- per-module siblings received in V1, so modules deleted through the registry left
-- their settings behind. Purge those orphans; future uninstalls prune the row in
-- ModulesService.remove (service-level, deliberately not an FK: the SMTP seed runner
-- may insert settings before the reconciler creates the module row on a fresh
-- database, and an FK would fail that insert).
DELETE FROM module_settings ms
WHERE NOT EXISTS (SELECT 1 FROM registry_modules rm WHERE rm.key = ms.module_key);
