#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# The can-i-deploy contract gate for auto-deploy.yml (extracted from the workflow, #4086).
#
# WHY THIS IS A FILE AND NOT A `run:` BLOCK
# GitHub imposes a per-STEP ceiling on `run:` script size, and crossing it does not produce a
# size error — the WHOLE workflow file stops being parseable. `name:` is never read, every push
# yields a run with ZERO jobs titled after the file path, and it reads as an ordinary red run.
# It kills the workflow for everyone, not just the author: #3135 blocked every contributor's
# deploy until it was reverted (#3139). The ceiling was measured by bisecting real pushes
# against GitHub — 20054 chars accepted, 20654 rejected — and `check-workflow-run-step-size.py`
# enforces 19000. Nothing local sees this class: PyYAML, a strict duplicate-key loader,
# actionlint and yamllint were all clean on the very file GitHub refused.
#
# This step sat at 18955 characters, 45 from that limit, and had been there since #4086 was
# filed. 45 characters is one comment line. The next person to explain a decision in place
# would have taken the whole workflow down, and the gate that exists to prevent that can only
# report the catastrophe, not create room.
#
# The prose is deliberately kept — this file has no size limit, which is the entire point of
# moving it here. The house rule is "prose belongs in .github/scripts/* headers, never in a
# run: block", so nothing was compressed out; only its address changed.
#
# INPUTS (environment, supplied by the calling step)
#   SERVICES                 JSON array of changed service names        (required)
#   IMAGE_TAGS/IMAGE_DIGESTS  maps produced by the exact build-push step
#   EVENT_NAME               github.event_name                          (required)
#   INPUT_CODEPLOY           github.event.inputs.codeploy, may be empty
#   INPUT_CODEPLOY_PACT_VERSIONS
#                            optional JSON object mapping a co-deployed service to an exact
#                            Pact version that this gate proves equivalent to GITHUB_SHA
#   PACT_BROKER_URL/_USERNAME/_PASSWORD, PACT_STANDALONE_VERSION        (job env)
#   RECONCILE_SOURCE_REQUIRED  true for schedule/reconcile dispatch; requires exact source proof
#   GITHUB_OUTPUT            written for: gate_ran, deployable, and the rest below
#
# Behaviour is byte-identical to the inlined version: the body below is the step's script
# verbatim, with the three `${{ }}` interpolations replaced by the environment variables above.
# A `${{ }}` expression is substituted by the runner BEFORE the shell sees it, so it cannot
# survive extraction — and leaving one behind would have silently become a literal string.

set -uo pipefail
# Record that the gate actually STARTED, before anything that can fail. This output
# persists even if the step later exits non-zero, so gitops-pr can tell "gate never
# ran (no broker) → no filter" apart from "gate ran but crashed before a verdict →
# fail closed". `deployable` alone cannot: both leave it empty, and that ambiguity
# is exactly what made record-deployment record every changed service as deployed
# when a flaky pact-CLI download killed the gate (issue #1348).
echo "gate_ran=true" >> "$GITHUB_OUTPUT"
SERVICES="${SERVICES:?}"
reconcile_present='{}'
block_class='{}'
block_reason='{}'
reconcile_blocked='[]'
reconcile_source_blocked=0

refuse_reconcile_source() {
  local svc="$1"
  local why="Broker has no Pacticipant and no verified Pact source"
  echo "::error::reconcile source refused for ${svc}: ${why}"
  reconcile_source_blocked=1
  reconcile_blocked="$(jq -cn --argjson old "$reconcile_blocked" --arg svc "$svc" '$old + [$svc] | unique')"
  block_class="$(jq -cn --argjson old "$block_class" --arg svc "$svc" '$old + {($svc): "NOT_ASKED"}')"
  block_reason="$(jq -cn --argjson old "$block_reason" --arg svc "$svc" --arg why "$why" '$old + {($svc): $why}')"
}

