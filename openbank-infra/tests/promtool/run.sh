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
PAYMENTS_DIR="$REPO/openbank-infra/gitops/components/payments"
BILLING_RULES="$REPO/openbank-infra/gitops/components/billing/prometheus-rules-billing.yaml"

command -v promtool >/dev/null || { echo "promtool not on PATH"; exit 127; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

python3 - "$RULES_DIR" "$PAYMENTS_DIR" "$BILLING_RULES" "$WORK" <<'PY'
import sys, pathlib, yaml
src, payments, billing, dst = map(pathlib.Path, sys.argv[1:])
for f in sorted(src.glob("prometheus-rules-*.yaml")):
    for doc in yaml.safe_load_all(f.read_text()):
        if isinstance(doc, dict) and doc.get("kind") == "PrometheusRule":
            (dst / f.name).write_text(yaml.safe_dump(doc["spec"], sort_keys=False))
# Every payments rule file (prometheus-rules.yaml plus the per-concern prometheus-rules-*.yaml,
# e.g. the #13623 unsettled-booking rules), one unwrapped file per CR named payments-<name>.yaml.
# A glob, not a list: a new payments rule file is testable the moment it exists.
payment_files = sorted(payments.glob("prometheus-rules*.yaml"))
if not payment_files:
    sys.exit(f"no PrometheusRule files under {payments}")
for f in payment_files:
    for doc in yaml.safe_load_all(f.read_text()):
        if isinstance(doc, dict) and doc.get("kind") == "PrometheusRule":
            name = doc["metadata"]["name"]
            out = dst / f"payments-{name}.yaml"
            if out.exists():
                sys.exit(f"duplicate PrometheusRule name {name} in {payments}")
            out.write_text(yaml.safe_dump(doc["spec"], sort_keys=False))
for doc in yaml.safe_load_all(billing.read_text()):
    if isinstance(doc, dict) and doc.get("kind") == "PrometheusRule":
        (dst / billing.name).write_text(yaml.safe_dump(doc["spec"], sort_keys=False))
PY

# Enumerated label values: a matcher on a value the exporter never emits is a rule that can
# never fire, and a fixture copied from the rule agrees with it (RolloutAnalysisAborted matched
# "Aborted" twice over, #4584 and #15101). Values read live with
# `label/phase/values?match[]=rollout_phase` (Argo Rollouts v1.10). Fails on any other value.
python3 - "$WORK" <<'PY'
import pathlib, re, sys
work = pathlib.Path(sys.argv[1])
KNOWN = {"rollout_phase": ("phase", {"Progressing", "Paused", "Completed", "Error", "Abort", "Timeout"})}
bad, files = [], sorted(work.glob("*.yaml"))
for f in files:
    text = f.read_text()
    for metric, (label, allowed) in KNOWN.items():
        for m in re.finditer(metric + r"\{([^}]*)\}", text):
            for v in re.findall(label + r'\s*=~?\s*"([^"]*)"', m.group(1)):
                for alt in v.split("|"):
                    if alt not in allowed:
                        bad.append(f"{f.name}: {metric}{{{label}=\"{alt}\"}}; the exporter emits only {sorted(allowed)}")
if bad:
    sys.exit("rule matches a label value that is never emitted:\n  " + "\n  ".join(bad))
print(f"label-value check: {len(files)} rule files, known metrics {sorted(KNOWN)}")
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
