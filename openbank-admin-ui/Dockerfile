# Multi-stage build for openbank-admin-ui.
#
# Build context: project root (..). The whole repo is visible to BuildKit
# but a tight .dockerignore (see openbank-admin-ui/.dockerignore-context)
# limits what enters the image. NO host paths are mounted at runtime —
# everything the FE serves at runtime is baked in as an immutable layer.
#
# What gets baked:
#   - The Next.js standalone bundle (./.next/standalone)
#   - Static assets (./.next/static)
#   - A read-only docs bundle (/app/docs-bundle/<service>/docs/*.md), built
#     by copying every openbank-<svc>/docs/ tree found in the build context.
#     The FE reads docs ONLY from this bundle path — never from a host mount.
#   - BuildInfo (version, git SHA, date) as environment vars injected via
#     build args. Surfaced at runtime via /api/build-info.

# ──────────────────────────────────────────────────────────────────────
# Stage 1a: Collect docs trees into a single bundle/ directory.
# Lightweight alpine, no node, just shell + cp.
# ──────────────────────────────────────────────────────────────────────
FROM alpine:3.24@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS docs-collector
WORKDIR /collect
COPY . /repo
RUN set -e; \
    mkdir -p /docs-bundle; \
    for dir in /repo/openbank-*/docs; do \
      [ -d "$dir" ] || continue; \
      svc=$(basename "$(dirname "$dir")"); \
      mkdir -p "/docs-bundle/$svc/docs"; \
      cp -r "$dir"/. "/docs-bundle/$svc/docs/"; \
      echo "bundled: $svc ($(ls "$dir" | wc -l) files)"; \
    done; \
    echo "── docs bundle ──"; \
    find /docs-bundle -name '*.md' | sort

# ──────────────────────────────────────────────────────────────────────
# Stage 1b: Collect CycloneDX SBOMs (build/reports/bom.json) for every
# service that has been built locally with `./gradlew sbomAll`. Bundles
# them at /sbom-bundle/<service>/bom.json so the admin-ui can serve from
# the image without a host fs mount. Services without a generated SBOM
# are simply absent from the bundle — the API surfaces "not generated".
# Re-uses the root .dockerignore exception added for SBOM coverage:
#   !openbank-*/build/reports/bom.json
# ──────────────────────────────────────────────────────────────────────
FROM alpine:3.24@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS sbom-collector
WORKDIR /collect
COPY . /repo
RUN set -e; \
    mkdir -p /sbom-bundle; \
    found=0; \
    for f in /repo/openbank-*/build/reports/bom.json; do \
      [ -f "$f" ] || continue; \
      svc=$(basename "$(dirname "$(dirname "$(dirname "$f")")")"); \
      mkdir -p "/sbom-bundle/$svc"; \
      cp "$f" "/sbom-bundle/$svc/bom.json"; \
      found=$((found + 1)); \
    done; \
    echo "── sbom bundle: $found service(s) ──"; \
    find /sbom-bundle -name 'bom.json' | sort

# ──────────────────────────────────────────────────────────────────────
# Stage 1c: Collect committed OpenAPI specs into a single bundle/ directory.
# The catalog page (/docs/api) reads endpoint counts + the full contract from
# these baked specs via /api/catalog/openapi/<svc> — NOT from the live
# `/q/openapi`, which Quarkus serves on the management port (8085) that the BFF
# (HTTP port only) cannot reach. The committed
# openbank-<svc>/src/main/resources/openapi.yaml is the governance source of
# truth (CLAUDE.md rule #4: info.version == version.txt). Bundling it makes the
# catalog work for ALL services regardless of deploy state, with no external
# GitHub dependency. Mirrors the docs/sbom collectors above.
# ──────────────────────────────────────────────────────────────────────
FROM alpine:3.24@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS openapi-collector
WORKDIR /collect
COPY . /repo
RUN set -e; \
    mkdir -p /openapi-bundle; \
    found=0; \
    for f in /repo/openbank-*/src/main/resources/openapi.yaml; do \
      [ -f "$f" ] || continue; \
      svc=$(basename "$(dirname "$(dirname "$(dirname "$(dirname "$f")")")")"); \
      mkdir -p "/openapi-bundle/$svc"; \
      cp "$f" "/openapi-bundle/$svc/openapi.yaml"; \
      found=$((found + 1)); \
    done; \
    echo "── openapi bundle: $found spec(s) ──"; \
    find /openapi-bundle -name 'openapi.yaml' | sort

