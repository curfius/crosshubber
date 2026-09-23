-- Manifest installs (InstallService.applyInstall) stored entry_points.sandbox as raw
-- JSON array text ('["allow-scripts"]') while the navigation editor comma-joins the
-- same column ('allow-scripts'). The output DTO splits on commas, so legacy rows came
-- back mangled with brackets/quotes. Normalize stored arrays to comma-joined tokens.
UPDATE entry_points
   SET sandbox = replace(
         replace(
           regexp_replace(regexp_replace(sandbox, '^\[', ''), '\]$', ''),
           '"', ''),
         ', ', ',')
 WHERE sandbox LIKE '[%'
   AND sandbox LIKE '%]';
