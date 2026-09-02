#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# Build & push the openbank-admin-ui image to ECR with build provenance wired in.
#
# The Dockerfile bakes BUILD_VERSION / BUILD_GIT_SHA / BUILD_DATE as build args and
# re-publishes them at runtime via /api/build-info (the version chip in the UI).
# Built ad-hoc without these args, a deployed UI shows "dev / unknown" — which is
# wrong for an auditable banking control plane (you cannot tell which commit is
# live). This script is the single reproducible entry point that wires them, so
# every pushed image is traceable to an exact commit.
#
# Flow (GitOps): build (linux/arm64, the sandbox node arch) -> push sandbox-<sha>
# -> bump the gitops manifest image tag -> commit & let ArgoCD sync. Pass
# --no-bump to only build & push (e.g. a throwaway test image).
#
# Usage:
#   openbank-infra/scripts/build-push-admin-ui.sh [--no-bump] [--no-sbom] [--platform <p>]
#
#   --no-sbom  skip ./gradlew sbomAll and reuse whatever build/reports/bom.json
#              files already exist (fast UI-only iteration).
#
# Env overrides (defaults match the live sandbox manifest):
#   ECR_REGISTRY  265175468565.dkr.ecr.eu-north-1.amazonaws.com
#   ECR_REPO      openbank-admin-ui
#   AWS_REGION    eu-north-1
#   AWS_PROFILE   openbank
#   ADMIN_UI_IMAGE_TAG_SUFFIX  optional immutable CI retry suffix (`run<id>`)
#   ADMIN_UI_BUILDX_TIMEOUT_SECONDS  CI-only upper bound for the image build (default: 1440)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

ECR_REGISTRY="${ECR_REGISTRY:-265175468565.dkr.ecr.eu-north-1.amazonaws.com}"
ECR_REPO="${ECR_REPO:-openbank-admin-ui}"
AWS_REGION="${AWS_REGION:-eu-north-1}"
export AWS_PROFILE="${AWS_PROFILE:-openbank}"
PLATFORM="linux/arm64"
BUMP_MANIFEST=1
GEN_SBOM=1
ECR_LOGIN=1
MANIFEST="openbank-infra/gitops/components/admin-ui/admin-ui.yaml"

while [ $# -gt 0 ]; do
  case "$1" in
    --no-bump)    BUMP_MANIFEST=0; shift ;;
    --no-sbom)    GEN_SBOM=0; shift ;;
    --no-login)   ECR_LOGIN=0; shift ;;
    --scan-infra) SCAN_INFRA=1; shift ;;
    --platform)   PLATFORM="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

# Provenance: version from version.txt (ADR-0029 release_invariant — version.txt is
# the single source of truth, the admin-ui analog of quarkus.application.version),
# short sha + clean/dirty marker from git, UTC build date. A dirty tree gets a
# "-dirty" suffix so an uncommitted image can never masquerade as a clean commit.
BUILD_VERSION="$(tr -d '[:space:]' < ./openbank-admin-ui/version.txt)"
# Enforce the release_invariant locally: version.txt MUST equal package.json:version
# (the runtime-readable version). A drift here means a release artifact whose UI chip
# would disagree with its declared SemVer — fail fast rather than ship the mismatch.
PKG_VERSION="$(node -p "require('./openbank-admin-ui/package.json').version")"
if [ "${BUILD_VERSION}" != "${PKG_VERSION}" ]; then
  echo "ERROR: version.txt (${BUILD_VERSION}) != package.json version (${PKG_VERSION})." >&2
  echo "       Run /bump admin-ui to move both together (ADR-0029 release_invariant)." >&2
  exit 1
fi
GIT_SHA="$(git rev-parse --short HEAD)"
if ! git diff --quiet HEAD -- openbank-admin-ui 2>/dev/null; then
  GIT_SHA="${GIT_SHA}-dirty"
