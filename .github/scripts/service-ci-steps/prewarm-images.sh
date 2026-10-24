#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
if [ "${ECR_LOGIN_OUTCOME}" = "success" ] \
   && docker pull "$ECR/docker-hub/library/postgres:18.6-alpine"; then
  docker pull "$ECR/docker-hub/valkey/valkey:7.2-alpine" &
  docker pull "$ECR/docker-hub/redpandadata/redpanda:v24.1.2" &
  docker pull "$ECR/docker-hub/apicurio/apicurio-registry-mem:2.6.2.Final" &
  wait
  docker tag "$ECR/docker-hub/library/postgres:18.6-alpine"                  postgres:18.6-alpine
  docker tag "$ECR/docker-hub/valkey/valkey:7.2-alpine"                      valkey/valkey:7.2-alpine
  docker tag "$ECR/docker-hub/redpandadata/redpanda:v24.1.2"                 redpandadata/redpanda:v24.1.2
  docker tag "$ECR/docker-hub/apicurio/apicurio-registry-mem:2.6.2.Final"   apicurio/apicurio-registry-mem:2.6.2.Final
  echo "::notice title=TC pre-warm done::Testcontainers images ready (ECR pull-through, zero NAT)"
elif curl -sf --connect-timeout 1 \
    http://registry-cache.registry-cache.svc.cluster.local:5000/v2/ > /dev/null 2>&1; then
  echo "::warning title=TC pre-warm::ECR unavailable — using in-cluster registry-cache"
  docker pull postgres:18.6-alpine &
  docker pull valkey/valkey:7.2-alpine &
  docker pull redpandadata/redpanda:v24.1.2 &
  docker pull apicurio/apicurio-registry-mem:2.6.2.Final &
  wait
else
  echo "::warning title=TC pre-warm::no image cache reachable — Testcontainers will pull at test time"
fi