# ──────────────────────────────────────────────────────────────────────
# Stage 1d: Collect the governance corpora — ADRs (docs/adr), per-service
# threat models (docs/threat-models), and agent charters (docs/agents) — into
# /governance-bundle so the operator console can surface them (read-only) at
# /docs/adr, /docs/threat-models and /iaops/agents/<id> (ADR-0156). Mirrors the
# docs/sbom/openapi collectors above. Re-uses the root .dockerignore exceptions
# added for these trees (!docs/adr/**, !docs/threat-models/**, !docs/agents/**);
# the rest of docs/ + *.md stays excluded.
# ──────────────────────────────────────────────────────────────────────
# Also carries the ADR-0148 prompt registry (openbank-libs/governance/prompts) for the ADR-0285
# Communication Studio: openbank-libs/ is .dockerignore'd out of the runtime fs, so without this
# copy /communication renders "registry not available" in the image while looking correct in local
# dev, where the repo tree is one level up.
FROM alpine:3.24@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS governance-collector
WORKDIR /collect
COPY . /repo
RUN set -e; \
    mkdir -p /governance-bundle/adr /governance-bundle/threat-models /governance-bundle/agents /governance-bundle/prompts /governance-src/openbank-libs/governance; \
    cp /repo/docs/adr/*.md /governance-bundle/adr/ 2>/dev/null || true; \
    cp /repo/docs/threat-models/*.md /governance-bundle/threat-models/ 2>/dev/null || true; \
    cp /repo/docs/agents/*.md /governance-bundle/agents/ 2>/dev/null || true; \
    cp /repo/openbank-libs/governance/rules.yaml /governance-src/openbank-libs/governance/rules.yaml; \
    cp -R /repo/openbank-libs/governance/prompts/. /governance-bundle/prompts/ 2>/dev/null || true; \
    echo "── governance bundle: $(ls /governance-bundle/adr | wc -l) ADR(s), $(ls /governance-bundle/threat-models | wc -l) threat model(s), $(ls /governance-bundle/agents | wc -l) agent charter(s), $(find /governance-bundle/prompts -name '*.md' | wc -l) prompt(s) ──"; \
    find /governance-bundle -name '*.md' | sort

# ──────────────────────────────────────────────────────────────────────
# Stage 1e: Collect feature-flag-as-code — the per-service flagd ConfigMaps at
# openbank-infra/gitops/components/<svc>/feature-flags.yaml — into /flags-bundle
# as flat <svc>.yaml files, so the operator console can surface the flag registry
# (read-only) at /docs/flags (ADR-0067). Mirrors the collectors above; the loader
# (src/lib/governance/flags.ts) parses the ConfigMap YAML + embedded flags.json.
# ──────────────────────────────────────────────────────────────────────
FROM alpine:3.24@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS flags-collector
WORKDIR /collect
COPY . /repo
RUN set -e; \
    mkdir -p /flags-bundle; \
    found=0; \
    for f in /repo/openbank-infra/gitops/components/*/feature-flags.yaml; do \
      [ -f "$f" ] || continue; \
      svc=$(basename "$(dirname "$f")"); \
      cp "$f" "/flags-bundle/$svc.yaml"; \
      found=$((found + 1)); \
    done; \
    echo "── flags bundle: $found service(s) ──"; \
    find /flags-bundle -name '*.yaml' | sort

