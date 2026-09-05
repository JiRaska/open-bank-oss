# shellcheck shell=bash
# ---------------------------------------------------------------------------------------
# Shared cosign SBOM-attestation helper (ADR-0029 / ADR-0030 D4).
#
# Source this from any script that pushes an openbank-* image to ECR:
#
#     . "$(dirname "$0")/lib/cosign-attest.sh"
#     cosign_sign_and_attest "$IMAGE" "$PLATFORM"
#
# Why this exists: the attest logic was copy-pasted into some producers and simply
# absent from others, so several fleet images were pushed SIGNED but UNATTESTED.
# kyverno's verify-openbank-image-sbom-attestation ClusterPolicy is Enforce — an
# unattested image is admitted never, so the gap only surfaces when a pod happens to
# reschedule, long after the push that caused it.
#
# Three invariants this helper exists to hold:
#
#   1. --platform is ALWAYS passed to `trivy image`. trivy defaults to linux/amd64 for
#      REMOTE (registry) scans regardless of host arch. Fleet images are built
#      linux/arm64 (Graviton nodes), so an unqualified `trivy image` fails with
#      "no child with platform linux/amd64 in index" — and every caller that only
#      checked the exit code turned that into a skipped attestation.
#   2. A failed attestation is FATAL (return 1). The image is verified with
#      `cosign verify-attestation` after the fact, so "attest exited 0" is not taken
#      on trust. A silently-unattested image is an image that cannot be deployed.
#   3. The SBOM is proven substantive BEFORE it is attested, and the post-attest verify
#      is bound to the envelope THIS run pushed. `cosign attest` APPENDS an envelope to
#      the image's `.att` tag rather than replacing, and `cosign verify-attestation`
#      passes when ANY envelope verifies — so an unqualified verify can be reporting on
#      a previous build's good SBOM while the envelope just pushed is empty. cosign v2
#      offers no "verify the newest envelope" flag (--policy is any-match too), so the
#      binding is done here, on the CycloneDX serialNumber trivy mints per run.
#
# cosign v2 is pinned ON PURPOSE: kyverno 3.2.6 discovers signatures/attestations only
# via the legacy `sha256-<digest>.sig` / `.att` tag scheme. cosign v3 writes OCI 1.1
# referrers kyverno cannot find, so a v3-signed image fails an Enforce policy. Revisit
# once kyverno can verify referrers (ADR-0029, issue #770).
# ---------------------------------------------------------------------------------------

COSIGN_KEY="${COSIGN_KEY:-awskms:///alias/openbank-cosign-signing}"
COSIGN_VERSION="${COSIGN_VERSION:-v2.4.3}"

