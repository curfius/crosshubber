-- Fix roles TEXT[] -> TEXT for JPA String mapping
-- Keep simple: store as comma-separated or JSON string
ALTER TABLE modules ALTER COLUMN roles TYPE TEXT USING array_to_string(roles, ',');
ALTER TABLE entry_points ALTER COLUMN roles TYPE TEXT USING array_to_string(roles, ',');
ALTER TABLE entry_point_groups ALTER COLUMN roles TYPE TEXT USING array_to_string(roles, ',');
-- sandbox is also TEXT[] but stored as comma string
ALTER TABLE entry_points ALTER COLUMN sandbox TYPE TEXT USING array_to_string(sandbox, ',');