# Reconcile builds the current workflow SHA, while its usable Pact may have been published by
# an earlier path-scoped Services CI run. A Broker tag alone is not provenance. Resolve and
# verify eligible service sources: exact current SHA, or an older exact-main Services
# CI SHA whose service tree is byte-identical to the image being built. Known absent Pacticipants
# are individually refused; ambiguous source, API failure, failed child job, or a non-equivalent
# tree aborts before any Pact verdict
# or Broker setup mutation. gitops-pr treats gate_ran=true + deployable=[] as fail-closed.
if [ "${RECONCILE_SOURCE_REQUIRED:-false}" = "true" ]; then
  source_failure=0
  if [ "${GITHUB_REF:-}" != "refs/heads/main" ]; then
    echo "::error::reconcile source proof is valid only for a workflow run on refs/heads/main"
    source_failure=1
  fi
  explicit_sources="${INPUT_CODEPLOY_PACT_VERSIONS:-}"
  use_explicit_sources=false
  if ! jq -e '
    type == "array" and length > 0 and
    all(.[]; type == "string" and test("^openbank-[a-z0-9-]+$")) and
    length == (unique | length)
  ' >/dev/null <<< "$SERVICES"; then
    echo "::error::reconcile service list is malformed or ambiguous"
    source_failure=1
  fi
  # These maps are outputs of the successful build-push job. The gate waits for push,
  # signature, scan, SBOM and provenance; the verifier requires exact service coverage and
  # full pushed digests so an overwritten mutable tag cannot change what gets selected.
  if ! python3 .github/scripts/verify-reconcile-image-map.py \
      "$SERVICES" "${IMAGE_TAGS:-}" "${IMAGE_DIGESTS:-}" "${BUILD_IMAGE_TAG:-}" "$GITHUB_SHA"; then
    echo "::error::reconcile image evidence is missing, ambiguous, or not built from ${GITHUB_SHA}"
    source_failure=1
  fi
  if [ "${INPUT_CODEPLOY:-}" = "true" ] && [ -n "$explicit_sources" ] && [ "$explicit_sources" != "{}" ]; then
    if ! jq -e --argjson services "$SERVICES" \
      'type == "object" and (keys | sort) == ($services | sort)' >/dev/null <<< "$explicit_sources"; then
      echo "::error::reconcile source map must map exactly the requested services"
      source_failure=1
    else
      use_explicit_sources=true
    fi
  fi
  historical_services='[]'
  source_probe_map='{}'
  historical_source_map='{}'
  if [ "$source_failure" -eq 0 ] && [ "$use_explicit_sources" != true ]; then
    for svc in $(echo "$SERVICES" | jq -r '.[]'); do
      present="$(bash .github/scripts/probe-pact-version.sh "$svc" "$GITHUB_SHA")"
      source_probe_map="$(jq -cn --argjson old "$source_probe_map" --arg svc "$svc" \
        --arg present "$present" '$old + {($svc): $present}')"
      case "$present" in
        yes|equivalent:*) ;;
        no)
          historical_services="$(jq -cn --argjson old "$historical_services" \
            --arg svc "$svc" '$old + [$svc]')"
          ;;
        absent)
          refuse_reconcile_source "$svc"
          ;;
        *)
          echo "::error::reconcile source refused for ${svc}: no exact or tree-equivalent Pact source (${present})"
          source_failure=1
          ;;
      esac
    done
  fi
  if [ "$source_failure" -eq 0 ] && [ "$(jq -r 'length' <<< "$historical_services")" -gt 0 ]; then
    historical_source_map="$(python3 .github/scripts/verify-successful-service-ci.py \
      --resolve-reconcile-sources "$historical_services" "$GITHUB_SHA")" || {
      echo "::error::reconcile source refused: no verified historical Services CI source for every requested service"
      source_failure=1
    }
    if [ "$source_failure" -eq 0 ] && ! jq -e --argjson services "$historical_services" '
      type == "object" and (keys | sort) == ($services | sort) and
      all(.[]; type == "object" and
        (.sha | type == "string" and test("^[0-9a-f]{40}$")) and
        (.run_id | type == "string" and length > 0) and
        (.attempt | type == "number" and . > 0))
    ' >/dev/null <<< "$historical_source_map"; then
      echo "::error::reconcile source refused: historical source resolver returned incomplete or malformed evidence"
      source_failure=1
    fi
  fi
  if [ "$source_failure" -eq 0 ]; then
    for svc in $(echo "$SERVICES" | jq -r '.[]'); do
      # Known absence refuses only this member; the full image-map keyset was checked above.
      if jq -e --arg svc "$svc" 'index($svc) != null' >/dev/null <<< "$reconcile_blocked"; then
        continue
      fi
      source_ci_verified=false
      if [ "$use_explicit_sources" = true ]; then
        source_sha="$(jq -r --arg svc "$svc" '.[$svc] // empty' <<< "$explicit_sources")"
        present="yes"
      else
        present="$(jq -r --arg svc "$svc" '.[$svc] // empty' <<< "$source_probe_map")"
        case "$present" in
          yes) source_sha="$GITHUB_SHA" ;;
          equivalent:*) source_sha="${present#equivalent:}" ;;
          no)
            source_sha="$(jq -er --arg svc "$svc" '.[$svc].sha' <<< "$historical_source_map")"
            resolved="$(jq -c --arg svc "$svc" '.[$svc]' <<< "$historical_source_map")"
            present="equivalent:${source_sha}"
            source_ci_verified=true
            echo "  ✓ ${svc} will use exact successful Services CI/Pact source ${source_sha} from run $(jq -r '.run_id' <<< "$resolved")"
            ;;
          absent)
            # Reconcile is retried independently of the original push. Without a
            # registered Pacticipant there is no durable contract verdict to bind
            # to this image; a later registration could make the same image unsafe
            # after this job has already handed its result to gitops-pr.
            refuse_reconcile_source "$svc"
            continue
            ;;
          *)
            echo "::error::reconcile source refused for ${svc}: no exact or tree-equivalent Pact source (${present})"
            source_failure=1
            continue
            ;;
        esac
      fi
      if ! grep -qE '^[0-9a-f]{40}$' <<< "$source_sha"; then
        echo "::error::reconcile source refused for ${svc}: source is not a full 40-hex SHA"
        source_failure=1
        continue
      fi
      if [ "$source_sha" != "$GITHUB_SHA" ] \
         && ! bash .github/scripts/pact-version-tree-equivalent.sh "$svc" "$source_sha" "$GITHUB_SHA"; then
        echo "::error::reconcile source refused for ${svc}: source tree is not equivalent to the current image tree"
        source_failure=1
        continue
      fi
      if [ "$use_explicit_sources" = true ]; then
        exact_present="$(EVENT_NAME=push PACT_WAIT_PER_SERVICE_SECONDS=0 \
          bash .github/scripts/probe-pact-version.sh "$svc" "$source_sha")"
        if [ "$exact_present" != "yes" ]; then
          echo "::error::reconcile source refused for ${svc}: explicit Pact version is not verifiably present (${exact_present})"
          source_failure=1
          continue
        fi
      fi
      if [ "$source_ci_verified" != true ] \
         && ! python3 .github/scripts/verify-successful-service-ci.py "$svc" "$source_sha"; then
        source_failure=1
        continue
      fi
      reconcile_present="$(jq -cn --argjson old "$reconcile_present" --arg svc "$svc" \
        --arg present "$present" '$old + {($svc): $present}')"
    done
  fi
  if [ "$source_failure" -ne 0 ]; then
    echo "deployable=[]" >> "$GITHUB_OUTPUT"
    echo "blocked=${SERVICES}" >> "$GITHUB_OUTPUT"
    {
      echo "### can-i-deploy: reconcile source evidence missing"
      echo "No can-i-deploy verdict or GitOps image-pin mutation was allowed because at least one service lacked exact image evidence, successful exact-main Services CI build/contract evidence, or a verifiable Pact source."
      echo "Requested services: \`$(echo "$SERVICES" | jq -r 'join(" ")')\`."
    } >> "$GITHUB_STEP_SUMMARY"
    exit 1
  fi
  if [ "$reconcile_source_blocked" -eq 1 ] && {
    [ "$(jq 'length' <<< "$reconcile_blocked")" -eq "$(jq 'length' <<< "$SERVICES")" ] ||
    { [ "$EVENT_NAME" = workflow_dispatch ] && [ "${INPUT_CODEPLOY:-}" = true ]; }
  }; then
    # No eligible member, or an explicitly atomic co-deploy set cannot be proven in full.
    echo "deployable=[]" >> "$GITHUB_OUTPUT"
    echo "blocked=${SERVICES}" >> "$GITHUB_OUTPUT"
    echo "### Reconcile refused: no complete Pact source for the requested set" >> "$GITHUB_STEP_SUMMARY"
    exit 1
  fi
  echo "Verified exact build-push image evidence for the full set and successful exact-main CI/Pact source for every eligible reconcile service."