fi
BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
# A workflow_dispatch may rebuild the same commit after newly available evidence
# has been staged. ECR tags are immutable, so it must use a distinct, still
# commit-derived tag instead of attempting to overwrite the original image.
TAG_SUFFIX="${ADMIN_UI_IMAGE_TAG_SUFFIX:-}"
if [ -n "${TAG_SUFFIX}" ] && ! [[ "${TAG_SUFFIX}" =~ ^run[0-9]+$ ]]; then
  echo "ERROR: ADMIN_UI_IMAGE_TAG_SUFFIX must be empty or run<GitHub run id>." >&2
  exit 2
fi
TAG="sandbox-${GIT_SHA}${TAG_SUFFIX:+-${TAG_SUFFIX}}"
IMAGE="${ECR_REGISTRY}/${ECR_REPO}:${TAG}"

echo "==> admin-ui image provenance"
echo "    version : ${BUILD_VERSION}"
echo "    git_sha : ${GIT_SHA}"
echo "    date    : ${BUILD_DATE}"
echo "    image   : ${IMAGE}"
echo "    platform: ${PLATFORM}"

# SBOM provenance: the Dockerfile's sbom-collector stage only COPIES pre-existing
# openbank-*/build/reports/bom.json files — it has no JDK and never runs gradle. So
# unless cyclonedxBom has been run first, the bundle ships empty and the Tech
# Inventory SBOM viewer 404s ("SBOM not available") for every service. Generate the
# CycloneDX SBOMs here (best-effort) so the bundle is populated reproducibly from the
# same entry point that builds the image. Skip with --no-sbom for a fast UI-only
# iteration that reuses whatever boms already exist on disk.
if [ "${GEN_SBOM}" -eq 1 ]; then
  echo "==> generate CycloneDX SBOMs (./gradlew sbomAll)"
  # Best-effort: a single service failing to resolve must not abort the image build;
  # the collector bundles whatever bom.json files exist.
  ./gradlew sbomAll --console=plain -q || \
    echo "    WARN: sbomAll had failures; bundling whatever SBOMs were produced." >&2
  echo "    sbom reports present:"
  find . -path '*/build/reports/bom.json' 2>/dev/null | sort | sed 's/^/      /' || true
fi

# Test/coverage provenance: the admin-ui is a READ-ONLY consumer of CI test
# results (src/app/api/test-results/route.ts) — it never runs tests itself. The
# Dockerfile bakes openbank-admin-ui/test-results.json; this step regenerates it
# from JUnit XML so the Test Coverage page reflects real numbers reproducibly.
#
# Source repo for the XML is ${TEST_RESULTS_REPO:-$REPO_ROOT}. A clean worktree
# (e.g. a surgical branch checkout) has no build/test-results/ and would collect
# an all-zero summary — so we GUARD: collect to a temp file and only adopt it if
# it carries tests, otherwise keep any existing non-empty bundle. Point
# TEST_RESULTS_REPO at a checkout that has run `./gradlew test` for real numbers.
TEST_RESULTS_REPO="${TEST_RESULTS_REPO:-$REPO_ROOT}"
TR_OUT="openbank-admin-ui/test-results.json"
TR_TMP="$(mktemp)"
# mktemp yields an extensionless path; read it with readFileSync+JSON.parse
# rather than require() so Node does not mis-dispatch the JSON to its .js loader
# (which would throw and silently freeze NEW_TESTS at 0 on every CI build).
trap 'rm -f "${TR_TMP}"' EXIT
tr_total() { node -e "try{process.stdout.write(String(JSON.parse(require('fs').readFileSync(process.argv[1],'utf8')).totals.tests))}catch{process.stdout.write('0')}" "$1" 2>/dev/null || echo 0; }
echo "==> collect test results (--repo ${TEST_RESULTS_REPO})"
if node openbank-admin-ui/scripts/collect-test-results.mjs \
     --repo "${TEST_RESULTS_REPO}" --out "${TR_TMP}" 2>&1; then
  NEW_TESTS="$(tr_total "${TR_TMP}")"
  OLD_TESTS="$(tr_total "${TR_OUT}")"
  if [ "${NEW_TESTS}" -gt 0 ] || [ ! -f "${TR_OUT}" ] || [ "${OLD_TESTS}" -eq 0 ]; then
    mv "${TR_TMP}" "${TR_OUT}"
    echo "    adopted: ${NEW_TESTS} tests -> ${TR_OUT}"
  else
    rm -f "${TR_TMP}"
    # Surface the gap in CI output so it does not silently persist across deploys.
    # In GitHub Actions this becomes a yellow annotation on the workflow run.
    echo "::warning::test-results collector returned 0 tests — JUnit XMLs not staged; Test Coverage page will show no data. Stage them via the 'Stage JUnit test results' workflow step or set TEST_RESULTS_REPO to a checkout with build/test-results/."
    echo "    kept existing bundle (${OLD_TESTS} tests); fresh collect was empty."
  fi
