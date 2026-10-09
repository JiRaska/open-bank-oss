#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Cache the fleet's exact PostgreSQL artifact before compilation or test startup.
set -euo pipefail
module=${1:?expected module name}
[[ "$module" =~ ^openbank-[a-z0-9-]+$ ]] || { echo "invalid module name" >&2; exit 2; }
[[ -d "$module" ]] || { echo "missing module: $module" >&2; exit 2; }
roots=()
for source_set in test integrationTest providerPactTest; do
  [[ ! -d "$module/src/$source_set" ]] || roots+=("$module/src/$source_set")
done
if [[ ${#roots[@]} -eq 0 ]]; then
  echo "PostgreSQL pre-warm: no test source sets in $module"
  exit 0
fi
if grep -rqE --include='*.kt' --include='*.java' \
    'postgres:18\.6-alpine|com\.openbank\.libs\.testing\.containers\.Postgres' "${roots[@]}"; then
  :
else
  result=$?
  if [[ "$result" -ne 1 ]]; then
    echo "::error::cannot inspect PostgreSQL test declarations for $module" >&2
    exit "$result"
  fi
  echo "PostgreSQL pre-warm: no fleet image declared by $module test sources"
  exit 0
fi
tag=postgres:18.6-alpine
digest=sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873
images=("mirror.gcr.io/library/$tag@$digest" "$tag@$digest")
for image in "${images[@]}"; do
  if docker image inspect "$image" >/dev/null 2>&1; then
    docker tag "$image" "$tag"
    echo "PostgreSQL pre-warm: exact cached artifact ready"
    exit 0
  fi
done
for image in "${images[@]}"; do
  if timeout 90s docker pull "$image"; then
    docker tag "$image" "$tag"
    echo "PostgreSQL pre-warm: pinned artifact ready for Testcontainers"
    exit 0
  fi
done
echo "::error::cannot prepare the declared PostgreSQL test artifact from either registry" >&2
exit 1
