-- Step 13 (OPTIMIZATIONS #12 + schema hygiene):
-- 1) Optimistic-locking version columns on the four concurrently-written entities.
-- 2) Re-assert the canonical entry_point_groups category CHECK (4 values including
--    'user-settings'). History: V1 shipped a 3-value variant, V6 already widened it —
--    the Step 11 "3-value CHECK" audit note read V1 only and was incorrect (live DB
--    verified). Re-stating the invariant here normalizes any straggler environment
--    (e.g. restore-from-old-dump) and keeps the intended value-set in one place.
--    'admin-settings' stays excluded (admin settings are modules, not groups).
-- 3) Drop the legacy favorites table (pre-decommission Node stack; no Java entity,
--    no seed/init reference). chat_conversations/chat_messages no longer exist —
--    renamed to ai_hub_* by V8, messages dropped by V15.
ALTER TABLE instance_settings ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;
ALTER TABLE navigation_layout ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;
ALTER TABLE module_settings   ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;
ALTER TABLE workspaces        ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;

ALTER TABLE entry_point_groups DROP CONSTRAINT IF EXISTS entry_point_groups_category_check;
ALTER TABLE entry_point_groups
    ADD CONSTRAINT entry_point_groups_category_check
    CHECK (category IN ('applications', 'settings', 'features', 'user-settings'));

DROP TABLE IF EXISTS favorites;