# ──────────────────────────────────────────────────────────────────────
# Stage 1f: Collect per-service release-please CHANGELOG.md files into
# /changelog-bundle/<folder>/CHANGELOG.md so the docs BFF (src/lib/docs/releases.ts
# → /docs/changelog and /docs/release-notes) can render version history WITH NO
# GitHub access. The repo is PRIVATE and the admin-ui pod carries no token, so the
# previous GitHub-only path 404'd for every service and both pages showed up empty.
# Release notes are derived from these same files. Re-uses the root .dockerignore
# exception (!openbank-*/CHANGELOG.md). Mirrors the docs/sbom/openapi collectors.
# ──────────────────────────────────────────────────────────────────────
FROM alpine:3.24@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS changelog-collector
WORKDIR /collect
COPY . /repo
RUN set -e; \
    mkdir -p /changelog-bundle; \
    found=0; \
    for f in /repo/openbank-*/CHANGELOG.md; do \
      [ -f "$f" ] || continue; \
      svc=$(basename "$(dirname "$f")"); \
      mkdir -p "/changelog-bundle/$svc"; \
      cp "$f" "/changelog-bundle/$svc/CHANGELOG.md"; \
      found=$((found + 1)); \
    done; \
    echo "── changelog bundle: $found service(s) ──"; \
    find /changelog-bundle -name 'CHANGELOG.md' | sort

