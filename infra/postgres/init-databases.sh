#!/usr/bin/env bash
set -euo pipefail

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set=orders_password="$ORDEROPS_DATABASE_PASSWORD" \
  --set=fulfillment_password="$FULFILLMENT_DATABASE_PASSWORD" <<'SQL'
CREATE ROLE orders LOGIN PASSWORD :'orders_password';
CREATE DATABASE orders OWNER orders;
CREATE ROLE fulfillment LOGIN PASSWORD :'fulfillment_password';
CREATE DATABASE fulfillment OWNER fulfillment;
SQL
