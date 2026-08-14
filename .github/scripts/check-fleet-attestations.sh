#!/usr/bin/env bash
# ---------------------------------------------------------------------------------------
# Fleet SBOM-attestation gate (ADR-0030 D4, rules.yaml provenance.enforce_criteria).
#
# Enumerates EVERY openbank-* image referenced anywhere under openbank-infra/gitops/ and
# verifies each one carries a cosign-signed CycloneDX SBOM attestation that kyverno's
# verify-openbank-image-sbom-attestation ClusterPolicy would accept.
#
# WHY THIS IS THE GATE:
#   kyverno verifies at ADMISSION, not continuously. An unattested image whose pod is
#   already running keeps running — the policy never re-evaluates it. The violation is
#   LATENT until something reschedules that pod (node roll, eviction, scale-up), at which
#   point the pod is denied and can never restart. So "the fleet looks healthy" proves
#   nothing about whether the fleet is attested, and a PolicyReport with 0 fail only
#   describes pods that happen to exist right now.
#
#   This check reads the DECLARED images in gitops instead — the set of images the cluster
#   would admit — so a gap is caught while it is still latent, before a reschedule turns it
#   into an outage.
#
#   No Audit -> Enforce graduation of an image-provenance policy may proceed unless this
#   check passes. That precondition is encoded in rules.yaml provenance.enforce_criteria.
#
# COVERAGE: every openbank-* ECR reference in gitops — app containers, initContainers AND
# sidecars alike. The image regex is intentionally position-blind (it does not care which
# YAML key an image hangs off) precisely so a sidecar or initContainer cannot slip through.
#
# THREE OUTCOME CLASSES, kept distinct on purpose:
#   UNATTESTED — cosign REACHED the artifact and returned an attestation verdict: no valid
#                CycloneDX attestation for this key. This is the outage class: pods run now,
#                die forever on the next reschedule. Always fatal (exit 1).
#   ABSENT     — the image is not in the registry at all (a first-registration placeholder
#                tag that was never built). Also broken, but a different bug with a different
#                fix, and it is NOT an attestation regression. Fatal unless the image is
#                listed in the placeholder allowlist below.
#   UNKNOWN    — the PROBE could not run: ECR throttle, 5xx, expired credentials, DNS, a
#                cosign crash. This is NOT a verdict about the image and must never be
#                reported as one. Exit 2, mirroring
#                `.github/scripts/check-verification-metadata-complete.py` (0 = clean,
#                1 = real gap, 2 = could not run).
# Conflating the first two would make this gate permanently red on a known placeholder — and a
# permanently-red check is one nobody reads. That is exactly how sbom-drift-scanner's
# `sepa-payment: no-pod-found` sat unactioned on the morning of the outage.
#
# WHY UNKNOWN EXISTS (#1915, measured 2026-08-13): the loop used to special-case only the
# registry-absence strings and let EVERY other non-zero cosign exit fall through to
# UNATTESTED. Run 31729895636 therefore reported
# `UNATTESTED openbank-release-steward:sandbox-e80f4bc7 … 61 attested / 1 unattested / 62
# total` while 24 of the 25 most recent runs of the same gate passed on the identical,
# unchanged image, and a hand `cosign verify-attestation` against digest sha256:31d626…
# answered "The signatures were verified against the specified public key". A transient
# registry failure was published as a supply-chain verdict. Classification is now POSITIVE
# in both directions — an image is called UNATTESTED only when cosign says so in words —
# and every candidate failure is retried before being classified at all.
#
# Usage:
#   .github/scripts/check-fleet-attestations.sh              # verify the whole fleet
#   COSIGN_BIN=/path/to/cosign-v2 ... check-fleet-attestations.sh
#   FLEET_ATTEST_JSON=out.json ...                           # also emit a JSON report
#   VERIFY_ATTEMPTS=3 VERIFY_RETRY_SLEEP=5 ...               # bounded retry (defaults)
#   .github/scripts/check-fleet-attestations.sh --selftest   # prove the classifier both ways
#
# Exit codes: 0 = every declared image attested; 1 = a real gap (UNATTESTED and/or ABSENT);
#             2 = the check COULD NOT RUN for at least one image (registry/credential/tool
#             failure). 2 is not a verdict — callers must not report it as a fleet gap.
#
# Requires: AWS credentials with ECR read access, cosign v2.x, jq (report only).
# Read-only: never pushes, retags, signs, or mutates any image.
# ---------------------------------------------------------------------------------------
set -uo pipefail