# ──────────────────────────────────────────────────────────────────────
# Stage 2: Install Node dependencies (cacheable layer).
# --platform=$BUILDPLATFORM: run npm ci on the BUILD host's native arch
# (amd64 on Hetzner x86_64, arm64 on ARC/Mac). Without this, buildx forces
# arm64 + QEMU on x86 hosts → npm ci crashes with SIGILL. The resulting
# node_modules contains only JS/JSON — no arch-specific binaries — so it
# is safe to copy into the arm64 runtime stage unchanged.
# ──────────────────────────────────────────────────────────────────────
# ──────────────────────────────────────────────────────────────────────
# Stage 1g: Stage the ADR-0211 origination Kotlin so `prebuild` can derive the state graph.
#
# `generate-origination-graph.mjs` reads openbank-libs-domain/.../origination/*.kt — deliberately,
# because a console that carried its own copy of the state machine would drift from the code that
# runs it. But the build stage copies ONLY `openbank-admin-ui/`, so inside the image that path does
# not exist: the script exited 1 and every admin-ui image build failed for six runs while local
# builds and `npm test` stayed green, because they run with the repo root present (#3283).
#
# Staged as its own collector rather than widening the build COPY: the image context stays the
# admin-ui directory plus explicitly named bundles, which is what every other input here does.
# ──────────────────────────────────────────────────────────────────────
FROM alpine:3.24@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS origination-collector
WORKDIR /collect
COPY . /repo
RUN set -e; \
    mkdir -p /origination-src/openbank-libs-domain/src/main/kotlin/com/openbank/libs/lending/origination; \
    cp /repo/openbank-libs-domain/src/main/kotlin/com/openbank/libs/lending/origination/*.kt \
       /origination-src/openbank-libs-domain/src/main/kotlin/com/openbank/libs/lending/origination/ 2>/dev/null || true; \
    echo "── origination sources: $(ls /origination-src/openbank-libs-domain/src/main/kotlin/com/openbank/libs/lending/origination | wc -l) file(s) ──"; \
    ls /origination-src/openbank-libs-domain/src/main/kotlin/com/openbank/libs/lending/origination

FROM --platform=$BUILDPLATFORM node:26-alpine@sha256:725aeba2364a9b16beae49e180d83bd597dbd0b15c47f1f28875c290bfd255b9 AS deps
WORKDIR /app
COPY openbank-admin-ui/package.json openbank-admin-ui/package-lock.json* ./
# Resilient install: the registry/network can flake (ECONNRESET mid-download) and a bare
# `npm install` then fails the whole build. Longer fetch timeouts + npm's own retry, wrapped
# in a 3-attempt outer loop with a clean cache between tries.
RUN npm config set fetch-retries 5 \
 && npm config set fetch-retry-mintimeout 20000 \
 && npm config set fetch-retry-maxtimeout 60000 \
 && npm config set fetch-timeout 120000 \
 && npm config set maxsockets 3 \
 && for i in 1 2 3; do \
      npm ci --legacy-peer-deps --no-audit --no-fund && break; \
      echo "npm ci attempt $i failed; retrying…"; \
      npm cache clean --force || true; sleep 10; \
    done; \
    test -x node_modules/.bin/next

# ──────────────────────────────────────────────────────────────────────
# Stage 3: Build the Next.js app.
# --platform=$BUILDPLATFORM: next build is pure JS compilation — the output
# (.next/standalone, .next/static) is architecture-agnostic and safe to
# copy into the arm64 runtime stage. Running on the native arch avoids
# QEMU overhead (~10× slower) and SIGILL crashes on x86 hosts.
# ──────────────────────────────────────────────────────────────────────
FROM --platform=$BUILDPLATFORM node:26-alpine@sha256:725aeba2364a9b16beae49e180d83bd597dbd0b15c47f1f28875c290bfd255b9 AS build
WORKDIR /app

# BuildInfo — passed in via docker-compose build args, stamped into the bundle.
ARG BUILD_VERSION=dev
ARG BUILD_GIT_SHA=unknown
ARG BUILD_DATE=unknown
ENV NEXT_PUBLIC_BUILD_VERSION=$BUILD_VERSION \
    NEXT_PUBLIC_BUILD_GIT_SHA=$BUILD_GIT_SHA \
    NEXT_PUBLIC_BUILD_DATE=$BUILD_DATE \
    BUILD_VERSION=$BUILD_VERSION \
    BUILD_GIT_SHA=$BUILD_GIT_SHA \
    BUILD_DATE=$BUILD_DATE

COPY --from=deps /app/node_modules ./node_modules
COPY openbank-admin-ui/ ./
# The origination Kotlin, at the repo-relative path the generator expects (#3283). It resolves
# against --repo, which defaults to the parent of the admin-ui directory — here, /.
COPY --from=origination-collector /origination-src/openbank-libs-domain /openbank-libs-domain
# Product Studio types are generated from the provider's committed design-first contract. Stage
# exactly that contract at the same repo-relative path used by local builds; never duplicate DTOs.
COPY --from=openapi-collector /openapi-bundle/openbank-product-catalog/openapi.yaml /openbank-product-catalog/src/main/resources/openapi.yaml
# The readiness collector derives money-path policy from this authoritative source. Keep the
# single file it reads at its repo-relative path; otherwise prebuild cannot distinguish a missing
# policy from an empty policy and correctly refuses to produce a misleading scorecard.
COPY --from=governance-collector /governance-src/openbank-libs/governance/rules.yaml /openbank-libs/governance/rules.yaml
ENV NEXT_TELEMETRY_DISABLED=1
ENV NEXT_STANDALONE=true
# Source-map upload to GlitchTip (#3235). The token arrives as a BuildKit SECRET, not a build arg:
# an ARG is recorded in `docker history` and would ship the credential inside the image everyone
# can pull. The mount exists only for the lifetime of this RUN.
#
# Absent secret = no upload and a normal build, so local and PR builds are unaffected and this can
# never become the reason a build fails.
RUN --mount=type=secret,id=glitchtip_token \
    SENTRY_AUTH_TOKEN="$(cat /run/secrets/glitchtip_token 2>/dev/null || true)" \
    npm run build

# Client source maps NEVER enter the runtime image. The upload happens inside `npm run build`
# above, so by this line the maps have already served their only purpose.
#
# @sentry/nextjs deletes them itself (`sourcemaps.deleteSourcemapsAfterUpload`), and that is the
# path that normally runs — but it is a plugin option, conditional on the plugin being reached at
# all, and `.next/static` is COPY'd wholesale into the runtime stage two stages down. One skipped
# hook and every original source of the operator console is downloadable from a public URL. This
# line makes that outcome structurally impossible instead of configured-away: it costs nothing when
# the delete already happened, and is the only thing standing between a plugin regression and a full
# source disclosure. Do not remove it as redundant (#3235).
RUN echo "maps before prune: $(find .next/static -name '*.map' | wc -l)" \
 && find .next/static -name '*.map' -type f -delete \
 && test "$(find .next/static -name '*.map' | wc -l)" -eq 0 \
 && echo "maps in .next/static after prune: 0"

# ──────────────────────────────────────────────────────────────────────
# Stage 4: Runtime image — minimal surface, non-root.
# ──────────────────────────────────────────────────────────────────────
FROM node:26-alpine@sha256:725aeba2364a9b16beae49e180d83bd597dbd0b15c47f1f28875c290bfd255b9 AS runtime
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1

ARG BUILD_VERSION=dev
ARG BUILD_GIT_SHA=unknown
ARG BUILD_DATE=unknown
ENV BUILD_VERSION=$BUILD_VERSION \
    BUILD_GIT_SHA=$BUILD_GIT_SHA \
    BUILD_DATE=$BUILD_DATE

RUN addgroup -S openbank && adduser -S openbank -G openbank

COPY --from=build /app/public ./public
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
# OpenTelemetry preload. Both files sit at the package root rather than inside the standalone
# bundle because Next.js only emits application code there; a --require preload is invisible to
# its file tracing. See otel-bootstrap.cjs for why the SDK cannot be started from
# instrumentation.ts (instrumentation-http patches node:http too late to catch the server).
COPY --from=build /app/otel-bootstrap.cjs /app/otel-scrub.cjs ./
COPY --from=docs-collector /docs-bundle ./docs-bundle
COPY --from=sbom-collector /sbom-bundle ./sbom-bundle
COPY --from=openapi-collector /openapi-bundle ./openapi-bundle
# Governance corpora (ADRs + threat models; read-only consumer, see
# src/lib/governance/docs.ts → /docs/adr and /docs/threat-models). Image-baked
# from docs/adr + docs/threat-models because the repo root docs/ tree is
# .dockerignore'd out of the runtime fs. Directory COPY keeps its 644/755 tree —
# no --chmod (would strip dir +x and break traversal, same as the bundles above).
COPY --from=governance-collector /governance-bundle ./governance-bundle
# Feature-flag-as-code registry (read-only consumer; see src/lib/governance/flags.ts
# → /docs/flags, ADR-0067). Image-baked from openbank-infra/gitops/components/*/
# feature-flags.yaml as flat <svc>.yaml files.
COPY --from=flags-collector /flags-bundle ./flags-bundle
# Per-service release-please CHANGELOG.md (read-only consumer; see
# src/lib/docs/releases.ts → /docs/changelog and /docs/release-notes). Image-baked
# from openbank-<svc>/CHANGELOG.md so version history renders with no GitHub access
# (the repo is private and the pod has no token). Directory COPY keeps its 644/755
# tree — no --chmod (would strip dir +x and break traversal, like the bundles above).
COPY --from=changelog-collector /changelog-bundle ./changelog-bundle
# CI-produced test/coverage summary (read-only consumer; see
# src/app/api/test-results/route.ts). build-push-admin-ui.sh runs
# scripts/collect-test-results.mjs before the build so this file always exists
# (a clean checkout with no JUnit XML yields an honest all-zero summary). The
# route prefers this baked artifact over the legacy openbank_ci Postgres path.
#
# --chmod=0644 is REQUIRED: a bare `COPY <file>` of a single context file lands
# as mode 600 root:root, which `USER openbank` (UID 100) cannot read → the route
# hits EACCES, silently falls back to the (unreachable) openbank_ci Postgres, and
# the Coverage page degrades to an all-zero placeholder. World-readable fixes it.
# (The bundle dirs above arrive via `COPY --from=<stage>` and keep their 644/755
# tree — do NOT add --chmod=0644 to a directory COPY, it would strip the dir +x
# bit and break traversal.)
COPY --chmod=0644 openbank-admin-ui/test-results.json ./test-results.json
# Quality report — contract pairs + pitest mutation scores (read-only consumer;
# src/app/api/quality-report/route.ts, ADR-0063). build-push-admin-ui.sh runs
# scripts/collect-quality-report.mjs before the build so this file always exists
# (empty scaffold when there are no pacts/pitest reports to collect).
# --chmod=0644 for the same UID-100 readability reason as test-results.json.
COPY --chmod=0644 openbank-admin-ui/quality-report.json ./quality-report.json
# Unified provenance-aware test evidence (ADR-0273). Legacy test/quality
# projections remain bundled through the compatibility window.
COPY --chmod=0644 openbank-admin-ui/test-intelligence.json ./test-intelligence.json
# CI-produced AWS Cost Explorer snapshot (read-only consumer; see
# src/app/api/finops/costs/route.ts). build-push-admin-ui.sh runs
# scripts/collect-aws-costs.mjs before the build so this file always exists
# (no billing creds at collect time → an honest available:false snapshot).
# --chmod=0644 for the same UID-100 readability reason as test-results.json.
COPY --chmod=0644 openbank-admin-ui/cost-report.json ./cost-report.json
# Git-derived DORA delivery metrics (read-only consumer; see
# src/app/api/devops/dora/route.ts, ADR-0061). build-push-admin-ui.sh runs
# scripts/collect-dora.mjs before the build so this file always exists (outside a
# git checkout it is an honest available:false snapshot).
# --chmod=0644 for the same UID-100 readability reason as test-results.json.
COPY --chmod=0644 openbank-admin-ui/dora.json ./dora.json
# AI-agent charters (ADR-0031 D1) — the machine-readable source of truth the
# IAOps section parses (src/app/api/iaops/governance). Read-only consumer: the
# UI never writes it. --chmod=0644 for the same UID-100 readability reason.
COPY --chmod=0644 openbank-libs/governance/agents.yaml ./agents.yaml
# Generated IAOps governance snapshot (ADR-0029 D3): curated rollout prose +
# machine-checkable repo facts. Read-only consumer: src/app/api/iaops/governance.
COPY --chmod=0644 openbank-admin-ui/ai-governance-snapshot.json ./ai-governance-snapshot.json
# Compliance control catalogue (read-only consumer; see
# src/lib/governance/compliance.ts → /docs/control-tower). Authored source of
# truth, baked like agents.yaml; the Control Tower joins it with the derived
# security posture. --chmod=0644 for the same UID-100 readability reason.
COPY --chmod=0644 openbank-libs/governance/compliance-controls.yaml ./compliance-controls.yaml
# CI-produced code-derived service catalog (ADR-0029 D3; read-only consumer; see
# src/app/api/catalog/services/route.ts). build-push-admin-ui.sh runs
# scripts/generate-catalog.mjs before the build so this file always exists.
# --chmod=0644 for the same UID-100 readability reason as test-results.json.
COPY --chmod=0644 openbank-admin-ui/catalog.json ./catalog.json
# CI-produced code-derived dependency graph (ADR-0029 D1; read-only consumer; see
# src/app/api/catalog/graph/route.ts). build-push-admin-ui.sh runs
# scripts/generate-service-graph.mjs before the build so this file always exists.
COPY --chmod=0644 openbank-admin-ui/service-graph.json ./service-graph.json
# Derived zero-trust security posture (governance-as-code, ADR-0029; read-only
# consumer, see src/lib/governance/security.ts → /docs/zero-trust).
# build-push-admin-ui.sh runs scripts/generate-security-graph.mjs before the
# build so this file always exists (parse failure → an honest available:false
# snapshot). --chmod=0644 for the same UID-100 readability reason.
COPY --chmod=0644 openbank-admin-ui/security-graph.json ./security-graph.json
# Infrastructure lifecycle snapshot (ADR-0077): endoflife.date cycles + GitOps image
# tags, baked by scripts/generate-infra-lifecycle.mjs. Read-only consumer:
# src/app/api/infra/lifecycle/route.ts. build-push-admin-ui.sh runs the generator before
# the build (and fails the build if it is empty), so this file always exists.
COPY --chmod=0644 openbank-admin-ui/infra-lifecycle.json ./infra-lifecycle.json
# Grype CVE summary for the infra images (ADR-0077). Optional: present only when the
# build ran with --scan-infra; otherwise an honest "not scanned" placeholder. The BFF
# degrades gracefully when it is absent.
COPY --chmod=0644 openbank-admin-ui/infra-vulns.json ./infra-vulns.json
# Repo-derived production-readiness scorecard (C1–C9 maturity; read-only consumer,
# src/app/api/prod-readiness/route.ts). build-push-admin-ui.sh runs
# scripts/prod-readiness-collector.py before the build so this file always exists
# (collector failure → an honest empty scorecard). --chmod=0644 for the same
# UID-100 readability reason as test-results.json.
COPY --chmod=0644 openbank-admin-ui/prod-readiness.json ./prod-readiness.json