else
  rm -f "${TR_TMP}"
  # Never fail the build over telemetry; ensure the COPY target exists.
  [ -f "${TR_OUT}" ] || echo '{"services":[],"totals":{"tests":0,"passed":0,"failed":0,"skipped":0,"services":0,"servicesWithTests":0,"unit":{"tests":0,"passed":0,"failed":0},"integration":{"tests":0,"passed":0,"failed":0}},"collectedAt":null}' > "${TR_OUT}"
  echo "    WARN: collector failed; using existing/placeholder bundle." >&2
fi

# Quality provenance (ADR-0063): contract pairs (pacts/) + pitest mutation scores
# -> quality-report.json, read by the Quality dashboard (src/app/api/quality-report/
# route.ts). Runs FROM openbank-admin-ui/ so it picks up the test-results.json just
# collected above and writes the bundle the Dockerfile bakes; --repo-root points the
# pitest-XML / pacts scan at the monorepo root. Best-effort — never abort the build
# over telemetry; the route degrades to an empty scaffold when the file is absent.
# Contract verdicts: if PACT_BROKER_URL (+ PACT_BROKER_USERNAME/PASSWORD, the same
# read-only vars the CI can-i-deploy gate uses) are exported, the collector folds the
# real provider-verification verdict from the broker (ADR-0092) into each contract;
# unset/unreachable → contracts stay 'pending' (fail-soft).
QR_OUT="openbank-admin-ui/quality-report.json"
echo "==> collect quality report (contracts + mutation scores)"
if ! ( cd openbank-admin-ui && node scripts/collect-quality-report.mjs --repo-root "${REPO_ROOT}" ) 2>&1; then
  [ -f "${QR_OUT}" ] || echo '{"contracts":[],"mutations":[],"serviceScores":[],"collectedAt":null}' > "${QR_OUT}"
  echo "    WARN: quality-report collector failed; baked empty bundle." >&2
fi

# Common evidence projection. A collector failure must be honest (unavailable),
# never an all-zero report that resembles a healthy run.
TI_OUT="openbank-admin-ui/test-intelligence.json"
echo "==> collect unified test intelligence"
if ! node openbank-admin-ui/scripts/collect-test-intelligence.mjs \
    --repo "${REPO_ROOT}" --out "${TI_OUT}" 2>&1; then
  [ -f "${TI_OUT}" ] || echo '{"schemaVersion":1,"collectedAt":"1970-01-01T00:00:00.000Z","components":[],"contracts":[],"mutations":[],"performance":[],"syntheticJourneys":[],"clientExperiences":[],"history":[],"runHistory":[],"testCases":[],"totals":{"components":0,"componentsWithExecutionEvidence":0,"moneyPathComponents":0,"failingEvidence":0,"missingEvidence":0,"staleEvidence":0},"warnings":["collector failed"]}' > "${TI_OUT}"
  echo "    WARN: test-intelligence collector failed; baked unavailable bundle." >&2
fi

# Production-readiness provenance: derive the C1–C9 maturity scorecard from the
# repo (read-only consumer; src/app/api/prod-readiness/route.ts). Pure repo read
# via the Python collector — no JDK/creds. Best-effort: a failure must not abort
# the image build (the Readiness page just degrades to its calm empty state).
PR_OUT="openbank-admin-ui/prod-readiness.json"
echo "==> collect production-readiness scorecard (repo-derived)"
if ! python3 openbank-infra/scripts/prod-readiness-collector.py --all --json "${PR_OUT}" >/dev/null 2>&1; then
  [ -f "${PR_OUT}" ] || echo '{"generated_for":"","dimensions":[],"services":[]}' > "${PR_OUT}"
  echo "    WARN: readiness collector failed; baked empty scorecard." >&2
