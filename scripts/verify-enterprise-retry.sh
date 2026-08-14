#!/bin/sh
set -eu
export MSYS_NO_PATHCONV=1
name=ekb-verify-runtime-v3
docker run -d --name "$name" --network enterprise-knowledge-base_default \
  -e "EKB_MYSQL_DSN=ekb:devonly1234@tcp(ekb-mysql-smoke:3306)/enterprise_kb?parseTime=true" \
  -e EKB_MINIO_ENDPOINT=ekb-minio:9000 \
  -e EKB_MINIO_ACCESS_KEY=ekbminio \
  -e EKB_MINIO_SECRET_KEY=devonly1234 \
  -e EKB_MINIO_BUCKET=enterprise-kb \
  -e EKB_OPENSEARCH_URL=http://ekb-opensearch:9200 \
  -e EKB_RAW_INDEX=ekb_raw_v1 \
  -e EKB_MODELS_URL=http://ekb-models:8000 \
  -e EKB_EMBED_DIMS=1024 \
  -e EKB_MINERU_URL=http://host.docker.internal:8000 \
  enterprise-knowledge-base:verify sleep infinity
docker cp ../.ai-bridge/mineru-smoke/scanned.pdf "$name":/tmp/scanned.pdf
docker exec "$name" /tmp/enterprise-kb ingest --file /tmp/scanned.pdf --logical-name scanned-failure-lifecycle.pdf
