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
  # `cases` is the SUBJECT COUNT this gate reports (gates.yaml min_subjects). A checker whose
  # corpus is its own fixtures examines nothing the day someone deletes them, and the floor is
  # what makes that a failure instead of a faster green.
  local failures=0 got cases=0
  check_case() {
    local name="$1" expected="$2" input="$3"
    got="$(classify_failure "$input")"
    cases=$((cases + 1))
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

  # ---------------------------------------------------------------------------------------
  # END-TO-END: the classifier is only half the fix. What the caller acts on is the EXIT
  # CODE, and no PR can summon an ECR throttle to prove exit 2 is reachable — so the whole
  # loop is driven here against a stub `cosign` that fails per-image in each way. Without
  # this, the exit-2 branch is code nobody has ever run, which is the repo's oldest CI rule
  # (a gate that has only ever passed is unfalsified) applied to the gate's own plumbing.
  # ---------------------------------------------------------------------------------------
  local tmp stub reg
  tmp="$(mktemp -d)"
  reg="$ECR_REGISTRY"
  mkdir -p "$tmp/gitops"
  stub="$tmp/cosign"
  cat > "$stub" <<'STUB'
#!/usr/bin/env bash
# Stub cosign: version says v2 (the script refuses anything else), and each fixture image
# fails the way its name declares. Deliberately writes to stderr, as the real one does.
if [ "$1" = version ]; then echo "GitVersion: v2.4.3"; exit 0; fi
img="${!#}"
case "$img" in
  *fixture-ok*)     echo "Verification for $img -- The signatures were verified"; exit 0 ;;
  *fixture-gone*)   echo "Error: MANIFEST_UNKNOWN: manifest unknown" >&2; exit 1 ;;
  *fixture-bare*)   echo "Error: no matching attestations:" >&2; exit 1 ;;
  *fixture-flaky*)  echo "Error: TOOMANYREQUESTS: Rate exceeded" >&2; exit 1 ;;