fi

arch="$(uname -m)"; case "$arch" in aarch64|arm64) a=arm64 ;; *) a=x86_64 ;; esac
os="$(uname -s | tr '[:upper:]' '[:lower:]')"; case "$os" in darwin) o=osx ;; *) o=linux ;; esac
CLI="${PACT_CLI:-/tmp/pact/pact/bin/pact-broker}"
# Download only on a cache miss (the "Cache pact standalone CLI" step restores /tmp/pact
# on a hit). This removes the flaky github.com release download — the single point of
# failure that fail-closes the whole gate (issue #1348/#1009) — from every run after the
# first. On a genuine persistent download failure this still exits non-zero, and
# gate_ran=true + empty deployable then makes gitops-pr fail closed, exactly as before.
if [ ! -x "$CLI" ]; then
  tgz="pact-${PACT_STANDALONE_VERSION}-${o}-${a}.tar.gz"
  url="https://github.com/pact-foundation/pact-ruby-standalone/releases/download/v${PACT_STANDALONE_VERSION}/${tgz}"
  dl_ok=0
  for attempt in 1 2 3; do
    if curl -fsSL --retry 2 --retry-delay 3 -o /tmp/pact.tgz "$url"; then dl_ok=1; break; fi
    echo "::warning::pact standalone download attempt ${attempt}/3 failed; retrying in 5s"
    sleep 5
  done
  if [ "$dl_ok" -ne 1 ]; then
    echo "::error::could not download pact standalone ($url) after 3 attempts — cannot run contract gate; gitops-pr will fail closed (deploy nothing) this run"
    exit 1
  fi
  mkdir -p /tmp/pact && tar -xzf /tmp/pact.tgz -C /tmp/pact
else
  echo "  ✓ pact CLI restored from cache ($CLI)"
fi
# Ensure the sandbox environment is registered.
#
# This was written as "idempotent, 409 = already exists" and is not: the broker answers a
# duplicate name with 400 and a validation body, so on every single run after the first this
# printed
#   Error making request to <broker>/environments status=400
#   {"errors":{"name":["name 'sandbox' is already used by an existing environment."]}}
#   ::warning::could not ensure sandbox environment (may already exist — continuing)
# — a red-looking error and a warning, in the one log a person reads while a deploy is stuck,
# describing the NORMAL state (#6568). A real broker outage produces the same two lines, so the
# noise also costs the signal. Look first, and only create when it is genuinely missing; a
# failure to LOOK is still a warning, because then the create is the fallback.
env_auth="${PACT_BROKER_USERNAME}:${PACT_BROKER_PASSWORD}"
if envs_body="$(curl -sf --max-time 20 -u "$env_auth" "${PACT_BROKER_URL}/environments" 2>/dev/null)" \
   && printf '%s' "$envs_body" | jq -e '[.._embedded?, .environments?] | flatten | map(select(.name? == "sandbox")) | length > 0' >/dev/null 2>&1; then
  echo "  ✓ sandbox environment already registered"