# Echo a cosign v2.x binary path: reuse one on PATH if it is v2, else fetch the pinned
# release to a cache path. Returns non-zero (and echoes nothing) if no v2 binary can be
# obtained.
resolve_cosign_v2() {
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

# assert_cyclonedx_sbom <sbom-file>
#
# Fail unless the file is a substantive CycloneDX document. trivy can exit 0 having
# written a truncated or component-less BOM (a partial scan, a disk-full write, a broken
# image index); attesting one produces provenance that is technically present and
# substantively worthless — exactly the "silently-bad provenance step" this helper exists
# to stop. Echoes the BOM serialNumber on success; it is unique per trivy run, which is
# what lets the post-attest verify below bind to THIS run's envelope.
assert_cyclonedx_sbom() {
  local sbom="$1" format components serial

  if ! jq -e . "$sbom" >/dev/null 2>&1; then
    echo "ERROR: ${sbom} is not valid JSON — trivy produced a truncated SBOM." >&2
    return 1
  fi
  format="$(jq -r '.bomFormat // empty' "$sbom")"
  if [ "$format" != "CycloneDX" ]; then
    echo "ERROR: ${sbom} has bomFormat='${format}', expected 'CycloneDX'." >&2
    return 1
  fi
  # Type-check before counting: jq's `length` is defined on every type, so a scalar
  # `.components` ("scan_failed", 5) would report a non-zero "count" and sail through.
  components="$(jq -r 'if (.components | type) == "array" then (.components | length) else 0 end' "$sbom")"
  if [ "$components" -lt 1 ]; then
    echo "ERROR: ${sbom} has no components array, or it is empty — refusing to attest" >&2
    echo "       an SBOM that inventories nothing." >&2
    return 1
  fi
  serial="$(jq -r '.serialNumber // empty' "$sbom")"
  if [ -z "$serial" ]; then
    echo "ERROR: ${sbom} carries no serialNumber — the attestation could not be bound" >&2
    echo "       to this run, so it would not be provable. Refusing to attest." >&2
    return 1
  fi

  echo "    SBOM sane: ${components} components, serialNumber=${serial}" >&2
  printf '%s\n' "$serial"
}

# cosign_attest_sbom <image-ref> <platform> [cosign-bin]
#
# Generate a CycloneDX SBOM for the image with trivy, check it is substantive, bind it to
# the image digest with `cosign attest`, then PROVE that this run's envelope landed with
# `cosign verify-attestation`. Returns 0 only if that envelope is verifiable afterwards;
# returns 1 on any failure.
cosign_attest_sbom() {
  local image="$1" platform="$2" bin="${3:-}"
  local sbom serial envelopes

  if [ -z "$image" ] || [ -z "$platform" ]; then
    echo "ERROR: cosign_attest_sbom requires <image> <platform>." >&2
    return 1
  fi

  if [ -z "$bin" ]; then
    bin="$(resolve_cosign_v2 || true)"
  fi
  if [ -z "$bin" ]; then
    echo "ERROR: cosign v2 unavailable — cannot attest ${image}." >&2
    return 1
  fi
  if ! command -v trivy >/dev/null 2>&1; then
    echo "ERROR: trivy unavailable — cannot generate the SBOM for ${image}." >&2
    return 1
  fi
  if ! command -v jq >/dev/null 2>&1; then
    echo "ERROR: jq unavailable — cannot check the SBOM or bind the attestation to it." >&2
    return 1
  fi

  sbom="${TMPDIR:-/tmp}/$(echo "$image" | tr '/:@' '___').cdx.json"

  # --platform is REQUIRED here — see the header note. Do not "simplify" it away.
  echo "==> trivy image --platform ${platform} (cyclonedx) ${image}"
  if ! trivy image --platform "$platform" --format cyclonedx --output "$sbom" "$image"; then
    echo "ERROR: trivy SBOM generation failed for ${image} (platform=${platform})." >&2
    return 1
  fi

  # Check the SBOM BEFORE attesting: a junk predicate that reaches `cosign attest` is
  # permanent — the envelope cannot be un-pushed, only outlived by the next real build.
  serial="$(assert_cyclonedx_sbom "$sbom")" || return 1

  echo "==> cosign attest (cyclonedx) ${image}"
  if ! COSIGN_YES=true "$bin" attest --key "$COSIGN_KEY" --type cyclonedx \
       --predicate "$sbom" "$image"; then
    echo "ERROR: cosign attest failed for ${image}." >&2
    return 1
  fi

  # Never trust `attest` exit 0 alone: verify the attestation is actually discoverable
  # the same way kyverno will discover it at admission.
  echo "==> cosign verify-attestation ${image}"
  if ! envelopes="$(COSIGN_YES=true "$bin" verify-attestation --key "$COSIGN_KEY" \
       --type cyclonedx "$image" 2>/dev/null)"; then
    echo "ERROR: cosign verify-attestation failed for ${image} — the attestation did not land." >&2
    return 1
  fi

  # …and do not trust that a PASS is about this run. verify-attestation prints every
  # envelope that verified, one JSON line each, and exits 0 if ANY of them did; since
  # `attest` appends, an older good envelope covers for a new bad one. Require the
  # serialNumber of the SBOM just generated to appear among them. `try … catch empty`
  # keeps a pre-existing undecodable envelope from failing the run on someone else's
  # behalf — only the absence of OUR serial is fatal.
  if ! printf '%s\n' "$envelopes" | jq -e -s --arg serial "$serial" \
       '[ .[] | try (.payload | @base64d | fromjson | .predicate.serialNumber) catch empty ]
        | index($serial) != null' >/dev/null 2>&1; then
    echo "ERROR: cosign verify-attestation for ${image} verified no envelope carrying this" >&2
    echo "       run's SBOM (serialNumber=${serial}). The attestation that verified belongs" >&2
    echo "       to an earlier build — this image's own provenance did not land." >&2
    return 1
  fi

  echo "    attested + verified SBOM (key=${COSIGN_KEY}, serialNumber=${serial})"
  return 0
}

# cosign_attest_slsa_provenance <image-ref> [cosign-bin]
#
# Attest SLSA build provenance (predicateType https://slsa.dev/provenance/v0.2) for the image:
# which repo, commit, workflow and run built it — the claim the signature and SBOM attestation do
# NOT carry (they prove "ours" and "known contents", not "built by OUR CI from THIS commit",
# ADR-0030 D4, #8590 #14). The predicate is built by build-slsa-provenance.py from the GITHUB_*
# env, attested with the same KMS key, and then PROVEN to have landed with verify-attestation
# bound to this run's buildInvocationId (the .att tag is append-only — an unqualified verify can
# be reporting on an older envelope, see cosign_attest_sbom).
#
# GitHub-Actions-only: outside CI (a break-glass manual push) there is no trustworthy invocation
# record, so the function SKIPS with a notice and returns 0. In CI a failure is FATAL (return 1).
cosign_attest_slsa_provenance() {
  local image="$1" bin="${2:-}"
  local predicate invocation_id envelopes

  if [ "${GITHUB_ACTIONS:-}" != "true" ]; then
    echo "    skipping SLSA provenance attestation for ${image} — not in GitHub Actions;"
    echo "    a manual push has no CI invocation to attest (signature + SBOM still gate it)."
    return 0
  fi

  if [ -z "$image" ]; then
    echo "ERROR: cosign_attest_slsa_provenance requires <image>." >&2
    return 1
  fi
  if [ -z "$bin" ]; then
    bin="$(resolve_cosign_v2 || true)"
  fi
  if [ -z "$bin" ]; then
    echo "ERROR: cosign v2 unavailable — cannot attest SLSA provenance for ${image}." >&2
    return 1
  fi

  local builder
  builder="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/build-slsa-provenance.py"
  predicate="${TMPDIR:-/tmp}/$(echo "$image" | tr '/:@' '___').slsa.json"

  echo "==> build SLSA provenance predicate ${image}"
  if ! python3 "$builder" --output "$predicate"; then
    echo "ERROR: SLSA predicate build failed for ${image}." >&2
    return 1
  fi
  invocation_id="$(jq -r '.metadata.buildInvocationId' "$predicate")"

  echo "==> cosign attest (slsaprovenance) ${image}"
  if ! COSIGN_YES=true "$bin" attest --key "$COSIGN_KEY" --type slsaprovenance \
       --predicate "$predicate" "$image"; then
    echo "ERROR: cosign attest (slsaprovenance) failed for ${image}." >&2
    return 1
  fi

  echo "==> cosign verify-attestation (slsaprovenance) ${image}"
  if ! envelopes="$(COSIGN_YES=true "$bin" verify-attestation --key "$COSIGN_KEY" \
       --type slsaprovenance "$image" 2>/dev/null)"; then
    echo "ERROR: cosign verify-attestation (slsaprovenance) failed for ${image} — the attestation did not land." >&2
    return 1
  fi

  # Bind the verify to THIS run: require our buildInvocationId among the verified envelopes
  # (same append-only .att trap as the SBOM binding above — any-match is not proof).
  # CASING TRAP (measured against cosign v2.4.3, run 33984244416 + local repro): cosign does
  # NOT embed the predicate verbatim — it parses it into its Go struct and re-marshals, and
  # the struct's json tag is `buildInvocationID` (capital D), not the SLSA v0.2 spec spelling
  # `buildInvocationId`. The envelope therefore carries `buildInvocationID` and a binding on
  # the spec spelling matches nothing. Accept both spellings.
  if ! printf '%s\n' "$envelopes" | jq -e -s --arg id "$invocation_id" \
       '[ .[] | try (.payload | @base64d | fromjson
          | .predicate.metadata | (.buildInvocationId // .buildInvocationID)) catch empty ]
        | index($id) != null' >/dev/null 2>&1; then
    echo "ERROR: cosign verify-attestation for ${image} verified no envelope carrying this" >&2
    echo "       run's provenance (buildInvocationId=${invocation_id})." >&2
    return 1
  fi

  echo "    attested + verified SLSA provenance (key=${COSIGN_KEY}, run=${invocation_id})"
  return 0
}

# cosign_sign_and_attest <image-ref> <platform>
#
# The full provenance pass a producer needs: sign the image, then attest its SBOM.
# HARD FAILS (return 1) if either step does not land — kyverno's signature policy AND
# its SBOM-attestation policy are both Enforce, so an image missing either is
# undeployable and must fail the build that produced it, loudly, at push time.
cosign_sign_and_attest() {
  local image="$1" platform="$2"
  local bin

  bin="$(resolve_cosign_v2 || true)"
  if [ -z "$bin" ]; then
    echo "ERROR: cosign v2 unavailable — ${image} would be pushed UNSIGNED + UNATTESTED" >&2
    echo "       and rejected at admission. Install cosign v2.x or set COSIGN_VERSION." >&2
    return 1
  fi

  echo "==> cosign sign ${image} (tag-based, $("$bin" version 2>/dev/null | awk '/GitVersion/{print $2}'))"
  if ! COSIGN_YES=true "$bin" sign --key "$COSIGN_KEY" "$image"; then
    echo "ERROR: cosign sign failed for ${image}." >&2
    return 1
  fi
  echo "    signed (key=${COSIGN_KEY})"

  cosign_attest_sbom "$image" "$platform" "$bin" || return 1
  cosign_attest_slsa_provenance "$image" "$bin" || return 1
  return 0
}
