-- Retire navigation_layout (the pre-decommission Portal Navigation dashboard
-- document) and seed it into the shell-tree model, per plan/UX_PLAN.md trees
-- unification:
--   sections -> navigation_groups (category 'applications', nav-<slug> keys,
--               parent_key nesting, per-parent sort_order step 10)
--   items    -> registry_module_contents rows matched by "moduleKey:contentKey"
--               ref (group_key + per-bucket sort_order + hidden)
--   unlisted -> application rows the document does not name keep group_key NULL
--               and are appended after the listed root items (ShellTreeService
--               semantics; the sidebar renders ungrouped contents at root)
--   pinnedSectionEnabled -> navigation_settings.settings JSONB (default true)
-- then DROP the table.
--
-- Guards: seeds only when a document exists with a sections array AND no
-- 'applications' groups exist yet (group_key is globally unique — an already
-- seeded tree is never re-seeded; a stale document is dropped with the table).
-- The seed writes no group_key longer than 'nav-' + 40 + '-NN' (fits the
-- [a-z0-9-]{0,63} key space) and dedupes against existing keys.
-- Known behavioral note (accepted in review): legacy documents nested deeper
-- than MAX_SHELL_DEPTH=3 seed fine but their next save is rejected by the
-- depth check — flatten in the editor if that ever fires.
DO $$
DECLARE
  v_lay          JSONB;
  v_existing     INT;
  v_row          RECORD;
  v_parent_path  INT[];
  v_parent_key   TEXT;
  v_base         TEXT;
  v_key          TEXT;
  v_suffix       INT;
  v_name         TEXT;
  v_hidden       BOOLEAN;
  v_start        INT;
