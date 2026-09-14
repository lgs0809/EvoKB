-- psql -v component_schema=evokb_evolution30_<12 hex characters> -f this-file.sql
-- Read-only inspection of a retained synthetic mechanism schema; never public.
\set ON_ERROR_STOP on
\if :{?component_schema}
\else
  \echo 'Supply component_schema from one evolution case report.'
  \quit 2
\endif
SELECT :'component_schema' ~ '^evokb_evolution30_[0-9a-f]{12}$' AS valid_component_schema \gset
\if :valid_component_schema
\else
  \echo 'Refusing a schema outside the retained evolution component namespace.'
  \quit 2
\endif

BEGIN READ ONLY;
SET LOCAL search_path TO :"component_schema", pg_catalog;
SELECT current_schema() AS inspected_schema,
       transaction_timestamp() AS inspected_at,
       current_setting('transaction_read_only') AS read_only;
SELECT version_id, count(*) AS independent_source_contributions
FROM source_usage_contribution GROUP BY version_id ORDER BY version_id;
SELECT asset_id, count(*) AS independent_entry_contributions
FROM asset_usage_contribution GROUP BY asset_id ORDER BY asset_id;
SELECT stage, target_id, qualified_at, selected_task_id
FROM kb_generation_candidate ORDER BY stage, target_id;
SELECT business_day, stage, count(*) AS selected_count
FROM kb_generation_daily_selection GROUP BY business_day, stage ORDER BY business_day, stage;
SELECT business_day, stage, target_id, task_id
FROM kb_generation_daily_selection ORDER BY business_day, stage, target_id;
SELECT task_id, knowledge_base_id, version_id, task_type, subject_id, status,
       attempt, execution_epoch, priority, error_code
FROM compile_task ORDER BY created_at, task_id;
COMMIT;
