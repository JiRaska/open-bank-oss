#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Moved verbatim out of _service-ci.yml, which is expanded once per Services CI caller
# under GitHub's expanded-workflow size cap. pipefail keeps the inline-step semantics.
set -o pipefail
set -euo pipefail
git fetch --quiet origin main
bash "${SERVICE_CI_HELPERS}/consumer-pact-handoff.sh" validate pact-handoff
mkdir -p pacts
# Remove only this validated consumer's old files before installing the exact handoff set.
rm -f pacts/"${SERVICE}"-*.json
shopt -s nullglob
files=(pact-handoff/"${SERVICE}"-*.json)
for f in ${files[@]+"${files[@]}"}; do
  cp "$f" pacts/
done
bash "${SERVICE_CI_HELPERS}/consumer-pact-handoff.sh" validate pacts
