#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Moved verbatim out of _service-ci.yml, which is expanded once per Services CI caller
# under GitHub's expanded-workflow size cap. pipefail keeps the inline-step semantics.
set -o pipefail
set -euo pipefail
if [ ! -f pact-handoff/manifest.txt ]; then
  echo "::error::pact hand-off artifact from the build job has no manifest.txt — treating this as a failed hand-off, not an empty consumer-pact set (the build job always writes one)."
  exit 1
fi
cat pact-handoff/manifest.txt
mkdir -p pacts
shopt -s nullglob
files=(pact-handoff/*.json)
# bash 3.2 (macOS runners) treats an EMPTY array as unbound under `set -u`; the
# ${files[@]+...} form expands to nothing instead of aborting (consumer-less services).
for f in ${files[@]+"${files[@]}"}; do cp "$f" pacts/; done
echo "Staged ${#files[@]} consumer pact file(s) from the build job's hand-off."
