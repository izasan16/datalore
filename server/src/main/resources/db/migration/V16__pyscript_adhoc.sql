-- Ad-hoc runs (no script bound, like SQL lab direct run)
ALTER TABLE pyscript_run ALTER COLUMN script_id DROP NOT NULL;