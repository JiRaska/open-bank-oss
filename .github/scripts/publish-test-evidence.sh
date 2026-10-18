#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Publish ONE component's Test Intelligence posture to the commit-addressed evidence store
# (#13201), write-once. The single upload path for both callers:
#   * _service-ci.yml publish-evidence  — tests ran on the commit being built (identical)
#   * test-evidence-reconcile.yml       — the running image's commit, tested on a commit the
#                                         reconciler PROVED build-identical (tree-equivalent)
#
#   publish-test-evidence.sh <component> <tested_sha> <key_sha> <identical|tree-equivalent> <run.json>
#
# Needs AWS credentials for the evidence-writer role in the environment. Exit 0 = published OR
# already published (412: write-once, the first publication stands). Anything else is non-zero.
set -uo pipefail

COMPONENT="${1:?component}"; TESTED="${2:?tested sha}"; KEY_SHA="${3:?key sha}"
EQUIVALENCE="${4:?equivalence}"; RUN_JSON="${5:?run.json}"
BUCKET="${EVIDENCE_BUCKET:-openbank-sandbox-test-evidence}"
ROOT="$(git rev-parse --show-toplevel)"
OUT="$(mktemp)"

released=false
[ -f "${ROOT}/${COMPONENT}/version.txt" ] && released=true
money_path="$(python3 - "${COMPONENT}" "${ROOT}/openbank-libs/governance/rules.yaml" <<'PY'
import sys, yaml
rules = yaml.safe_load(open(sys.argv[2])) or {}
services = rules.get("money_path_services") or (rules.get("services") or {}).get("money_path") or []
print("true" if sys.argv[1] in services else "false")
PY
)" || { echo "::warning::money-path inventory unreadable"; exit 1; }

key="$(node "${ROOT}/openbank-admin-ui/scripts/publish-test-evidence.mjs" \
  --run "${RUN_JSON}" --component "${COMPONENT}" --commit "${TESTED}" \
  --key-commit "${KEY_SHA}" --equivalence "${EQUIVALENCE}" \
  --released "${released}" --money-path "${money_path}" --out "${OUT}")" || exit 1

out="$(aws s3api put-object --bucket "${BUCKET}" --key "${key}" \
  --body "${OUT}" --content-type application/json \
  --cache-control 'public, max-age=31536000, immutable' \
  --if-none-match '*' 2>&1)"
status=$?
if [ "${status}" -eq 0 ]; then
  echo "published s3://${BUCKET}/${key} (${EQUIVALENCE}, tested on ${TESTED:0:8})"
elif printf '%s' "${out}" | grep -q 'PreconditionFailed'; then
  echo "kept s3://${BUCKET}/${key} (already published; write-once)"
else
  printf '%s\n' "${out}" >&2
  echo "::warning::Test Intelligence evidence not published for ${key}"
  exit 1
fi