else
  "$CLI" create-environment --name sandbox --display-name Sandbox --no-production \
    --broker-base-url "$PACT_BROKER_URL" \
    --broker-username "$PACT_BROKER_USERNAME" \
    --broker-password "$PACT_BROKER_PASSWORD" \
    && echo "  ✓ sandbox environment created" \
    || echo "::warning::could not ensure sandbox environment (may already exist — continuing)"
fi
rc=0
deployable_list=()
# Shared wait budget for probe-pact-version.sh (#3082). See that script's header.
PACT_WAIT_BUDGET_FILE="$(mktemp)"; export PACT_WAIT_BUDGET_FILE
echo "${PACT_WAIT_BUDGET_SECONDS:-180}" > "$PACT_WAIT_BUDGET_FILE"
# Per-service block classification (#2549): PENDING_BUILD / UNVERIFIED /
# REGRESSION / UNKNOWN, plus the human reason. Read below by the left-behind
# summary and by the scheduled-tick silencing rule.
# Verbatim CLI output of every service that ends up blocked, in the record format
# derive-codeploy-set.py reads. Written only in the block branch, so a clean run
# leaves it empty and the derivation prints nothing (#1985).
BLOCKS_FILE="$(mktemp)"

# ── #1985: co-deploy mode ───────────────────────────────────────────────────────
# Services that block EACH OTHER cannot converge one deploy at a time; this asks
# the broker whether THOSE exact build versions are mutually compatible. Full reasoning in
# derive-codeploy-set.py's header. It is a gate, not a bypass — a red verdict
# deploys nothing — but a DIFFERENT question, so it is opt-in on workflow_dispatch
# and never reachable from a push or a scheduled tick. Do not use `--latest main` here:
# a later, unrelated main build can move that tag between selection and the question, turning
# this into a verdict about a different image.
if [ "${EVENT_NAME}" = "workflow_dispatch" ] \
   && [ "${INPUT_CODEPLOY}" = "true" ]; then
  explicit_versions="${INPUT_CODEPLOY_PACT_VERSIONS:-}"
  use_explicit_versions=false
  if [ -n "$explicit_versions" ] && [ "$explicit_versions" != "{}" ]; then
    if ! jq -e --argjson services "$SERVICES" \
      'type == "object" and (keys | sort) == ($services | sort)' >/dev/null <<< "$explicit_versions"; then
      echo "::error::codeploy_pact_versions must map exactly the requested services to 40-hex versions"
      echo "deployable=[]" >> "$GITHUB_OUTPUT"
      exit 1
    fi
    use_explicit_versions=true
  fi
  CODEPLOY_ARGS=()
  codeploy_skipped=()
  for svc in $(echo "$SERVICES" | jq -r '.[]'); do
    if [ "$use_explicit_versions" = true ]; then
      pact_version="$(jq -r --arg svc "$svc" '.[$svc] // empty' <<< "$explicit_versions")"
      if ! grep -qE '^[0-9a-f]{40}$' <<< "$pact_version"; then
        echo "::error::codeploy_pact_versions has no valid 40-hex version for ${svc}"
        echo "deployable=[]" >> "$GITHUB_OUTPUT"
        exit 1
      fi
      if ! bash .github/scripts/pact-version-tree-equivalent.sh "$svc" "$pact_version" "$GITHUB_SHA"; then
        echo "::error::codeploy_pact_versions entry for ${svc} is not byte-identical to the deploy ref in every build input"
        echo "deployable=[]" >> "$GITHUB_OUTPUT"
        exit 1
      fi
      CID_SELECTOR=(--version "$pact_version")
      echo "    ${svc}: --version ${pact_version} (explicit version proven byte-identical to deploy ref)"
    else
      # #5993: the co-deploy matrix may ONLY be asked at exact versions. The shared
      # selector is right for the per-service loop below, where `--latest main` is a
      # documented ADR-0092 fallback — but here that moving tag makes the one question
      # this branch exists to ask a question about a DIFFERENT artifact, and on the
      # `unknown` (broker probe inconclusive) path it can answer GREEN for a pair that is
      # not being deployed. resolve-codeploy-selector.sh narrows it to exact | SKIP |
      # REFUSE; its --self-test asserts no branch can ever emit a moving tag again.
      if [ "${RECONCILE_SOURCE_REQUIRED:-false}" = "true" ]; then
        vpresent="$(jq -r --arg svc "$svc" '.[$svc] // empty' <<< "$reconcile_present")"
      else
        vpresent="$(bash .github/scripts/probe-pact-version.sh "$svc" "$GITHUB_SHA")"
      fi
      sel_line="$(PACT_VERSION_PRESENT="$vpresent" \
        bash .github/scripts/resolve-codeploy-selector.sh "$svc" "$GITHUB_SHA")"
      read -ra CID_SELECTOR <<< "$(printf '%s' "$sel_line" | cut -f1)"
      if [ "${CID_SELECTOR[0]}" = "REFUSE" ]; then
        echo "::error::can-i-deploy co-deploy set cannot be asked safely for ${svc}: $(printf '%s' "$sel_line" | cut -f2)"
        echo "deployable=[]" >> "$GITHUB_OUTPUT"
        exit 1
      fi
      if [ "${CID_SELECTOR[0]}" = "SKIP" ]; then
        # Not a pacticipant at all — no contracts either way, so it cannot break any pair.
        # It stays in the deploy set and leaves the MATRIX, which is the per-service loop's
        # 404 rule applied here. Pinning it to `--latest main` instead made the whole set
        # read as a contract break that does not exist.
        echo "    ${svc}: not in the matrix ($(printf '%s' "$sel_line" | cut -f2))"
        codeploy_skipped+=("$svc")
        continue
      fi
      echo "    ${svc}: ${CID_SELECTOR[*]} ($(printf '%s' "$sel_line" | cut -f2))"
    fi
    CODEPLOY_ARGS+=(--pacticipant "$svc" "${CID_SELECTOR[@]}")
  done
  if [ "${#CODEPLOY_ARGS[@]}" -eq 0 ]; then
    # Every requested service turned out to have no contracts in the broker. There is no
    # matrix to ask, and asking `can-i-deploy` with zero pacticipants is a CLI error that
    # would read as a contract break. Nothing here can break a contract, so the set is
    # deployable — but say so explicitly rather than letting an empty question answer it.
    echo "  ✓ none of the requested services is a Pacticipant — no contracts to check (ADR-0092)"
    echo "deployable=${SERVICES}" >> "$GITHUB_OUTPUT"
    echo "blocked=[]" >> "$GITHUB_OUTPUT"
    exit 0
  fi
  echo "==> can-i-deploy CO-DEPLOY SET (#1985): $(echo "$SERVICES" | jq -r 'join(" ")')"
  if "$CLI" can-i-deploy "${CODEPLOY_ARGS[@]}" \
       --broker-base-url "$PACT_BROKER_URL" \
       --broker-username "$PACT_BROKER_USERNAME" \
       --broker-password "$PACT_BROKER_PASSWORD" \
       --retry-while-unknown 3 --retry-interval 5; then
    echo "  ✓ the whole set is mutually deployable — deploying it as one unit"
    echo "deployable=${SERVICES}" >> "$GITHUB_OUTPUT"
    echo "blocked=[]" >> "$GITHUB_OUTPUT"
    {
      echo "### can-i-deploy: co-deploy set verified (#1985)"
      echo "Asked as ONE question, not per service: \`$(echo "$SERVICES" | jq -r 'join(" ")')\`"
      if [ "${#codeploy_skipped[@]}" -gt 0 ]; then
        echo ""
        echo "Not in the matrix (no contracts in the broker, ADR-0092): \`${codeploy_skipped[*]}\`"
      fi
    } >> "$GITHUB_STEP_SUMMARY"
    exit 0
  fi
  echo "::error::can-i-deploy: the requested co-deploy set is NOT mutually deployable — this is a real contract break, not an ordering problem, and no co-deploy can resolve it (#1985)"
  echo "deployable=[]" >> "$GITHUB_OUTPUT"
  exit 1
