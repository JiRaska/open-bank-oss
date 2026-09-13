#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Run the PromQL unit tests against the real PrometheusRule manifests.
#
# promtool consumes bare `groups:` documents; the repo ships Prometheus Operator
# PrometheusRule CRs. This unwraps `.spec` from each named CR into a temp dir and points
# promtool at that, so the tests run against the SHIPPED expression -- not a copy of it that
# can drift. Every rule file named by a test's `rule_files:` must exist as a CR here.
#
# Usage: openbank-infra/tests/promtool/run.sh [test-file ...]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
RULES_DIR="$REPO/openbank-infra/gitops/components/observability"

command -v promtool >/dev/null || { echo "promtool not on PATH"; exit 127; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

python3 - "$RULES_DIR" "$WORK" <<'PY'
import sys, pathlib, yaml
src, dst = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
for f in sorted(src.glob("prometheus-rules-*.yaml")):
    for doc in yaml.safe_load_all(f.read_text()):
        if isinstance(doc, dict) and doc.get("kind") == "PrometheusRule":
            (dst / f.name).write_text(yaml.safe_dump(doc["spec"], sort_keys=False))
PY

# Sanity: promtool must accept every unwrapped file before any test runs. A test that
# "passes" against a rule file promtool could not parse would be testing nothing.
promtool check rules "$WORK"/*.yaml >/dev/null

tests=("$@")
if [ ${#tests[@]} -eq 0 ]; then
  # shellcheck disable=SC2207
  tests=($(find "$HERE" -name '*_test.yaml' | sort))
fi

status=0
for t in "${tests[@]}"; do
  # rule_files: are written relative to the test file; run from the unwrapped dir so the
  # bare filenames resolve there.
  cp "$t" "$WORK/$(basename "$t")"
  # `set -e` would abort on the first red test before the loop could record it, so the
  # failure is captured by the `if` rather than read from `$?` after the fact.
  if ! ( cd "$WORK" && promtool test rules "$(basename "$t")" ); then
    status=1
  fi
done
exit $status
