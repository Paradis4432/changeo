#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
pgbin="${CHANGEO_PG_BIN:-$PWD/.local-tools/postgres/bin}"
runtime="$PWD/.runtime"
mkdir -p "$runtime/socket"
if [[ ! -f "$runtime/pgdata/PG_VERSION" ]]; then
  python3 -c 'import secrets,pathlib; pathlib.Path(".runtime/db.password").write_text(secrets.token_urlsafe(32))'
  "$pgbin/initdb" -D "$runtime/pgdata" -U changeo --pwfile="$runtime/db.password" --auth=scram-sha-256 --encoding=UTF8 --locale=C
fi
if ! "$pgbin/pg_ctl" -D "$runtime/pgdata" status >/dev/null 2>&1; then
  "$pgbin/pg_ctl" -D "$runtime/pgdata" -l "$runtime/postgres.log" -o "-h 127.0.0.1 -p 55432 -k $runtime/socket" start
fi
export PGPASSWORD
PGPASSWORD="$(cat "$runtime/db.password")"
if ! "$pgbin/psql" -h 127.0.0.1 -p 55432 -U changeo -d postgres -Atc "select 1 from pg_database where datname='changeo'" | rg -q '^1$'; then
  "$pgbin/createdb" -h 127.0.0.1 -p 55432 -U changeo changeo
fi