USER openbank
EXPOSE 3000
ENV PORT=3000
ENV HOSTNAME="0.0.0.0"
ENV OPENBANK_DOCS_BUNDLE=/app/docs-bundle
ENV OPENBANK_SBOM_BUNDLE=/app/sbom-bundle
ENV OPENBANK_OPENAPI_BUNDLE=/app/openapi-bundle
ENV OPENBANK_FLAGS_BUNDLE=/app/flags-bundle
ENV OPENBANK_CHANGELOG_BUNDLE=/app/changelog-bundle
ENV OPENBANK_TEST_RESULTS=/app/test-results.json
ENV OPENBANK_TEST_INTELLIGENCE=/app/test-intelligence.json
ENV OPENBANK_READINESS=/app/prod-readiness.json
ENV OPENBANK_COST_REPORT=/app/cost-report.json
ENV OPENBANK_AGENTS_FILE=/app/agents.yaml
ENV OPENBANK_AI_GOVERNANCE_SNAPSHOT=/app/ai-governance-snapshot.json
ENV OPENBANK_COMPLIANCE_CONTROLS=/app/compliance-controls.yaml
ENV OPENBANK_CATALOG=/app/catalog.json
ENV OPENBANK_SERVICE_GRAPH=/app/service-graph.json
ENV OPENBANK_GOVERNANCE_DOCS=/app/governance-bundle
ENV OPENBANK_SECURITY_GRAPH=/app/security-graph.json
ENV OPENBANK_INFRA_LIFECYCLE=/app/infra-lifecycle.json
ENV OPENBANK_INFRA_VULNS=/app/infra-vulns.json
# Preload the OpenTelemetry SDK before the application so instrumentation-http can patch
# node:http before the server loads it. Self-gating: without OTEL_EXPORTER_OTLP_ENDPOINT the
# bootstrap starts no SDK and costs one require (ADR-0075 inv. 2). Baked here rather than left to
# the manifest so an environment that sets the endpoint cannot get tracing that silently exports
# nothing — the exact failure this replaced.
ENV NODE_OPTIONS="--require /app/otel-bootstrap.cjs"
CMD ["node", "server.js"]
