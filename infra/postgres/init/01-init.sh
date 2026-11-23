#!/bin/sh
# Runs once, the first time the Postgres volume is created.
#
# Least privilege, two logins for one service:
#   ledger_owner -> owns schema "ledger" and runs the Flyway migrations (DDL)
#   ledger_app   -> what the running service uses. The migrations grant it only what it needs:
#                   SELECT + INSERT on the ledger history, never UPDATE or DELETE.
# The owner could still disable a trigger; the app login cannot (it doesn't own the tables).
set -eu

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname "$POSTGRES_DB" \
     -v owner_pw="$LEDGER_OWNER_DB_PASSWORD" \
     -v app_pw="$LEDGER_APP_DB_PASSWORD" <<'EOSQL'
-- :'name' makes psql quote the value safely as a SQL literal
CREATE ROLE ledger_owner LOGIN PASSWORD :'owner_pw';
CREATE ROLE ledger_app   LOGIN PASSWORD :'app_pw';

REVOKE CONNECT ON DATABASE payments FROM PUBLIC;
GRANT  CONNECT ON DATABASE payments TO ledger_owner, ledger_app;

CREATE SCHEMA ledger AUTHORIZATION ledger_owner;

ALTER ROLE ledger_owner SET search_path = ledger;
ALTER ROLE ledger_app   SET search_path = ledger;
EOSQL