GITOPS_DIR="${GITOPS_DIR:-openbank-infra/gitops}"
VERIFY_ATTEMPTS="${VERIFY_ATTEMPTS:-3}"
VERIFY_RETRY_SLEEP="${VERIFY_RETRY_SLEEP:-5}"
PLACEHOLDER_FILE="${PLACEHOLDER_FILE:-.github/scripts/fleet-attestation-placeholders.txt}"
COSIGN_KEY="${COSIGN_KEY:-awskms:///alias/openbank-cosign-signing}"
COSIGN_VERSION="${COSIGN_VERSION:-v2.4.3}"
ECR_REGISTRY="${ECR_REGISTRY:-265175468565.dkr.ecr.eu-north-1.amazonaws.com}"
FLEET_ATTEST_JSON="${FLEET_ATTEST_JSON:-}"

# Classify one failed `cosign verify-attestation` by its stderr. POSITIVE in both directions:
# ABSENT and UNATTESTED each require cosign to have SAID so; anything else is UNKNOWN, because
# a probe that could not reach a verdict has not produced one. The old shape had a positive
# rule for ABSENT and used UNATTESTED as the fallback, which is what turned an ECR throttle
# into a supply-chain finding (#1915).
classify_failure() {
  local err="$1"
  if printf '%s' "$err" | grep -qE 'NAME_UNKNOWN|MANIFEST_UNKNOWN|does not exist|not found|404'; then
    printf 'ABSENT\n'
    return 0
  fi
  # cosign's own attestation verdicts. `no matching attestations` covers both "none at all"
  # and "none this key/type accepts"; the signature/certificate wordings cover a payload that
  # exists but does not verify. All of these mean the artifact WAS fetched.
  if printf '%s' "$err" | grep -qE 'no matching attestations|no attestations|none of the attestations matched|signature not found|invalid signature|failed to verify signature|unable to verify signature|no signatures found|error validating.*signature|crypto/rsa: verification error'; then
    printf 'UNATTESTED\n'
    return 0
  fi
  printf 'UNKNOWN\n'
}

selftest() {
  local failures=0 got
  check_case() {
    local name="$1" expected="$2" input="$3"
    got="$(classify_failure "$input")"
    if [ "$got" = "$expected" ]; then
      printf '  ok: %s (%s)\n' "$name" "$got"
    else
      printf '  FAIL: %s — expected %s, got %s\n' "$name" "$expected" "$got"
      failures=$((failures + 1))
    fi
  }

  # ABSENT — the registry says the reference is not there.
  check_case "MANIFEST_UNKNOWN" ABSENT \
    'GET https://265175468565.dkr.ecr.eu-north-1.amazonaws.com/v2/openbank-x/manifests/t: MANIFEST_UNKNOWN'
  check_case "NAME_UNKNOWN" ABSENT 'NAME_UNKNOWN: The repository with name openbank-x does not exist'

  # UNATTESTED — cosign reached the artifact and returned a verdict.
  check_case "no matching attestations" UNATTESTED \
    'Error: no matching attestations:
main.go:74: error during command execution: no matching attestations:'
  check_case "invalid signature" UNATTESTED 'Error: invalid signature when validating ASN.1 encoded signature'

  # UNKNOWN — the probe could not run. Each of these used to be published as UNATTESTED.
  check_case "ECR throttle" UNKNOWN \
    'Error: GET https://...amazonaws.com/v2/: TOOMANYREQUESTS: Rate exceeded'
  check_case "registry 5xx" UNKNOWN \
    'Error: unexpected status code 503 Service Unavailable'
  check_case "expired credentials" UNKNOWN \
    'Error: unable to get credentials: ExpiredToken: The security token included in the request is expired'
  check_case "DNS failure" UNKNOWN \
    'Error: Get "https://...amazonaws.com/v2/": dial tcp: lookup ...: no such host'
  check_case "KMS unreachable" UNKNOWN \
    'Error: loading public key: getting signer: kms get: RequestCanceled: request context canceled'

  if [ "$failures" -gt 0 ]; then
    printf 'selftest FAILED (%s case(s))\n' "$failures"
    return 1
  fi
  printf 'selftest OK — classifier proven on all three classes, both directions.\n'
  return 0
}

