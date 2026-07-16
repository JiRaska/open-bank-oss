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
# Two invariants this helper exists to hold:
#
#   1. --platform is ALWAYS passed to `trivy image`. trivy defaults to linux/amd64 for
#      REMOTE (registry) scans regardless of host arch. Fleet images are built
#      linux/arm64 (Graviton nodes), so an unqualified `trivy image` fails with
#      "no child with platform linux/amd64 in index" — and every caller that only
#      checked the exit code turned that into a skipped attestation.
#   2. A failed attestation is FATAL (return 1). The image is verified with
#      `cosign verify-attestation` after the fact, so "attest exited 0" is not taken
#      on trust. A silently-unattested image is an image that cannot be deployed.
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

# cosign_attest_sbom <image-ref> <platform> [cosign-bin]
#
# Generate a CycloneDX SBOM for the image with trivy, bind it to the image digest with
# `cosign attest`, then PROVE it landed with `cosign verify-attestation`. Returns 0 only
# if the attestation is verifiable afterwards; returns 1 on any failure.
cosign_attest_sbom() {
  local image="$1" platform="$2" bin="${3:-}"
  local sbom

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

  sbom="${TMPDIR:-/tmp}/$(echo "$image" | tr '/:@' '___').cdx.json"

  # --platform is REQUIRED here — see the header note. Do not "simplify" it away.
  echo "==> trivy image --platform ${platform} (cyclonedx) ${image}"
  if ! trivy image --platform "$platform" --format cyclonedx --output "$sbom" "$image"; then
    echo "ERROR: trivy SBOM generation failed for ${image} (platform=${platform})." >&2
    return 1
  fi

  echo "==> cosign attest (cyclonedx) ${image}"
  if ! COSIGN_YES=true "$bin" attest --key "$COSIGN_KEY" --type cyclonedx \
       --predicate "$sbom" "$image"; then
    echo "ERROR: cosign attest failed for ${image}." >&2
    return 1
  fi

  # Never trust `attest` exit 0 alone: verify the attestation is actually discoverable
  # the same way kyverno will discover it at admission.
  echo "==> cosign verify-attestation ${image}"
  if ! COSIGN_YES=true "$bin" verify-attestation --key "$COSIGN_KEY" --type cyclonedx \
       "$image" >/dev/null 2>&1; then
    echo "ERROR: cosign verify-attestation failed for ${image} — the attestation did not land." >&2
    return 1
  fi

  echo "    attested + verified SBOM (key=${COSIGN_KEY})"
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
  return 0
}