fi

# Cost provenance: snapshot real AWS spend from Cost Explorer into cost-report.json
# (read-only consumer; src/app/api/finops/costs/route.ts). The collector shells
# out to `aws ce get-cost-and-usage` — the same SSO/OIDC creds used for the ECR
# login below — and writes an honest available:false snapshot if Cost Explorer is
# denied/absent (never fabricated). Best-effort: a billing-read failure must not
# abort the image build (the FinOps cost panel just degrades to "unavailable").
CR_OUT="openbank-admin-ui/cost-report.json"
echo "==> collect AWS costs (Cost Explorer snapshot)"
if ! node openbank-admin-ui/scripts/collect-aws-costs.mjs --out "${CR_OUT}" 2>&1; then
  # Ensure the COPY target exists even if node itself failed to launch.
  [ -f "${CR_OUT}" ] || echo '{"available":false,"reason":"collector failed","currency":"USD","periodStart":"","periodEnd":"","total":0,"services":[],"collectedAt":null,"source":"aws-cost-explorer"}' > "${CR_OUT}"
  echo "    WARN: cost collector failed; baked available:false snapshot." >&2
fi

# DORA provenance: derive Deployment Frequency + Lead Time from git first-parent
# history into dora.json (read-only consumer; src/app/api/devops/dora/route.ts,
# ADR-0061). Pure git read, no token/creds. Best-effort: outside a git checkout
# it writes an honest available:false snapshot (the DevOps cards degrade calmly).
DORA_OUT="openbank-admin-ui/dora.json"
echo "==> collect DORA metrics (git-derived)"
if ! node openbank-admin-ui/scripts/collect-dora.mjs --out "${DORA_OUT}" 2>&1; then
  [ -f "${DORA_OUT}" ] || echo '{"available":false,"reason":"collector failed","source":"git-first-parent","windowDays":30,"collectedAt":null,"deploymentCount":0,"deploymentFrequencyPerDay":null,"leadTimeHours":null,"recentDeployments":[]}' > "${DORA_OUT}"
  echo "    WARN: DORA collector failed; baked available:false snapshot." >&2
fi

# Catalog provenance: regenerate the code-derived service catalog (ADR-0029 D3)
# from version.txt + openapi.yaml + rules.yaml. Pure repo walk, no creds. The
# admin-ui catalog page reads this baked snapshot (read-only consumer).
CAT_OUT="openbank-admin-ui/catalog.json"
echo "==> generate service catalog (ADR-0029 D3)"
if ! node openbank-admin-ui/scripts/generate-catalog.mjs --repo "${REPO_ROOT}" --out "${CAT_OUT}" 2>&1; then
  [ -f "${CAT_OUT}" ] || echo '{"schema":"openbank.catalog/v1","source":"generator failed","collectedAt":null,"totals":{},"services":[]}' > "${CAT_OUT}"
  echo "    WARN: catalog generator failed; baked empty catalog." >&2
fi

# Dependency-graph provenance: derive inter-service edges (ADR-0029 D1) from each
# service's application.yaml (rest-client + Kafka channels). Pure repo walk.
SG_OUT="openbank-admin-ui/service-graph.json"
echo "==> generate service graph (ADR-0029 D1)"
if ! node openbank-admin-ui/scripts/generate-service-graph.mjs --repo "${REPO_ROOT}" --out "${SG_OUT}" 2>&1; then
  [ -f "${SG_OUT}" ] || echo '{"schema":"openbank.service-graph/v1","source":"generator failed","collectedAt":null,"totals":{},"nodes":[],"edges":[],"danglingTopics":[]}' > "${SG_OUT}"
  echo "    WARN: service-graph generator failed; baked empty graph." >&2
fi