esac
echo "Error: stub reached with an unexpected image: $img" >&2; exit 1
STUB
  chmod +x "$stub"

  run_fixture() {
    local name="$1" expected_exit="$2" expected_summary="$3"; shift 3
    local out code
    cases=$((cases + 1))
    : > "$tmp/gitops/images.yaml"
    for img in "$@"; do
      printf 'image: %s/%s\n' "$reg" "$img" >> "$tmp/gitops/images.yaml"
    done
    # The threshold is passed EXPLICITLY rather than inherited: `VAR=x run_fixture` sets it on
    # the function, not on the grandchild process, so an inherited value would silently be the
    # default and the systemic case would prove nothing.
    out="$(GITOPS_DIR="$tmp/gitops" COSIGN_BIN="$stub" PLACEHOLDER_FILE="$tmp/none.txt" \
           VERIFY_ATTEMPTS=2 VERIFY_RETRY_SLEEP=0 FLEET_ATTEST_JSON="" \
           SYSTEMIC_UNKNOWN_THRESHOLD="${fixture_threshold:-99}" \
           bash "$SELF" 2>&1)"
    code=$?
    # Both failure branches print what the run ACTUALLY produced, not only what was wanted.
    # Everything needed to explain a failure exists in $out at this moment and nowhere after it:
    # an assertion that records its expectation and discards the observation leaves the next
    # reader with a re-run as their only move, which is how an intermittent gate stays
    # undiagnosed (#4918).
    fixture_diagnostics() {
      local actual
      actual="$(printf '%s' "$out" | grep -F '==> Fleet summary:' || true)"
      if [ -n "$actual" ]; then
        printf '        actual summary: %s\n' "${actual#*==> Fleet summary: }"
      else
        # No summary at all is a different fault from a wrong one — the script exited before it
        # counted anything — so say which of the two happened rather than printing nothing.
        printf '        no summary line was produced; last 20 lines of output:\n'
        printf '%s\n' "$out" | tail -20 | sed 's/^/          | /'
      fi
    }
    if [ "$code" != "$expected_exit" ]; then
      printf '  FAIL: %s — expected exit %s, got %s\n' "$name" "$expected_exit" "$code"
      fixture_diagnostics
      failures=$((failures + 1))
      return
    fi
    # Do not use `printf | grep -q` here: with `pipefail`, grep may exit as
    # soon as it finds the expected summary and leave printf with SIGPIPE.
    # That turns a correct, sufficiently large self-test output into a false
    # failure on a faster CI runner. Feed grep directly so this assertion is
    # about the summary, not pipe scheduling.
    if ! grep -qF "$expected_summary" <<< "$out"; then
      printf '  FAIL: %s — exit %s correct, but summary missing\n' "$name" "$code"
      printf '        expected summary: %s\n' "$expected_summary"
      fixture_diagnostics
      failures=$((failures + 1))
      return
    fi
    LAST_OUT="$out"
    printf '  ok: %s (exit %s)\n' "$name" "$code"
  }

  # Every declared image attested -> 0.
  run_fixture "all attested -> exit 0" 0 \
    "1 attested / 0 unattested / 0 absent / 0 allowlisted placeholder / 0 unknown" \
    "openbank-fixture-ok:t"
  # A real gap -> 1. Both fatal classes, so the summary carries each count.
  run_fixture "unattested + absent -> exit 1" 1 \
    "1 attested / 1 unattested / 1 absent / 0 allowlisted placeholder / 0 unknown" \
    "openbank-fixture-ok:t" "openbank-fixture-bare:t" "openbank-fixture-gone:t"
  # ONLY a probe failure -> 2, and crucially NOT 1: this is the case that used to be
  # published as a fleet gap, and the exit code is the only thing the caller reads.
  run_fixture "probe failure only -> exit 2 (not 1)" 2 \
    "1 attested / 0 unattested / 0 absent / 0 allowlisted placeholder / 1 unknown" \
    "openbank-fixture-ok:t" "openbank-fixture-flaky:t"
  # A REAL gap alongside a probe failure must still be 1 — "could not run" never masks a
  # verdict that was reached, or an unlucky throttle would downgrade a live outage.
  run_fixture "gap + probe failure -> exit 1 (gap wins)" 1 \
    "0 attested / 1 unattested / 0 absent / 0 allowlisted placeholder / 1 unknown" \
    "openbank-fixture-bare:t" "openbank-fixture-flaky:t"
  # A TOTAL outage must short-circuit the retries rather than multiply them past the job
  # timeout — a killed job reports no exit code at all, and the caller then cannot tell a gap
  # from a probe failure, which is the whole distinction this file exists to keep.
  fixture_threshold=3
  run_fixture "systemic outage -> retries off, still exit 2" 2 \
    "images in a row could not be probed: this is systemic" \
    "openbank-fixture-flaky:1" "openbank-fixture-flaky:2" "openbank-fixture-flaky:3" \
    "openbank-fixture-flaky:4"
  # ...and the SHORT-CIRCUIT itself, which the message above cannot prove: with the threshold
  # at 3, the first three images retry once each and the fourth must not retry at all. Asserted
  # as a count, because a fixture that only greps the banner passes with the retry suppression
  # deleted — measured, not assumed.
  cases=$((cases + 1))
  retries="$(printf '%s' "${LAST_OUT:-}" | grep -c '  retry ' || true)"
  if [ "$retries" -eq 3 ]; then
    printf '  ok: systemic outage stops retrying (3 retries, not 4)\n'
  else
    printf '  FAIL: systemic outage stops retrying — expected 3 retry lines, got %s\n' "$retries"
    failures=$((failures + 1))
  fi
  fixture_threshold=""

  # -------------------------------------------------------------------------------------
  # STALENESS, driven end to end through --check-placeholders (no cosign, no registry).
  # Three cases, because the check has to DISCRIMINATE: an entry whose image is still
  # declared must stay green, an entry whose image is gone must go red, and the mixed case
  # must be red. A check that merely fails on any non-empty allowlist would pass the first
  # two of these and be useless — it would reject every legitimate first registration.
  # -------------------------------------------------------------------------------------
  run_placeholder_fixture() {
    local name="$1" expected_exit="$2" expected_text="$3" allow="$4"; shift 4
    local out code
    cases=$((cases + 1))
    : > "$tmp/gitops/images.yaml"
    for img in "$@"; do printf 'image: %s/%s\n' "$reg" "$img" >> "$tmp/gitops/images.yaml"; done
    printf '# fixture allowlist\n' > "$tmp/placeholders.txt"
    for img in $allow; do printf '%s/%s\n' "$reg" "$img" >> "$tmp/placeholders.txt"; done
    out="$(GITOPS_DIR="$tmp/gitops" PLACEHOLDER_FILE="$tmp/placeholders.txt" \
           bash "$SELF" --check-placeholders 2>&1)"
    code=$?
    if [ "$code" != "$expected_exit" ]; then
      printf '  FAIL: %s — expected exit %s, got %s\n' "$name" "$expected_exit" "$code"
      printf '%s\n' "$out" | tail -10 | sed 's/^/          | /'
      failures=$((failures + 1))
      return
    fi
    if ! grep -qF "$expected_text" <<< "$out"; then
      printf '  FAIL: %s — exit %s correct, but expected text missing: %s\n' \
        "$name" "$code" "$expected_text"
      printf '%s\n' "$out" | tail -10 | sed 's/^/          | /'
      failures=$((failures + 1))
      return
    fi
    printf '  ok: %s (exit %s)\n' "$name" "$code"
  }

  # A genuine, still-declared placeholder — must NOT be flagged.
  run_placeholder_fixture "live placeholder is not stale -> exit 0" 0 \
    "1 entr(y/ies), 0 stale" "openbank-fixture-new:sandbox-init" \
    "openbank-fixture-new:sandbox-init" "openbank-fixture-ok:t"
  # An entry whose image left gitops — the class that rotted for five entries.
  run_placeholder_fixture "entry whose image is gone -> exit 1" 1 \
    "STALE     openbank-fixture-new:sandbox-init" "openbank-fixture-new:sandbox-init" \
    "openbank-fixture-new:sandbox-real" "openbank-fixture-ok:t"
  # Mixed: one live, one stale. Red, and only the stale one is named.
  run_placeholder_fixture "one live + one stale -> exit 1" 1 \
    "1 stale" "openbank-fixture-new:sandbox-init openbank-fixture-gone:sandbox-init" \
    "openbank-fixture-new:sandbox-init" "openbank-fixture-ok:t"

  rm -rf "$tmp"

  printf 'SUBJECTS=%s\n' "$cases"
  if [ "$failures" -gt 0 ]; then
    printf 'selftest FAILED (%s case(s))\n' "$failures"
    return 1
  fi
  printf 'selftest OK — classifier proven on all three classes, and exit 0/1/2 driven end to end.\n'
  return 0
}