if [ "${1:-}" = "--selftest" ]; then
  selftest
  exit $?
fi

# cosign v2 is pinned on purpose: kyverno 3.2.6 discovers attestations only via the legacy
# `sha256-<digest>.att` tag scheme; cosign v3 writes OCI 1.1 referrers it cannot read. This
# check MUST verify the same way kyverno does, or it would pass images kyverno rejects.
resolve_cosign_v2() {
  if [ -n "${COSIGN_BIN:-}" ] && [ -x "${COSIGN_BIN}" ]; then
    printf '%s\n' "${COSIGN_BIN}"
    return 0
  fi
  if command -v cosign >/dev/null 2>&1 \
     && cosign version 2>/dev/null | grep -Eq 'GitVersion:[[:space:]]*v2\.'; then
    command -v cosign
    return 0
  fi
  local os arch bin
  os="$(uname -s | tr '[:upper:]' '[:lower:]')"
  case "$(uname -m)" in
    aarch64 | arm64) arch=arm64 ;;
    x86_64 | amd64) arch=amd64 ;;
    *) return 1 ;;
  esac
  bin="${TMPDIR:-/tmp}/cosign-${COSIGN_VERSION}-${os}-${arch}"
  if [ ! -x "$bin" ]; then
    curl -fsSL -o "$bin" \
      "https://github.com/sigstore/cosign/releases/download/${COSIGN_VERSION}/cosign-${os}-${arch}" \
      2>/dev/null && chmod +x "$bin" || return 1
  fi
  printf '%s\n' "$bin"
}

if [ ! -d "$GITOPS_DIR" ]; then
  echo "ERROR: gitops dir not found: ${GITOPS_DIR} (run from the repo root)." >&2
  exit 1
fi

COSIGN_BIN_RESOLVED="$(resolve_cosign_v2 || true)"
if [ -z "$COSIGN_BIN_RESOLVED" ]; then
  echo "ERROR: cosign v2 unavailable — cannot verify fleet attestations." >&2
  echo "       Install cosign v2.x, set COSIGN_BIN, or set COSIGN_VERSION." >&2
  exit 1
fi

echo "==> Enumerating openbank-* images declared in ${GITOPS_DIR}"
echo "    cosign:   $("$COSIGN_BIN_RESOLVED" version 2>/dev/null | awk '/GitVersion/{print $2}')"
echo "    key:      ${COSIGN_KEY}"
echo

# Position-blind on purpose: matches `image:` under containers, initContainers, sidecars,
# ephemeralContainers, and any Helm/kustomize value that names a full openbank-* ref.
IMAGE_RE="${ECR_REGISTRY//./\\.}/openbank-[A-Za-z0-9._/-]+:[A-Za-z0-9._-]+"

# Read into an array without `mapfile` — this script must also run on macOS's bash 3.2
# (an operator verifying the gate by hand before authorizing a graduation).
IMAGES=()
while IFS= read -r _img; do
  [ -n "$_img" ] && IMAGES+=("$_img")
done < <(grep -rhoE "$IMAGE_RE" "$GITOPS_DIR" 2>/dev/null | sort -u)

if [ "${#IMAGES[@]}" -eq 0 ]; then
  echo "ERROR: no openbank-* images found under ${GITOPS_DIR} — the enumerator is broken." >&2
  echo "       (Failing closed: an empty fleet would otherwise 'pass' vacuously.)" >&2
  exit 1
fi

echo "==> ${#IMAGES[@]} distinct openbank-* image(s) declared"
echo

is_allowed_placeholder() {
  [ -f "$PLACEHOLDER_FILE" ] || return 1
  grep -vE '^[[:space:]]*(#|$)' "$PLACEHOLDER_FILE" | grep -qxF "$1"
}

