#!/bin/bash
cd ..
docker compose down -v redis
docker compose down -v orientdb
docker compose down -v clickhouse

docker compose up -d redis
docker compose up -d orientdb
docker compose up -d clickhouse