# Zero-trust posture provenance: derive the defense-in-depth security map from the
# real platform manifests (istio.yaml, network-policies.yaml, kyverno verify-images
# policy) — governance-as-code (ADR-0029) for /docs/zero-trust. Pure repo walk.
SEC_OUT="openbank-admin-ui/security-graph.json"
echo "==> generate security posture (zero-trust map)"
if ! node openbank-admin-ui/scripts/generate-security-graph.mjs --repo "${REPO_ROOT}" --out "${SEC_OUT}" 2>&1; then
  [ -f "${SEC_OUT}" ] || echo '{"schema":"openbank.security-posture/v1","source":"generator failed","collectedAt":null,"istio":{"available":false},"network":{"available":false},"supplyChain":{"available":false},"available":false}' > "${SEC_OUT}"
  echo "    WARN: security-graph generator failed; baked empty posture." >&2
fi

# Governance provenance: join each module's declarative governance.yaml (data
# domain, classification, retention, lineage) with derived Flyway versions into the
# governance manifest (ADR-0071) — replaces the hand-edited manifest.ts. Pure repo walk.
GOV_OUT="openbank-admin-ui/governance.json"
echo "==> generate governance manifest (ADR-0071)"
# FAIL LOUD, don't ship a broken dashboard. The generator imports the `yaml` package, so it
# needs openbank-admin-ui/node_modules present on the HOST (it runs here, before the Docker
# `npm ci`). Building from a git worktree without node_modules silently baked an empty manifest
# (0 services) → the dashboard read "0/0 services online". A release artifact with an empty
# governance manifest is a defect, so error out instead of warning.
if ! node openbank-admin-ui/scripts/generate-governance.mjs --repo "${REPO_ROOT}" --out "${GOV_OUT}" 2>&1; then
  echo "ERROR: governance generator failed. Ensure openbank-admin-ui/node_modules exists" >&2
  echo "       (run 'npm ci' in openbank-admin-ui, or build from a checkout that has it)." >&2
  exit 1
fi
GOV_SVC_COUNT="$(node -e "process.stdout.write(String((JSON.parse(require('fs').readFileSync('${GOV_OUT}')).services||[]).length))" 2>/dev/null || echo 0)"
if [ "${GOV_SVC_COUNT}" -lt 1 ]; then
  echo "ERROR: governance manifest has 0 services — refusing to bake a broken dashboard." >&2
  exit 1
fi
echo "    governance manifest: ${GOV_SVC_COUNT} services"

# Infrastructure lifecycle (ADR-0077): join endoflife.date cycles + GitOps image tags
# into infra-lifecycle.json. Outbound fetch to endoflife.date, zero creds. The generator
# fails the build if zero lifecycle feeds resolve (the governance-empty lesson).
IL_OUT="openbank-admin-ui/infra-lifecycle.json"
echo "==> generate infra lifecycle snapshot (ADR-0077)"
if ! node openbank-admin-ui/scripts/generate-infra-lifecycle.mjs --repo "${REPO_ROOT}" --out "${IL_OUT}" 2>&1; then
  echo "ERROR: infra-lifecycle generator failed (endoflife.date unreachable?)." >&2
  exit 1
fi

# Cluster & container dossier (ADR-0081): namespace set + NetworkPolicy/ESO/ClusterPolicy counts +
# image anatomy, derived from GitOps + a representative Dockerfile. Pure repo walk, no creds.
CT_OUT="openbank-admin-ui/cluster-topology.json"
echo "==> generate cluster topology snapshot (ADR-0081)"
if ! node openbank-admin-ui/scripts/generate-cluster-topology.mjs --repo "${REPO_ROOT}" --out "${CT_OUT}" 2>&1; then
  echo "WARN: cluster-topology generator failed; baking an empty dossier." >&2
  echo '{"schema":"openbank.cluster-topology/v1","source":"generator failed","generatedAt":null,"counts":{},"groups":[],"namespaces":[],"securityLayers":[],"imageAnatomy":{"steps":[]},"planVsReality":[]}' > "${CT_OUT}"
fi