PASS=0
UNATTESTED=0
ABSENT=0
ALLOWED=0
UNKNOWN=0
UNATTESTED_IMAGES=()
ABSENT_IMAGES=()
UNKNOWN_IMAGES=()

for image in "${IMAGES[@]}"; do
  short="${image#"${ECR_REGISTRY}"/}"

  # Bounded retry, then classify. A verdict (OK / ABSENT / UNATTESTED) is final on the first
  # attempt that produces one; only an UNKNOWN — which is precisely the transient class — is
  # retried, so a genuinely unattested image still fails fast and no result is retried into
  # existence. Attempts are logged so a flaky registry is visible rather than smoothed over.
  verdict=""
  attempt=1
  while :; do
    if err="$(COSIGN_YES=true "$COSIGN_BIN_RESOLVED" verify-attestation \
                --key "$COSIGN_KEY" --type cyclonedx "$image" 2>&1)"; then
      verdict=OK
      break
    fi
    verdict="$(classify_failure "$err")"
    [ "$verdict" != "UNKNOWN" ] && break
    [ "$attempt" -ge "$VERIFY_ATTEMPTS" ] && break
    printf '  retry %s/%s  %s  (probe failed, not a verdict)\n' \
      "$attempt" "$VERIFY_ATTEMPTS" "$short"
    sleep "$VERIFY_RETRY_SLEEP"
    attempt=$((attempt + 1))
  done

  case "$verdict" in
    OK)
      printf '  OK          %s\n' "$short"
      PASS=$((PASS + 1))
      ;;
    ABSENT)
      if is_allowed_placeholder "$image"; then
        printf '  PLACEHOLDER %s  (allowlisted: never built, cannot be admitted)\n' "$short"
        ALLOWED=$((ALLOWED + 1))
      else
        printf '  ABSENT      %s  <-- declared in gitops but NOT in the registry\n' "$short"
        ABSENT=$((ABSENT + 1))
        ABSENT_IMAGES+=("$image")
      fi
      ;;
    UNATTESTED)
      printf '  UNATTESTED  %s  <-- no valid CycloneDX SBOM attestation\n' "$short"
      UNATTESTED=$((UNATTESTED + 1))
      UNATTESTED_IMAGES+=("$image")
      ;;
    *)
      printf '  UNKNOWN     %s  <-- probe failed %s time(s), NO verdict about this image\n' \
        "$short" "$VERIFY_ATTEMPTS"
      printf '%s\n' "$err" | sed 's/^/                  | /' | tail -5
      UNKNOWN=$((UNKNOWN + 1))
      UNKNOWN_IMAGES+=("$image")
      ;;
  esac
done

FAIL=$((UNATTESTED + ABSENT))

echo
echo "==> Fleet summary: ${PASS} attested / ${UNATTESTED} unattested / ${ABSENT} absent / ${ALLOWED} allowlisted placeholder / ${UNKNOWN} unknown (probe failed) / ${#IMAGES[@]} total"

if [ -n "$FLEET_ATTEST_JSON" ] && command -v jq >/dev/null 2>&1; then
  # `${arr[@]+"${arr[@]}"}` guards bash 3.2's unbound-variable error on an empty array
  # under `set -u` — the all-attested case, which must still emit a valid report.
  _json_arr() {
    # `${arr[@]+"${arr[@]}"}` guards bash 3.2's unbound-variable error on an empty array
    # under `set -u` — the all-attested case, which must still emit a valid report.
    printf '%s\n' ${1+"$@"} | jq -R . | jq -s 'map(select(length > 0))'
  }
  jq -n \
    --argjson total "${#IMAGES[@]}" \
    --argjson attested "$PASS" \
    --argjson unattested "$UNATTESTED" \
    --argjson absent "$ABSENT" \
    --argjson placeholders "$ALLOWED" \
    --argjson unattestedImages "$(_json_arr ${UNATTESTED_IMAGES[@]+"${UNATTESTED_IMAGES[@]}"})" \
    --argjson absentImages "$(_json_arr ${ABSENT_IMAGES[@]+"${ABSENT_IMAGES[@]}"})" \
    --argjson unknown "$UNKNOWN" \
    --argjson unknownImages "$(_json_arr ${UNKNOWN_IMAGES[@]+"${UNKNOWN_IMAGES[@]}"})" \
    --arg scannedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg gateResult "$([ "$FAIL" -gt 0 ] && echo FAIL || { [ "$UNKNOWN" -gt 0 ] && echo INCOMPLETE || echo PASS; })" \
    '{scannedAt: $scannedAt, gateResult: $gateResult, total: $total, attested: $attested,
      unattested: $unattested, absent: $absent, allowlistedPlaceholders: $placeholders,
      unknown: $unknown, unattestedImages: $unattestedImages, absentImages: $absentImages,
      unknownImages: $unknownImages}' \
    > "$FLEET_ATTEST_JSON"
  echo "    JSON report: ${FLEET_ATTEST_JSON}"
