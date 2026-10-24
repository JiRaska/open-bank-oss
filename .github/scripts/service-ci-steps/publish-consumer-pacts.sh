#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
shopt -s nullglob
files=(pacts/"${SERVICE}"-*.json)
if [ "${#files[@]}" -eq 0 ]; then
  echo "${SERVICE} is not a consumer of any contract — nothing to publish."
  exit 0
fi
contracts="$(for f in "${files[@]}"; do
  jq -nc \
    --arg c "$(jq -r '.consumer.name' "$f")" \
    --arg p "$(jq -r '.provider.name' "$f")" \
    --arg content "$(base64 < "$f" | tr -d '\n')" \
    '{consumerName:$c, providerName:$p, specification:"pact", contentType:"application/json", content:$content}'
done | jq -sc '.')"
body="$(jq -nc \
  --arg name "${SERVICE}" \
  --arg ver "$GITHUB_SHA" \
  --arg branch "$BRANCH" \
  --arg build "${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}" \
  --argjson contracts "$contracts" \
  '{pacticipantName:$name, pacticipantVersionNumber:$ver, branch:$branch, tags:[$branch], buildUrl:$build, contracts:$contracts}')"
echo "Publishing ${#files[@]} pact(s) for ${SERVICE} @ ${GITHUB_SHA::8} (branch ${BRANCH})"
body_file=$(mktemp)
trap 'rm -f "$body_file"' EXIT
printf '%s' "$body" > "$body_file"
code="$(bash "$RUNNER_TEMP/pact-broker-post.sh" \
  "${PACT_BROKER_URL%/}/contracts/publish" "$body_file" /tmp/pact-publish.json \
  'application/hal+json, application/json')"
jq -r '.notices[]?.text // empty' /tmp/pact-publish.json 2>/dev/null || cat /tmp/pact-publish.json
echo "Pact publication accepted (HTTP $code)."