fi

for svc in $(echo "$SERVICES" | jq -r '.[]'); do
  if jq -e --arg svc "$svc" 'index($svc) != null' >/dev/null <<< "$reconcile_blocked"; then
    rc=1
    printf '===SERVICE %s\tNOT_ASKED\n' "$svc" >> "$BLOCKS_FILE"
    continue
  fi
  # A service that has never published a contract is not a Pacticipant in the
  # broker; `can-i-deploy` then errors with "Pacticipant <svc> not found" (matrix
  # 400) and the verdict reads as "NOT deployable". But a service with NO contracts
  # cannot break any consumer/provider expectation, so it is trivially deployable.
  # Probe the broker first: a 404 on /pacticipants/<svc> means "no contracts" → skip
  # the (erroring) matrix query and treat it as deployable. (pid-service hit exactly
  # this — it has no pacts; ADR-0092.)
  code="$(curl -s -o /dev/null -w '%{http_code}' \
    -u "${PACT_BROKER_USERNAME}:${PACT_BROKER_PASSWORD}" \
    "${PACT_BROKER_URL}/pacticipants/${svc}" || echo 000)"
  if [ "${RECONCILE_SOURCE_REQUIRED:-false}" = "true" ] && [ "$code" != "200" ]; then
    # A source that disappears or becomes unobservable after preflight cannot
    # inherit the successful source proof. Do not let 404, 5xx or network errors
    # turn into the ordinary no-contract deploy path for a reconcile.
    if [ "$code" = 404 ]; then
      refuse_reconcile_source "$svc"
      rc=1
      printf '===SERVICE %s\tNOT_ASKED\n' "$svc" >> "$BLOCKS_FILE"
      continue
    fi
    echo "::error::reconcile source refused for ${svc}: Pacticipant is missing or unavailable after source preflight (HTTP ${code})"
    echo "deployable=[]" >> "$GITHUB_OUTPUT"
    echo "blocked=${SERVICES}" >> "$GITHUB_OUTPUT"
    {
      echo "### can-i-deploy: reconcile Pacticipant state changed"
      echo "The Broker no longer proves that every no-contract service is absent; no GitOps image-pin mutation was allowed."
    } >> "$GITHUB_STEP_SUMMARY"
    exit 1
  fi
  if [ "$code" = "404" ]; then
    echo "  ✓ ${svc} has no contracts in the broker (not a Pacticipant) — deployable"
    deployable_list+=("$svc")
    continue
  fi
  # WHICH version to ask about is decided by resolve-can-i-deploy-selector.sh from a
  # broker probe (probe-pact-version.sh, which also does the bounded wait). Asking
  # `--latest main` on a push routinely answers about the PREVIOUS build — see #3082
  # and both scripts' headers. (The "keep this comment SHORT" instruction that used to
  # stand here is obsolete as of #4086: this is a script now, and it has no ceiling.)
  if [ "${RECONCILE_SOURCE_REQUIRED:-false}" = "true" ]; then
    vpresent="$(jq -r --arg svc "$svc" '.[$svc] // empty' <<< "$reconcile_present")"
  else
    vpresent="$(bash .github/scripts/probe-pact-version.sh "$svc" "$GITHUB_SHA")"
  fi
  sel_line="$(PACT_VERSION_PRESENT="$vpresent" EVENT_NAME="$GITHUB_EVENT_NAME" \
    bash .github/scripts/resolve-can-i-deploy-selector.sh "$svc" "$GITHUB_SHA")"
  read -ra CID_SELECTOR <<< "$(printf '%s' "$sel_line" | cut -f1)"
  # REFUSE (#3318): a manual dispatch of a sha with no pact version. Skip THIS
  # service — aborting the batch is the #846 shape (#3445).
  if [ "${CID_SELECTOR[0]}" = "REFUSE" ]; then
    refuse_why="$(printf '%s' "$sel_line" | cut -f2)"
    # #3454: classify it too, from the SAME inputs the selector refused on — a
    # class-less record reads downstream as [UNKNOWN]. Empty stdin: no verdict exists.
    r_line="$(PACT_VERSION_PRESENT="$vpresent" EVENT_NAME="$GITHUB_EVENT_NAME" \
      bash .github/scripts/classify-can-i-deploy-block.sh "$svc" </dev/null)"
    cls="$(cut -f1 <<< "$r_line")"
    block_class="$(jq -cn --argjson old "$block_class" --arg svc "$svc" --arg cls "$cls" \
      '$old + {($svc): $cls}')"
    block_reason="$(jq -cn --argjson old "$block_reason" --arg svc "$svc" \
      --arg reason "$(cut -f2- <<< "$r_line")" '$old + {($svc): $reason}')"
    echo "::error::can-i-deploy: [${cls}] ${refuse_why}"
    printf '===SERVICE %s\t%s\n%s\n' "$svc" "$cls" "$refuse_why" >> "$BLOCKS_FILE"
    rc=1  # keep the job red (ADR-0092); only the batch survives
    continue
  fi
  echo "==> can-i-deploy ${svc} (${CID_SELECTOR[*]}) → environment sandbox"
  echo "    $(printf '%s' "$sel_line" | cut -f2)"
  cid_out=$("$CLI" can-i-deploy \
       --pacticipant "$svc" "${CID_SELECTOR[@]}" \
       --to-environment sandbox \
       --broker-base-url "$PACT_BROKER_URL" \
       --broker-username "$PACT_BROKER_USERNAME" \
       --broker-password "$PACT_BROKER_PASSWORD" \
       --retry-while-unknown 3 --retry-interval 5 2>&1) && cid_rc=0 || cid_rc=$?
  echo "$cid_out"
  if [ "$cid_rc" -eq 0 ]; then
    echo "  ✓ ${svc} deployable"
    deployable_list+=("$svc")
  else
    # "No version with tag main exists": new service that has been registered as a
    # Pacticipant (via record-deployment) but has never published a pact or been
    # verified against one. Has the same semantics as a 404 Pacticipant — it cannot
    # break any consumer/provider expectation. Treat as deployable.
    if echo "$cid_out" | grep -q "No version with tag"; then
      echo "::warning::can-i-deploy: ${svc} has no 'main'-tagged version yet (new service, no pacts) — treating as deployable (ADR-0092)"
      deployable_list+=("$svc")
    # If every failure line is "no version is currently recorded as deployed/released
    # in this environment", the counterpart service has simply not been deployed to
    # sandbox yet and therefore cannot break any contract expectation. Treat it the
    # same as a 404 pacticipant (not a Pacticipant → trivially deployable).
    # Count only the per-pair "There is no verified pact..." explanation lines, NOT
    # the one-time "Computer says no" banner the CLI prints regardless of how many
    # pairs failed — folding the banner into the count made a single real failure
    # look like two, so the >= comparison below could never hold for exactly one
    # failing pair (issue surfaced by fraud-service/transaction-service, 2026-07-09).
    elif [ "$(echo "$cid_out" | grep -c "There is no verified pact" || true)" -gt 0 ] && \
         [ "$(echo "$cid_out" | grep -c "no version is currently recorded" || true)" -ge "$(echo "$cid_out" | grep -c "There is no verified pact" || true)" ]; then
      echo "::warning::can-i-deploy: ${svc} has counterpart(s) not yet recorded in sandbox — treating as deployable (ADR-0092)"
      deployable_list+=("$svc")
    else
      # Why blocked? #2549 (lag vs regression) + #3223 (counterpart carries no pacts,
      # so waiting cannot help). Reasoning in both script headers. Reuse $vpresent.
      # A probe that cannot run must not abort the step nor restore the promise.
      IFS=$'\t' read -r cp_st cp_dt \
        < <(bash .github/scripts/probe-blocking-counterparts.sh <<< "$cid_out") \
        || { cp_st=unknown; cp_dt=""; }
      cls_line="$(PACT_VERSION_PRESENT="$vpresent" COUNTERPART_STATE="$cp_st" \
        COUNTERPART_DETAIL="$cp_dt" \
        bash .github/scripts/classify-can-i-deploy-block.sh "$svc" <<< "$cid_out")"
      cls="$(cut -f1 <<< "$cls_line")"
      cls_why="$(cut -f2- <<< "$cls_line")"
      block_class="$(jq -cn --argjson old "$block_class" --arg svc "$svc" --arg cls "$cls" \
        '$old + {($svc): $cls}')"
      block_reason="$(jq -cn --argjson old "$block_reason" --arg svc "$svc" \
        --arg reason "$cls_why" '$old + {($svc): $reason}')"
      echo "::error::can-i-deploy: ${svc} NOT deployable [${cls}] — ${cls_why} (ADR-0092, #2549)"
      # Class too: derive-codeploy-set.py rejects transient blocks (#1985).
      printf '===SERVICE %s\t%s\n%s\n' "$svc" "$cls" "$cid_out" >> "$BLOCKS_FILE"
      rc=1
    fi
  fi