# Infrastructure CVEs (ADR-0077): optional Grype scan of the running infra images into
# infra-vulns.json. Opt-in (--scan-infra) because it pulls images + the vuln DB. When not
# requested, the committed placeholder stays and the UI honestly shows "not yet scanned".
IV_OUT="openbank-admin-ui/infra-vulns.json"
if [ "${SCAN_INFRA:-0}" = "1" ]; then
  if command -v grype >/dev/null 2>&1; then
    echo "==> scan infra images with Grype (ADR-0077)"
    node openbank-admin-ui/scripts/scan-infra-vulns.mjs --repo "${REPO_ROOT}" --out "${IV_OUT}" || \
      echo "    WARN: infra vuln scan had failures; kept whatever summary was produced." >&2
  else
    echo "    WARN: --scan-infra given but 'grype' not on PATH; keeping placeholder. Install: brew install grype" >&2
  fi
fi
[ -f "${IV_OUT}" ] || echo '{"schema":"openbank.infra-vulns/v1","scannedAt":null,"images":{}}' > "${IV_OUT}"

# Cost-allocation provenance: derive per-service resource footprints (ADR-0062) from the gitops
# Deployment requests. Pure repo walk, no creds. The allocation route joins this with cost-report
# .json at runtime to roll spend up service -> domain -> business flow (read-only consumer).
CF_OUT="openbank-admin-ui/cost-footprints.json"
echo "==> collect cost footprints (ADR-0062)"
if ! node openbank-admin-ui/scripts/collect-cost-footprints.mjs --repo "${REPO_ROOT}" --out "${CF_OUT}" 2>&1; then
  [ -f "${CF_OUT}" ] || echo '{"schema":"openbank.cost-footprints/v1","source":"collector failed","available":false,"collectedAt":null,"footprints":[]}' > "${CF_OUT}"
  echo "    WARN: cost-footprints collector failed; baked empty footprints." >&2
fi

# Customer-app dossier provenance (ADR-0074). UNLIKE the generators above, this
# artefact is NOT regenerated here: its source lives in a SEPARATE repo
# (JiRaska/openbank-app), so app-status.json is a COMMITTED, cross-repo transported
# artefact (produced + published by openbank-app CI, invariant 3). The image bakes
# the committed copy the same way it bakes the ADR/threat-model corpora — the build
# never reaches across a filesystem to a sibling checkout. Here we only GUARD the
# COPY target: a present, well-formed copy is baked as-is; a missing/garbled one
# degrades to an honest available:false stub so /docs/customer-app shows "no data"
# rather than 500ing. The content check that keeps the committed copy honest runs
# in CI (ci.yml: app-status job + openbank-app app-status.yml), not at image build.
AS_OUT="openbank-admin-ui/app-status.json"
echo "==> verify customer-app dossier artefact (ADR-0074 transport)"
if node -e "const d=JSON.parse(require('fs').readFileSync('${AS_OUT}','utf8'));process.exit(Array.isArray(d.capabilities)?0:1)" 2>/dev/null; then
  echo "    committed app-status.json is valid; baking as-is (transported from openbank-app)."
else
  echo '{"schema":"openbank.appstatus/v1","app":{"name":"openbank-app"},"asOf":null,"appRepo":"JiRaska/openbank-app","derived":{"sourceAvailable":false},"capabilities":[],"summary":{"total":0,"byStatus":{},"decisionMissing":[]},"gaps":["app-status.json missing or unreadable at build time"],"available":false}' > "${AS_OUT}"
  echo "    WARN: app-status.json missing/garbled; baked available:false stub." >&2
fi

if [ "${ECR_LOGIN}" = "1" ]; then
  echo "==> ECR login (${AWS_REGION})"
  aws ecr get-login-password --region "${AWS_REGION}" \
    | docker login --username AWS --password-stdin "${ECR_REGISTRY}"
else
  echo "==> ECR login skipped (--no-login; caller is responsible for docker auth)"
fi

