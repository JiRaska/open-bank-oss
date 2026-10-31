#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Run the visual regression lane inside the ONE rendering environment its baselines were
# made in (ADR-0359): the pinned Playwright image, linux/amd64. CI and a developer laptop run
# this same script, so a baseline committed from a Mac is pixel-identical to what CI compares.
#
#   scripts/visual.sh            compare against committed baselines
#   scripts/visual.sh --update   re-render baselines (review the PNG diff before committing)
#
# linux/amd64 is forced on purpose: Apple-silicon Docker would otherwise pull the arm64
# variant, whose rasteriser is not guaranteed bit-identical to the amd64 CI runner.
set -euo pipefail

# amd64 manifest of mcr.microsoft.com/playwright:v1.64.1-noble — the tag is mutable, the digest is not.
IMAGE="mcr.microsoft.com/playwright:v1.64.1-noble@sha256:d65108d1a8095d8b0c4a5e32bf1807a40a5ce90ff0c78473e1116b1c243235e8"
ui_dir="$(cd "$(dirname "$0")/.." && pwd)"
repo_root="$(cd "${ui_dir}/.." && pwd)"

args=()
if [ "${1:-}" = "--update" ]; then
  args+=(--update-snapshots=all)
  shift
fi
args+=("$@")

# CI has already run `npm ci` and `npm run build` on the (amd64 Linux) host, so its
# node_modules and .next are usable inside the container. Elsewhere (macOS), install and
# build inside the container into a Docker volume so no Linux binaries land in the host tree.
prepare='true'
volume=()
if [ -z "${CI:-}" ]; then
  volume=(-v openbank-admin-ui-visual-node-modules:/work/openbank-admin-ui/node_modules)
  prepare='npm ci --no-audit --no-fund && NEXT_TELEMETRY_DISABLED=1 npm run build'
fi

exec docker run --rm --platform linux/amd64 --ipc=host --network host \
  -v "${repo_root}:/work" "${volume[@]}" -w /work/openbank-admin-ui \
  -e CI -e OPENBANK_E2E_PREBUILT=true \
  "${IMAGE}" bash -c "${prepare} && npx playwright test -c playwright.visual.config.ts \"\$@\"" visual "${args[@]}"
