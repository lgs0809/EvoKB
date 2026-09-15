#!/usr/bin/env python3
"""Read-only migration guard: retain original columns and hash every public table without exporting rows."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def pg(sql):
    result = subprocess.run(
        ['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-XAt',
         '-v', 'ON_ERROR_STOP=1', '-U', 'evokb_target', '-d', 'evokb_target'],
        input=sql, text=True, capture_output=True, check=True, timeout=90)
    return json.loads(result.stdout)


def quote(identifier):
    return '"' + identifier.replace('"', '""') + '"'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--baseline', type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Preserve each snapshot; use a fresh result path')
    baseline = json.loads(args.baseline.read_text()) if args.baseline else None
    columns = baseline['originalColumns'] if baseline else pg("""
      SELECT json_object_agg(table_name,columns) FROM (
        SELECT c.table_name,json_agg(c.column_name ORDER BY c.ordinal_position) columns
        FROM information_schema.columns c JOIN information_schema.tables t
          ON t.table_schema=c.table_schema AND t.table_name=c.table_name
        WHERE c.table_schema='public' AND t.table_type='BASE TABLE'
          AND c.table_name<>'flyway_schema_history'
        GROUP BY c.table_name) tables;
      """)
    parts = []
    for table, names in sorted(columns.items()):
        selected = ','.join(quote(name) for name in names)
        # Same original-column projection before and after an additive migration. No assumption
        # that adding a NULL column leaves to_jsonb(whole_row) unchanged.
        parts.append("SELECT '" + table.replace("'", "''") + "' AS table_name,"
                     "COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]') AS rows "
                     "FROM (SELECT " + selected + " FROM public." + quote(table) + ") t")
    sql = 'BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\n'
    sql += 'SELECT json_object_agg(table_name,rows) FROM (' + '\nUNION ALL\n'.join(parts) + ') tables;\nCOMMIT;\n'
    # psql command tags are suppressed with -t but transaction tags still appear; use -q below.
    result = subprocess.run(
        ['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-XqAt',
         '-v', 'ON_ERROR_STOP=1', '-U', 'evokb_target', '-d', 'evokb_target'],
        input=sql, text=True, capture_output=True, check=True, timeout=90)
    data = json.loads(result.stdout)
    tables = {name: {'rows': len(rows), 'sha256': hashlib.sha256(
        json.dumps(rows, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()}
        for name, rows in data.items()}
    changes = [name for name in tables if baseline and tables[name] != baseline['tables'][name]]
    report = {'status': ('PASS_ORIGINAL_TABLES_UNCHANGED' if not changes else 'FAIL_ORIGINAL_TABLES_CHANGED')
              if baseline else 'READ_ONLY_TARGET_BASELINE',
              'actualOperationTime': datetime.now(timezone.utc).isoformat(),
              'originalColumns': columns, 'tables': tables, 'changedTables': changes,
              'jarSha256': json.loads((ROOT / '.tmp/target/process.json').read_text())['jarSha256'],
              'boundary': 'No row values, credentials or content are exported. Flyway history is intentionally excluded; additive new columns are checked separately.'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.with_suffix('.sql').write_text('-- Read-only, original-column migration guard.\n' + sql)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k not in ('originalColumns', 'tables')}, ensure_ascii=False))
    if changes:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