done
# Emit the per-service deployable set BEFORE the final exit — step outputs written
# via $GITHUB_OUTPUT persist even when the step subsequently exits non-zero, which is
# exactly what lets gitops-pr record-deployment proceed for the services that DID
# pass even when this job's own status is red because others didn't (issue #846).
deployable_json="$(printf '%s\n' "${deployable_list[@]:-}" | jq -R . | jq -sc 'map(select(length > 0))')"
echo "deployable=${deployable_json}" >> "$GITHUB_OUTPUT"
echo "Deployable this run: ${deployable_json}"
# ── #1420: name the services left behind (changed − deployable), loudly ──────────
# The gate keeps the deployable SUBSET moving and only fails its own status "for
# visibility"; the complement is silently starved. Four money-path services sat 5+
# days behind main before anyone diffed image tags by hand (issue #1420). The gate
# already knows the complement — emit it so a green-looking run cannot hide a stuck
# money-path deploy. This changes NO deploy behaviour: rc/deployable/exit are
# untouched; it only adds a summary + annotations, escalating to ::error:: when a
# money-path service is the one left behind.
BLOCKED_JSON="$(jq -cn --argjson all "$SERVICES" --argjson ok "$deployable_json" '$all - $ok' 2>/dev/null || echo '[]')"
echo "blocked=${BLOCKED_JSON}" >> "$GITHUB_OUTPUT"
BLOCKED_N="$(echo "$BLOCKED_JSON" | jq 'length')"
if [ "$BLOCKED_N" -gt 0 ]; then
  # money-path service names come from the governance source of truth, so this can't
  # rot as the fleet's money-path set changes.
  MP="$(awk '/^money_path_services:/{f=1;next} f&&/^  - /{print $2} f&&/^[a-zA-Z]/{exit}' openbank-libs/governance/rules.yaml)"
  {
    echo "### can-i-deploy: ${BLOCKED_N} changed service(s) left behind"
    echo "Rebuilt this run but blocked by the contract gate — they stay on their current (older) image while \`main\` advances:"
  } >> "$GITHUB_STEP_SUMMARY"
  mp_blocked=0
  regression_blocked=0
  for svc in $(echo "$BLOCKED_JSON" | jq -r '.[]'); do
    # #2549: name the KIND of block, not just the fact of it. Default UNKNOWN —
    # a service can land in BLOCKED_JSON without going through the classifier
    # (e.g. it never reached the per-service loop), and an unclassified block
    # must not silently inherit a transient label.
    svc_cls="$(jq -r --arg svc "$svc" '.[$svc] // "UNKNOWN"' <<< "$block_class")"
    svc_why="$(jq -r --arg svc "$svc" '.[$svc] // "no classification recorded for this service"' <<< "$block_reason")"
    [ "$svc_cls" = "REGRESSION" ] && regression_blocked=1
    if echo "$MP" | grep -qx "$svc"; then
      echo "::error::[${svc}] MONEY-PATH service blocked by can-i-deploy [${svc_cls}] — ${svc_why}; it stays behind main (issue #1420)"
      echo "- \`${svc}\` — **money-path**, blocked — \`${svc_cls}\`: ${svc_why}" >> "$GITHUB_STEP_SUMMARY"
      mp_blocked=1
    elif [ "$svc_cls" = "REGRESSION" ]; then
      echo "::error::[${svc}] blocked by can-i-deploy [REGRESSION] — ${svc_why} (issue #2549)"
      echo "- \`${svc}\` — blocked — \`REGRESSION\`: ${svc_why}" >> "$GITHUB_STEP_SUMMARY"
    else
      echo "::warning::[${svc}] blocked by can-i-deploy [${svc_cls}] — ${svc_why} (issue #1420)"
      echo "- \`${svc}\` — blocked — \`${svc_cls}\`: ${svc_why}" >> "$GITHUB_STEP_SUMMARY"
    fi
  done
  # ── #1985: name the SET, not just the members ────────────────────────────────
  # #1420 names who is blocked and #2549 names what kind; neither answers "can
  # this deploy at all, one at a time?". Derive it instead of working it out by
  # hand from the broker matrix (unit-tested, no network:
  # .github/scripts/test-derive-codeploy-set.sh).
  CHANGED_LIST=()
  while IFS= read -r changed_service; do
    CHANGED_LIST+=("$changed_service")
  done < <(echo "$SERVICES" | jq -r '.[]')
  CODEPLOY_OUT="$(python3 .github/scripts/derive-codeploy-set.py "${CHANGED_LIST[@]}" < "$BLOCKS_FILE" || true)"
  if [ -n "$CODEPLOY_OUT" ]; then
    echo "" >> "$GITHUB_STEP_SUMMARY"
    while IFS=$'\t' read -r kind a b; do
      case "$kind" in
        CODEPLOY)
          echo "::error::CO-DEPLOY SET — [${a}] block each other; no per-service deploy order converges. Re-run: gh workflow run auto-deploy.yml -f services='${a}' -f codeploy=true (issue #1985)"
          {
            echo "#### Co-deploy set (#1985)"
            echo "\`${a}\` block **each other** — none of them can converge one deploy at a time."
            echo ""
            echo "\`\`\`"
            echo "gh workflow run auto-deploy.yml -f services='${a}' -f codeploy=true"
            echo "\`\`\`"
          } >> "$GITHUB_STEP_SUMMARY"
          ;;
        PENDING)
          echo "::notice::[${a}] waiting on their own main-push build (PENDING_BUILD) — self-clearing, not a deadlock. Do NOT co-deploy (#1985)"
          echo "- \`${a}\` — waiting on their own builds, not deadlocked; no action" >> "$GITHUB_STEP_SUMMARY"
          ;;
        UNGATED)
          echo "::error::[${a}] have NO can-i-deploy verdict at all [NOT_ASKED] — the gate was refused, not answered; a co-deploy over them would be no check, and re-running the same dispatch cannot help (#3454)"
          echo "- \`${a}\` — \`NOT_ASKED\`: no verdict exists; deploy the sha services-ci built for them, or publish a pact version for this one" >> "$GITHUB_STEP_SUMMARY"
          ;;
        EXTERNAL)
          echo "::warning::[${a}] is blocked on ${b}, which is NOT part of this run — a co-deploy cannot help; ${b} has to deploy first (issue #1985)"
          echo "- \`${a}\` waits on \`${b}\`, outside this run — co-deploy does not apply" >> "$GITHUB_STEP_SUMMARY"
          ;;
      esac
    done <<< "$CODEPLOY_OUT"
  fi
  {
    echo ""
    echo "\`PENDING_BUILD\`/\`UNVERIFIED\` clear themselves — the every-3h reconcile tick"
    echo "re-drives them (auto-deploy-reconcile-lag.sh, #2020). \`REGRESSION\` does not:"
    echo "it needs a contract fix, and is reported as an error on every run including"
    echo "scheduled ticks, and neither does \`NOT_ASKED\` (#3454), which means the gate was"
    echo "never asked at all. \`UNKNOWN\` means the classifier could not tell — read the log."
  } >> "$GITHUB_STEP_SUMMARY"
  [ "$mp_blocked" = "1" ] && echo "::error::can-i-deploy left one or more MONEY-PATH services behind this run — see the job summary (issue #1420)"
  [ "$regression_blocked" = "1" ] && echo "::error::can-i-deploy: at least one block is a CONTRACT REGRESSION, which no reconcile tick can clear — see the job summary (issue #2549)"
