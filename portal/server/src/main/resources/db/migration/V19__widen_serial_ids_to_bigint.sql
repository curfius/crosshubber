-- Align SERIAL (int4) primary keys with the JPA entities, which declare Long (int8).
-- Detected by ddl-auto=validate in the smoke test; previously invisible because
-- production runs with ddl-auto=none. Widening is forward-compatible: int4 values
-- fit in int8, and the backing sequences are already bigint-based internally.
ALTER TABLE entry_points
  ALTER COLUMN id DROP DEFAULT,
  ALTER COLUMN id TYPE BIGINT,
  ALTER COLUMN id SET DEFAULT nextval('entry_points_id_seq'::regclass);
ALTER SEQUENCE entry_points_id_seq AS BIGINT MAXVALUE 9223372036854775807;

ALTER TABLE entry_point_groups
  ALTER COLUMN id DROP DEFAULT,
  ALTER COLUMN id TYPE BIGINT,
  ALTER COLUMN id SET DEFAULT nextval('entry_point_groups_id_seq'::regclass);
ALTER SEQUENCE entry_point_groups_id_seq AS BIGINT MAXVALUE 9223372036854775807;

ALTER TABLE module_versions
  ALTER COLUMN id DROP DEFAULT,
  ALTER COLUMN id TYPE BIGINT,
  ALTER COLUMN id SET DEFAULT nextval('module_versions_id_seq'::regclass);
ALTER SEQUENCE module_versions_id_seq AS BIGINT MAXVALUE 9223372036854775807;