fi

if [ "$UNATTESTED" -gt 0 ]; then
  echo
  echo "UNATTESTED (${UNATTESTED}) — pushed, signed, but NO SBOM attestation:"
  for image in "${UNATTESTED_IMAGES[@]}"; do
    echo "  - ${image}"
  done
  echo
  echo "  Each is a LATENT OUTAGE. Its pods run until something reschedules them; then"
  echo "  kyverno (verify-openbank-image-sbom-attestation, Enforce) denies admission and"
  echo "  they can never restart. Rebuild + attest each one BEFORE it reschedules:"
  echo "    openbank-infra/scripts/build-push-service.sh <svc>   # attests via lib/cosign-attest.sh"
fi

if [ "$ABSENT" -gt 0 ]; then
  echo
  echo "ABSENT (${ABSENT}) — declared in gitops but not present in the registry:"
  for image in "${ABSENT_IMAGES[@]}"; do
    echo "  - ${image}"
  done
  echo
  echo "  Not an attestation regression: these can never be admitted at all. Either build+push"
  echo "  the image, or — if this is a deliberate first-registration placeholder — document it"
  echo "  in ${PLACEHOLDER_FILE}."
fi

if [ "$UNKNOWN" -gt 0 ]; then
  echo
  echo "UNKNOWN (${UNKNOWN}) — the probe could not reach a verdict after ${VERIFY_ATTEMPTS} attempt(s):"
  for image in "${UNKNOWN_IMAGES[@]}"; do
    echo "  - ${image}"
  done
  echo
  echo "  This says NOTHING about whether these images are attested — it is a registry,"
  echo "  credential or tooling failure, not a supply-chain finding. Do not report it as a"
  echo "  fleet gap and do not rebuild anything on the strength of it. Re-run; if it persists,"
  echo "  verify one image by digest by hand and treat it as an infrastructure problem:"
  echo "    cosign verify-attestation --key ${COSIGN_KEY} --type cyclonedx <image>@<digest>"
fi

if [ "$FAIL" -gt 0 ]; then
  echo
  echo "FLEET ATTESTATION GATE: FAIL — ${FAIL} of ${#IMAGES[@]} declared image(s) not deployable."
  echo
  echo "Both image-provenance policies are already Enforce in-cluster"
  echo "(verify-openbank-image-signatures; verify-openbank-image-sbom-attestation, graduated"
  echo "2026-07-12), so this is a LATENT OUTAGE, not a graduation blocker: the affected pods"
  echo "keep running until something reschedules them, and are then denied admission and can"
  echo "never restart. Fix before a reschedule, not after"
  echo "(rules.yaml: provenance.enforce_criteria)."
  exit 1
fi

if [ "$UNKNOWN" -gt 0 ]; then
  echo
  echo "FLEET ATTESTATION GATE: INCOMPLETE — ${PASS} verified, ${UNKNOWN} could not be checked."
  echo "  Exit 2 = 'could not run', NOT a verdict. Callers must not treat it as a fleet gap."
  exit 2
fi

echo
echo "FLEET ATTESTATION GATE: PASS — every declared openbank-* image is attested."
[ "$ALLOWED" -gt 0 ] && echo "  (${ALLOWED} allowlisted placeholder(s) skipped — see ${PLACEHOLDER_FILE})"
exit 0