fi
# On a scheduled reconcile tick an ordinary (non money-path) service still blocked by
# the contract gate is EXPECTED — reconcile deliberately re-offers known strands and
# leaves the still-blocked ones for a later tick; the deployable subset already
# advanced via gitops-pr. Failing the job for that would make the every-3h schedule
# perpetually red — a red workflow addressed to nobody (see auto-deploy.yml scheduling
# note). So on `schedule` only fail the status when a MONEY-PATH service is the one
# left behind (mp_blocked=1), which must stay loud. push / workflow_dispatch keep the
# unconditional ADR-0092 behaviour below. Deploy behaviour is unchanged either way:
# gitops-pr deploys the `deployable` subset and never the blocked complement.
#
# #2549 narrows that silence. "Expected on a reconcile tick" is true of a block the
# tick can eventually CLEAR — PENDING_BUILD (the service's build has not published
# its pacts yet) and UNVERIFIED (the counterpart verifies minutes later). It is NOT
# true of a REGRESSION: no number of ticks fixes a failed verification, so silencing
# it turns the 3-hourly retry into an indefinite, invisible loop around a real
# contract break — retried forever, reported never. A REGRESSION therefore fails the
# scheduled run exactly as a money-path block does.
if [ "${EVENT_NAME}" = "schedule" ] && [ "$rc" -ne 0 ] \
   && [ "${mp_blocked:-0}" != "1" ] && [ "${regression_blocked:-0}" != "1" ] \
   && [ "$reconcile_source_blocked" != "1" ]; then
  echo "::notice::reconcile tick: ${BLOCKED_N:-0} service(s) still gate-blocked (non money-path, self-clearing classes only) — left for a later tick; the deployable subset deployed. Not failing the scheduled run."
  exit 0
fi
# Enforce: non-zero rc still fails this job's own status for visibility (ADR-0092) —
# it no longer blocks gitops-pr/record-deployment for the deployable subset above.
exit $rc