# ---------------------------------------------------------------------------------------
# The LIVE control the stub cannot give: the UNATTESTED branch is matched on cosign's own
# wording, so a wording change in a future cosign silently re-routes every real gap to
# UNKNOWN — the gate would then exit 2 forever, never file a gap, and read as an infra
# problem. Ask the real binary, against a real image in the real registry, for a verdict we
# know is negative: the same image with an attestation type it has never carried. If that
# does not classify as UNATTESTED, the vocabulary has moved and the gate is blind.
# No side effects — verify-attestation is read-only.
# ---------------------------------------------------------------------------------------
vocabulary_control() {
  local image="$1" err verdict
  printf '==> Vocabulary control: %s with --type spdx (never attested; must read UNATTESTED)\n' \
    "${image#"${ECR_REGISTRY}"/}"
  if err="$(COSIGN_YES=true "$COSIGN_BIN_RESOLVED" verify-attestation \
              --key "$COSIGN_KEY" --type spdx "$image" 2>&1)"; then
    echo "ERROR: control image verified against a type it should not carry — the control is" >&2
    echo "       no longer negative. Pick a type this fleet genuinely never attests." >&2
    return 1
  fi
  verdict="$(classify_failure "$err")"
  if [ "$verdict" != "UNATTESTED" ]; then
    echo "ERROR: cosign's not-attested wording no longer classifies as UNATTESTED (got ${verdict})." >&2
    echo "       Every real gap would now be reported as UNKNOWN and the gate would never fail" >&2
    echo "       on one. Update classify_failure() against this output:" >&2
    printf '%s\n' "$err" | sed 's/^/       | /' | tail -5 >&2
    return 1
  fi
  printf '    OK — a known-negative verdict still reads UNATTESTED.\n'
  return 0
}

