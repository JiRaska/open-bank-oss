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
# TWO FAILURE CLASSES, kept distinct on purpose:
#   UNATTESTED — the image IS in the registry but carries no valid attestation. This is the
#                outage class: pods run now, die forever on the next reschedule. Always fatal.
#   ABSENT     — the image is not in the registry at all (a first-registration placeholder
#                tag that was never built). Also broken, but a different bug with a different
#                fix, and it is NOT an attestation regression. Fatal unless the image is
#                listed in the placeholder allowlist below.
# Conflating the two would make this gate permanently red on a known placeholder — and a
# permanently-red check is one nobody reads. That is exactly how sbom-drift-scanner's
# `sepa-payment: no-pod-found` sat unactioned on the morning of the outage.
#
# Usage:
#   .github/scripts/check-fleet-attestations.sh              # verify the whole fleet
#   COSIGN_BIN=/path/to/cosign-v2 ... check-fleet-attestations.sh
#   FLEET_ATTEST_JSON=out.json ...                           # also emit a JSON report
#
# Requires: AWS credentials with ECR read access, cosign v2.x, jq (report only).
# Read-only: never pushes, retags, signs, or mutates any image.
# ---------------------------------------------------------------------------------------
set -uo pipefail

GITOPS_DIR="${GITOPS_DIR:-openbank-infra/gitops}"
PLACEHOLDER_FILE="${PLACEHOLDER_FILE:-.github/scripts/fleet-attestation-placeholders.txt}"
COSIGN_KEY="${COSIGN_KEY:-awskms:///alias/openbank-cosign-signing}"
COSIGN_VERSION="${COSIGN_VERSION:-v2.4.3}"
ECR_REGISTRY="${ECR_REGISTRY:-265175468565.dkr.ecr.eu-north-1.amazonaws.com}"
FLEET_ATTEST_JSON="${FLEET_ATTEST_JSON:-}"

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
UNATTESTED_IMAGES=()
ABSENT_IMAGES=()

for image in "${IMAGES[@]}"; do
  short="${image#"${ECR_REGISTRY}"/}"
  if err="$(COSIGN_YES=true "$COSIGN_BIN_RESOLVED" verify-attestation \
              --key "$COSIGN_KEY" --type cyclonedx "$image" 2>&1)"; then
    printf '  OK          %s\n' "$short"
    PASS=$((PASS + 1))
    continue
  fi

  # Distinguish "not in the registry" from "in the registry, not attested". cosign surfaces
  # the former as a registry NAME_UNKNOWN / MANIFEST_UNKNOWN / 404 from the manifest GET.
  if printf '%s' "$err" | grep -qE 'NAME_UNKNOWN|MANIFEST_UNKNOWN|does not exist|not found|404'; then
    if is_allowed_placeholder "$image"; then
      printf '  PLACEHOLDER %s  (allowlisted: never built, cannot be admitted)\n' "$short"
      ALLOWED=$((ALLOWED + 1))
    else
      printf '  ABSENT      %s  <-- declared in gitops but NOT in the registry\n' "$short"
      ABSENT=$((ABSENT + 1))
      ABSENT_IMAGES+=("$image")
    fi
    continue
  fi

  printf '  UNATTESTED  %s  <-- no valid CycloneDX SBOM attestation\n' "$short"
  UNATTESTED=$((UNATTESTED + 1))
  UNATTESTED_IMAGES+=("$image")
done

FAIL=$((UNATTESTED + ABSENT))

echo
echo "==> Fleet summary: ${PASS} attested / ${UNATTESTED} unattested / ${ABSENT} absent / ${ALLOWED} allowlisted placeholder / ${#IMAGES[@]} total"

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
    --arg scannedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg gateResult "$([ "$FAIL" -gt 0 ] && echo FAIL || echo PASS)" \
    '{scannedAt: $scannedAt, gateResult: $gateResult, total: $total, attested: $attested,
      unattested: $unattested, absent: $absent, allowlistedPlaceholders: $placeholders,
      unattestedImages: $unattestedImages, absentImages: $absentImages}' \
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

if [ "$FAIL" -gt 0 ]; then
  echo
  echo "FLEET ATTESTATION GATE: FAIL — ${FAIL} of ${#IMAGES[@]} declared image(s) not deployable."
  echo
  echo "No image-provenance policy may graduate Audit -> Enforce while this gate fails"
  echo "(rules.yaml: provenance.enforce_criteria)."
  exit 1
fi

echo
echo "FLEET ATTESTATION GATE: PASS — every declared openbank-* image is attested."
[ "$ALLOWED" -gt 0 ] && echo "  (${ALLOWED} allowlisted placeholder(s) skipped — see ${PLACEHOLDER_FILE})"
exit 0