# Context is the repo root: the Dockerfile collects every openbank-*/docs tree and
# COPYs openbank-admin-ui/ in. Build args carry the provenance into the image.
echo "==> docker buildx build + push"
# GLITCHTIP_AUTH_TOKEN (optional) enables source-map upload — passed as a BuildKit secret so it is
# never recorded in the image history. Unset means a normal build with no upload (#3235).
SECRET_ARGS=()
if [ -n "${GLITCHTIP_AUTH_TOKEN:-}" ]; then
  SECRET_ARGS+=(--secret "id=glitchtip_token,env=GLITCHTIP_AUTH_TOKEN")
  echo "    sourcemaps: upload ENABLED"
else
  echo "    sourcemaps: no GLITCHTIP_AUTH_TOKEN — upload skipped"
fi

buildx_args=(
  "${SECRET_ARGS[@]}" \
  --platform "${PLATFORM}" \
  --file openbank-admin-ui/Dockerfile \
  --build-arg "BUILD_VERSION=${BUILD_VERSION}" \
  --build-arg "BUILD_GIT_SHA=${GIT_SHA}" \
  --build-arg "BUILD_DATE=${BUILD_DATE}" \
  --tag "${IMAGE}" \
  --push \
  .
)

# The GitHub job has a 30-minute ceiling, but an unbounded BuildKit invocation can consume the
# entire deploy slot and leave no time for a useful failure or cleanup signal. GNU timeout is
# available on the Linux CI runner; keep the local macOS path portable and unbounded, because
# macOS does not ship that command. 24 minutes leaves time inside the job for attestation and the
# GitOps handoff while making a hung daemon observable as an ordinary failed build.
if command -v timeout >/dev/null 2>&1; then
  BUILDX_TIMEOUT_SECONDS="${ADMIN_UI_BUILDX_TIMEOUT_SECONDS:-1440}"
  if ! [[ "${BUILDX_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
    echo "ERROR: ADMIN_UI_BUILDX_TIMEOUT_SECONDS must be a positive integer." >&2
    exit 2
  fi
  echo "    buildx timeout: ${BUILDX_TIMEOUT_SECONDS}s (CI fail-fast guard)"
  timeout --foreground --kill-after=30s "${BUILDX_TIMEOUT_SECONDS}s" docker buildx build "${buildx_args[@]}"
else
  echo "    buildx timeout: unavailable on this host; relying on the caller's process limit"
  docker buildx build "${buildx_args[@]}"
fi

echo "==> pushed ${IMAGE}"

# Sign + attest via the shared helper (ADR-0029/0030 supply-chain), so admin-ui does
# provenance identically to every other producer. KMS trust root
# alias/openbank-cosign-signing. This block used to be the only correct copy of the attest
# logic in the tree; it is now the shared implementation (lib/cosign-attest.sh) — which is
# also where the cosign-v2 pin and its rationale (kyverno 3.2.6 / issue #770) now live.
#
# Now FATAL rather than best-effort. The old "never blocks the push" behaviour is exactly
# what let unattested images reach gitops: kyverno's SBOM-attestation policy is Enforce, so
# a warning here buys a push that cannot be admitted on its next reschedule.
. "${REPO_ROOT}/openbank-infra/scripts/lib/cosign-attest.sh"

cosign_sign_and_attest "$IMAGE" "$PLATFORM" || {
  echo "ERROR: ${IMAGE} pushed but NOT fully attested — it is NOT deployable." >&2
  echo "       Fix the provenance failure above and re-run; do not bump gitops to this tag." >&2
  exit 1
}

if [ "${BUMP_MANIFEST}" -eq 1 ]; then
  echo "==> bump ${MANIFEST} -> ${TAG}"
  # Replace the admin-ui image tag in the gitops manifest so ArgoCD rolls it out.
  if grep -qE "image: ${ECR_REGISTRY}/${ECR_REPO}:" "${MANIFEST}"; then
    sed -i.bak -E "s#(image: ${ECR_REGISTRY}/${ECR_REPO}:)[^[:space:]]+#\1${TAG}#" "${MANIFEST}"
    rm -f "${MANIFEST}.bak"
    echo "    updated. Review & commit:"
    echo "      git add ${MANIFEST} && git commit -s -S -m 'chore(admin-ui): deploy ${TAG}'"
  else
    echo "    WARN: image line not found in ${MANIFEST}; skipping bump." >&2
  fi
fi

echo "==> done."