SELF="$0"
MODE=verify
case "${1:-}" in
  --selftest | --self-test)
    selftest
    exit $?
    ;;
  # Needs cosign + a real registry, so it runs as its own step in fleet-attestation.yml
  # rather than inside the fleet loop — a vocabulary failure must be reported as itself,
  # not as 62 UNKNOWN images.
  --vocabulary-control)
    MODE=vocabulary-control
    ;;
  # Staleness only: needs GITOPS_DIR and nothing else — no cosign, no ECR credentials — so
  # it is reachable on an ordinary PR, which is the whole point (see check_placeholder_staleness).
  --check-placeholders)
    MODE=check-placeholders
    ;;
  "") ;;
  *)
    echo "ERROR: unknown argument: $1" >&2
    echo "       usage: $0 [--selftest|--self-test|--vocabulary-control|--check-placeholders]" >&2
    exit 1
    ;;
esac

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

COSIGN_BIN_RESOLVED=""
if [ "$MODE" != check-placeholders ]; then
  COSIGN_BIN_RESOLVED="$(resolve_cosign_v2 || true)"
fi
if [ "$MODE" != check-placeholders ] && [ -z "$COSIGN_BIN_RESOLVED" ]; then
  echo "ERROR: cosign v2 unavailable — cannot verify fleet attestations." >&2
  echo "       Install cosign v2.x, set COSIGN_BIN, or set COSIGN_VERSION." >&2
  exit 1
fi

echo "==> Enumerating openbank-* images declared in ${GITOPS_DIR}"
if [ "$MODE" != check-placeholders ]; then
  echo "    cosign:   $("$COSIGN_BIN_RESOLVED" version 2>/dev/null | awk '/GitVersion/{print $2}')"
  echo "    key:      ${COSIGN_KEY}"
fi
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

if [ "$MODE" = vocabulary-control ]; then
  vocabulary_control "${IMAGES[0]}"
  exit $?
fi

# ---------------------------------------------------------------------------------------
# STALENESS — the direction this gate could not fail in until #7740.
#
# The allowlist above suppresses the ABSENT verdict for an image ref. It could only ever be
# consulted for a ref that is DECLARED in gitops, so an entry whose ref is no longer declared
# anywhere suppresses nothing: it is invisible from every angle a run reports. The file's own
# header says "Remove an entry the moment the service is genuinely built + pushed", and that
# instruction had no enforcement — five of its five entries had outlived their subject (their
# pins had all moved to real built tags: finrep sandbox-3b62a4a5, vop sandbox-3b62a4a5,
# delegation/referral sandbox-c3f21ee6, case-coordinator sandbox-e96c8e41), and two of them
# additionally justified themselves with prose that main contradicts (vop's "no Dockerfile,
# absent from auto-deploy's ALL_SERVICES" — it has both).
#
# This is the repo's established baseline convention, which this one file sat outside of: a
# declaration must fail in BOTH directions, or it outlives its subject and the reader inherits
# a false statement about the fleet. Same rule as
# `rules.yaml: lineage_code_audit` ("an allowlist entry below whose edge is no longer declared
# ... is itself a finding — delete it") and deploy-coverage-baseline.txt's STALE_BASELINE.
#
# Note what it deliberately does NOT assert: nothing here says the image is absent from ECR.
# A ref that is still declared in gitops stays allowlisted whether or not it has since been
# built — the ABSENT/OK verdict from cosign is what decides that, and a fresh, genuinely
# unbuilt placeholder must pass this check or it would fail every first registration.
# ---------------------------------------------------------------------------------------
check_placeholder_staleness() {
  local stale=() entry declared=0
  [ -f "$PLACEHOLDER_FILE" ] || { printf '==> No placeholder allowlist at %s — nothing to check.\n' "$PLACEHOLDER_FILE"; return 0; }
  while IFS= read -r entry; do
    [ -n "$entry" ] || continue
    declared=$((declared + 1))
    local found=0 img
    for img in "${IMAGES[@]}"; do
      [ "$img" = "$entry" ] && { found=1; break; }
    done
    if [ "$found" -eq 1 ]; then
      printf '  DECLARED  %s\n' "${entry#"${ECR_REGISTRY}"/}"
    else
      printf '  STALE     %s  <-- allowlisted, but no longer declared in %s\n' \
        "${entry#"${ECR_REGISTRY}"/}" "$GITOPS_DIR"
      stale+=("$entry")
    fi
  done < <(grep -vE '^[[:space:]]*(#|$)' "$PLACEHOLDER_FILE")

  printf '\n==> Placeholder allowlist: %s entr(y/ies), %s stale\n' "$declared" "${#stale[@]}"
  if [ "${#stale[@]}" -gt 0 ]; then
    echo
    echo "STALE PLACEHOLDER ALLOWLIST ENTR(Y/IES) (${#stale[@]}):"
    for entry in "${stale[@]}"; do echo "  - ${entry}"; done
    echo
    echo "  Each of these suppresses nothing: the ref is not declared under ${GITOPS_DIR}, so"
    echo "  the ABSENT branch can never be reached for it. The entry survives only as prose"
    echo "  asserting something about the fleet that is no longer true — which is how five"
    echo "  entries outlived their own 'Remove an entry the moment the service is genuinely"
    echo "  built + pushed' instruction. DELETE the entry (and, if its pin has moved to a real"
    echo "  built tag, nothing else is needed)."
    return 1
  fi
  return 0
}

