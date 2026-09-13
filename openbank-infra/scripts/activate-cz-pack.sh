#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# ADR-0212 D4 bootstrap, steps 2-3 of the runbook (seed -> ACTIVATE -> verify -> flip):
# four-eyes-activate the CZ reference pack against a live lending-service, then verify.
# The flip of LENDING_ENFORCE_PACK stays a separate gitops PR after this script's
# /active output shows the pack — flipping earlier refuses every origination.
#
# Usage:
#   BASE_URL=https://api.open-bank.tech \
#   MAKER_TOKEN=<ROLE_COMPLIANCE JWT of principal A> \
#   CHECKER_TOKEN=<ROLE_COMPLIANCE JWT of principal B, must differ from A> \
#   openbank-infra/scripts/activate-cz-pack.sh
set -euo pipefail

BASE_URL="${BASE_URL:?set BASE_URL (e.g. https://api.open-bank.tech)}"
MAKER_TOKEN="${MAKER_TOKEN:?set MAKER_TOKEN (compliance maker JWT)}"
CHECKER_TOKEN="${CHECKER_TOKEN:?set CHECKER_TOKEN (compliance checker JWT, must differ from maker)}"
PACK_FILE="$(git rev-parse --show-toplevel)/openbank-lending-service/src/main/resources/compliance-packs/cz-consumer-credit-v1.json"

echo "==> 1/3 maker proposes the CZ pack"
proposal=$(curl -fsS -X POST "${BASE_URL}/api/v1/lending/compliance-packs/proposals" \
  -H "Authorization: Bearer ${MAKER_TOKEN}" \
  -H "Content-Type: application/json" \
  --data-binary "@${PACK_FILE}")
echo "${proposal}" | python3 -m json.tool
proposal_id=$(echo "${proposal}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

echo "==> 2/3 checker approves (must differ from maker — server enforces four-eyes)"
# Captured, then pretty-printed separately rather than `curl | python3` (OpenSSF Scorecard
# Pinned-Dependencies: downloadThenRun flags any curl-into-interpreter pipe, code or not —
# this is a JSON response, not a downloaded script, but the check can't tell the two apart).
decision="$(curl -fsS -X POST "${BASE_URL}/api/v1/lending/compliance-packs/proposals/${proposal_id}/decide" \
  -H "Authorization: Bearer ${CHECKER_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"approve": true, "reason": "reviewed against 257/2016 Sb. and CCD2"}')"
echo "${decision}" | python3 -m json.tool

echo "==> 3/3 verify the pack is active"
active="$(curl -fsS "${BASE_URL}/api/v1/lending/compliance-packs/active" \
  -H "Authorization: Bearer ${CHECKER_TOKEN}")"
echo "${active}" | python3 -m json.tool

echo ""
echo "NEXT (manual): flip LENDING_ENFORCE_PACK=true in openbank-infra/gitops/components/lending/lending-service.yaml"
echo "as its own one-line PR. Do NOT flip before this script's /active output shows the pack."