BEGIN
  SELECT settings INTO v_lay FROM navigation_layout WHERE id = 1;
  IF v_lay IS NULL OR jsonb_typeof(v_lay -> 'sections') IS DISTINCT FROM 'array' THEN
    RETURN;
  END IF;

  SELECT count(*) INTO v_existing FROM navigation_groups WHERE category = 'applications';
  IF v_existing > 0 THEN
    RETURN;
  END IF;

  CREATE TEMP TABLE _v35_nodes (path INT[], node JSONB) ON COMMIT DROP;
  INSERT INTO _v35_nodes (path, node)
  WITH RECURSIVE walk(path, node) AS (
    SELECT ARRAY[i.ord::INT], i.value
      FROM jsonb_array_elements(v_lay -> 'sections') WITH ORDINALITY AS i(value, ord)
    UNION ALL
    SELECT w.path || c.ord::INT, c.value
      FROM walk w
      CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(w.node -> 'children') = 'array'
             THEN w.node -> 'children' ELSE '[]'::jsonb END)
        WITH ORDINALITY AS c(value, ord)
  )
  SELECT path, node FROM walk;

  CREATE TEMP TABLE _v35_sections (
    path       INT[],
    group_key  TEXT,
    parent_key TEXT,
    name       TEXT,
    hidden     BOOLEAN
  ) ON COMMIT DROP;

  -- Sections breadth-first: parents are always one path segment shorter than
  -- their children, so they are inserted (and dedup-seen) first.
  FOR v_row IN
    SELECT t.path, t.node
      FROM _v35_nodes t
     WHERE COALESCE(t.node ->> 'type', 'section') <> 'item'
     ORDER BY array_length(t.path, 1), t.path
  LOOP
    v_parent_path := NULL;
    v_parent_key := NULL;
    IF array_length(v_row.path, 1) > 1 THEN
      v_parent_path := v_row.path[1 : array_length(v_row.path, 1) - 1];
      SELECT s.group_key INTO v_parent_key
        FROM _v35_sections s WHERE s.path = v_parent_path;
    END IF;

    v_base := lower(regexp_replace(COALESCE(v_row.node ->> 'id', ''), '[^a-z0-9]+', '-', 'g'));
    v_base := trim(BOTH '-' FROM v_base);
    IF length(v_base) > 40 THEN
      v_base := trim(BOTH '-' FROM left(v_base, 40));
    END IF;
    IF v_base = '' THEN
      v_base := 'section';
    END IF;
    v_key := 'nav-' || v_base;
    v_suffix := 2;
    WHILE EXISTS (SELECT 1 FROM navigation_groups WHERE group_key = v_key)
       OR EXISTS (SELECT 1 FROM _v35_sections s WHERE s.group_key = v_key) LOOP
      v_key := 'nav-' || v_base || '-' || v_suffix;
      v_suffix := v_suffix + 1;
    END LOOP;

    v_name := left(COALESCE(NULLIF(btrim(v_row.node ->> 'name'), ''), 'Section'), 256);
    v_hidden := COALESCE((v_row.node ->> 'hidden')::boolean, false);

    INSERT INTO _v35_sections (path, group_key, parent_key, name, hidden)
    VALUES (v_row.path, v_key, v_parent_key, v_name, v_hidden);
  END LOOP;

  -- ORDER BY path guarantees parents before children for the FK (a parent path
  -- is a strict prefix of its child path, and int[] prefix sorts first).
  INSERT INTO navigation_groups (group_key, category, name, parent_key, sort_order, roles, hidden)
  SELECT s.group_key, 'applications', s.name, s.parent_key,
         (row_number() OVER (PARTITION BY s.parent_key ORDER BY s.path) - 1) * 10,
         '', s.hidden
    FROM _v35_sections s
   ORDER BY s.path;

  -- Items: resolve parent group by path prefix (root items slice to an empty
  -- array, which matches no section), rank per bucket in document order.
  CREATE TEMP TABLE _v35_items (
    module_key  TEXT,
    content_key TEXT,
    parent_key  TEXT,
    hidden      BOOLEAN,
    ord         INT
  ) ON COMMIT DROP;
  INSERT INTO _v35_items (module_key, content_key, parent_key, hidden, ord)
  SELECT split_part(i.ref, ':', 1), split_part(i.ref, ':', 2), s.group_key, i.hidden,
         (row_number() OVER (PARTITION BY COALESCE(s.group_key, '') ORDER BY i.path) - 1) * 10
    FROM (SELECT t.path, t.node ->> 'ref' AS ref,
                 COALESCE((t.node ->> 'hidden')::boolean, false) AS hidden
            FROM _v35_nodes t
           WHERE t.node ->> 'type' = 'item') i
    LEFT JOIN _v35_sections s
      ON s.path = i.path[1 : array_length(i.path, 1) - 1]
   WHERE position(':' in i.ref) > 0;

  UPDATE registry_module_contents m
     SET group_key = i.parent_key, sort_order = i.ord, hidden = i.hidden
    FROM _v35_items i
   WHERE m.category = 'applications'
     AND m.module_key = i.module_key
     AND m.content_key = i.content_key;

  -- Unlisted: append after the listed root items, preserving their relative
  -- order (window functions are illegal in UPDATE ... SET, hence the subquery).
  SELECT COALESCE(MAX(ord), -10) + 10 INTO v_start FROM _v35_items WHERE parent_key IS NULL;
  UPDATE registry_module_contents m
     SET group_key = NULL, sort_order = u.new_ord
    FROM (SELECT m2.module_key, m2.content_key,
                 v_start + (row_number() OVER (ORDER BY m2.sort_order, m2.module_key, m2.content_key) - 1) * 10
                    AS new_ord
            FROM registry_module_contents m2
           WHERE m2.category = 'applications'
             AND NOT EXISTS (SELECT 1 FROM _v35_items i
                              WHERE i.module_key = m2.module_key
                                AND i.content_key = m2.content_key)) u
   WHERE m.module_key = u.module_key AND m.content_key = u.content_key;

  UPDATE navigation_settings
     SET settings = settings || jsonb_build_object(
           'pinnedSectionEnabled', COALESCE((v_lay ->> 'pinnedSectionEnabled')::boolean, true))
   WHERE id = 1;
END $$;

DROP TABLE navigation_layout;