is_allowed_placeholder() {
  [ -f "$PLACEHOLDER_FILE" ] || return 1
  grep -vE '^[[:space:]]*(#|$)' "$PLACEHOLDER_FILE" | grep -qxF "$1"
}

echo "==> Placeholder allowlist staleness (${PLACEHOLDER_FILE})"
if ! check_placeholder_staleness; then
  STALE_PLACEHOLDERS=1
else
  STALE_PLACEHOLDERS=0
fi
echo

if [ "$MODE" = check-placeholders ]; then
  [ "$STALE_PLACEHOLDERS" -eq 0 ] || exit 1
  echo "PLACEHOLDER ALLOWLIST: PASS — every entry still names an image declared in ${GITOPS_DIR}."
  exit 0
fi

PASS=0
UNATTESTED=0
ABSENT=0
ALLOWED=0
UNKNOWN=0
UNATTESTED_IMAGES=()
ABSENT_IMAGES=()
UNKNOWN_IMAGES=()
CONSECUTIVE_UNKNOWN=0
RETRIES_DISABLED=0

# Retry is per-image, so a TOTAL registry outage would multiply: 62 images x (attempts-1) x
# sleep, on top of every call's own latency, which overruns the job timeout — and a killed
# job produces no exit code at all, so the careful 1-vs-2 distinction is lost exactly when it
# matters most. Retrying is worth it for a flaky registry and worthless for a dead one, so
# after this many images in a row have failed the probe, stop retrying and let the run finish
# and report exit 2 honestly.
SYSTEMIC_UNKNOWN_THRESHOLD="${SYSTEMIC_UNKNOWN_THRESHOLD:-5}"

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
    [ "$RETRIES_DISABLED" -eq 1 ] && break
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

  if [ "$verdict" = "UNKNOWN" ]; then
    CONSECUTIVE_UNKNOWN=$((CONSECUTIVE_UNKNOWN + 1))
    if [ "$RETRIES_DISABLED" -eq 0 ] && [ "$CONSECUTIVE_UNKNOWN" -ge "$SYSTEMIC_UNKNOWN_THRESHOLD" ]; then
      RETRIES_DISABLED=1
      printf '  ---- %s images in a row could not be probed: this is systemic, not flaky.\n' \
        "$CONSECUTIVE_UNKNOWN"
      printf '       Retries OFF for the rest of the run so it finishes inside the job timeout —\n'
      printf '       a killed job reports NO exit code, which loses the gap-vs-probe distinction.\n'
    fi
  else
    CONSECUTIVE_UNKNOWN=0
  fi
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

if [ "$STALE_PLACEHOLDERS" -gt 0 ] && [ "$FAIL" -eq 0 ]; then
  echo
  echo "FLEET ATTESTATION GATE: FAIL — every declared image is attested, but the placeholder"
  echo "allowlist carries entr(y/ies) whose image is no longer declared. Kept a separate"
  echo "sentence from the image classes above on purpose: nothing is undeployable, the"
  echo "BASELINE is wrong, and the fix is a file deletion rather than a rebuild."
  exit 1
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
