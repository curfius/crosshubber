-- Section colors: shell-nav sections carry an optional display color (grouping
-- accents on the navigation page / editor avatars). Null keeps the fallback.
ALTER TABLE navigation_groups ADD COLUMN color TEXT NULL;
