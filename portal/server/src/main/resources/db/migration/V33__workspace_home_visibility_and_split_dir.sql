-- Workarea Phase 1 (tabs & splits):
-- 1) Per-workspace Home-tab visibility — persisted by the primary-area home toggle.
--    DEFAULT TRUE matches the client convention (homeVisible omitted = shown) and the
--    entity's prePersist null-guard.
-- 2) Split dir value rename: 'row'/'col' (flex direction of the pane container) to
--    'vertical'/'horizontal' (divider orientation). The split buttons historically
--    passed crossed values ("Split vertically" created a row), so stored layouts are
--    rewritten to the new vocabulary. jsonb::text renders `"dir": "row"` (space after
--    colon); the spaceless variants cover documents written by older tooling.
ALTER TABLE workspaces
    ADD COLUMN IF NOT EXISTS home_visible BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE workspaces
    SET layout = replace(
        replace(
            replace(
                replace(layout::text,
                    '"dir": "row"', '"dir": "vertical"'),
                '"dir": "col"', '"dir": "horizontal"'),
            '"dir":"row"', '"dir":"vertical"'),
        '"dir":"col"', '"dir":"horizontal"')::jsonb
    WHERE layout IS NOT NULL
      AND layout::text LIKE '%"dir"%';
