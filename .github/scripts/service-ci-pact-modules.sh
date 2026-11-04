#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Sourced by Services CI for safe path classification and Pact module selection.
# Admin UI is a consumer but not a Gradle module; its trusted Pact workflow
# publishes the contract, and the provider remains in the service matrix.

# The service-CI recipe: the files the build job itself executes. DERIVED from what
# _service-ci.yml and services-ci.yml reference (plus the selector's own sources), never a
# hand-kept list — a helper added to the recipe is in this set on the PR that adds it. A change
# here cannot alter a service's compile inputs, so it builds ONE canary (select-modules.sh),
# not the fleet. The bare `.github/scripts/` directory that the retry-helper `cp` loop yields
# is dropped: it would make every check script a recipe change.
service_ci_recipe_paths() {
  {
    printf '%s\n' .github/workflows/services-ci.yml .github/workflows/_service-ci.yml .github/workflows/_service-ci-pr.yml \
      .github/scripts/service-ci-pact-modules.sh .github/scripts/service-ci-select-modules.sh \
      .github/scripts/service-ci-library-dependents.py .github/scripts/derive-fleet-scanning-test-modules.py \
      .github/scripts/filter-lib-test-only-paths.py .github/scripts/coverage-floor-only-diff.py \
      .github/scripts/heal-gradle-verification-failure.sh .github/scripts/retry-testcontainers-ebusy.sh \
      .github/scripts/retry-gradle-verification-cache.sh .github/scripts/service-ci-steps/
    grep -ohE '\.github/[A-Za-z0-9_./*-]+' .github/workflows/_service-ci.yml .github/workflows/services-ci.yml 2>/dev/null \
      | sed -E 's#/\*(\.[a-z]+)?$#/#' | grep -vxE '\.github/(scripts|workflows|actions)/'
  } | sort -u
}

is_service_ci_recipe_path() {
  local p
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    case "$p" in
      */) [[ "$1" == "$p"* ]] && return 0 ;;
      *)  [ "$1" = "$p" ] && return 0 ;;
    esac
  done <<< "$(service_ci_recipe_paths)"
  return 1
}

recipe_path_changed() {
  local f
  while IFS= read -r f; do
    [ -n "$f" ] && is_service_ci_recipe_path "$f" && return 0
  done <<< "$1"
  return 1
}

is_inert_service_path() {
  # Shared umbrella docs are consumed by the Admin UI image bundle, not by any
  # Gradle module. Keep this exact root-level Markdown set out of JVM builds;
  # nested docs and other shared-library files retain the conservative path.
  if [[ "$1" =~ ^openbank-libs/docs/[^/]+\.md$ ]]; then
    return 0
  fi

  case "$1" in
    docs/adr/0039-ledger-as-golden-source-balance-as-projection.md)
      # AdrTextScannerTest reads this exact repository fixture.
      return 1 ;;
    openbank-infra/gitops/components/admin-ui/*)
      # Admin UI deploy PRs only move this image pin; admin-ui builds in its own
      # workflow. Relevant again the moment a JVM test reads this directory.
      ! grep -rqE --include='*.kt' --include='*.java' 'components("[[:space:]]*,[[:space:]]*"|/)admin-ui' openbank-*/src/test 2>/dev/null
      return ;;
    openbank-infra/aws/envs/sandbox-platform/*.tf)
      # This Terraform environment has its own Platform OpenTofu workflow.
      # GitOps manifests remain relevant: JVM tests read some ConfigMaps and policies.
      return 0 ;;
    .github/workflows/Dockerfile.deploy)
      # The copied runtime Dockerfile is a build input for every Gradle image.
      return 1 ;;
    .github/*)
      # Measured 2026-11-04 (string-literal inventory in derive-fleet-scanning-test-modules.py):
      # no JVM test and no Gradle script names a path under .github/. Gates, check scripts,
      # labels and other workflows are validated by `CI` / `Validate manifests`, never by a
      # service build. Until this rule, every such change fell to the full-fleet fallback —
      # ~20 PR branches with 86 queued builds each, for files no service compiles against.
      # The recipe the build job executes is the exception: it runs the canary, so it stays
      # relevant. Both halves are self-tested below.
      if is_service_ci_recipe_path "$1"; then return 1; fi
      return 0 ;;
    openbank-document-renderer/requirements.txt)
      # Main's Python sidecar lock remains outside the JVM lane.
      return 0 ;;
    openbank-admin-ui/*|docs/*|*/version.txt|*/CHANGELOG.md|.release-please-manifest.json|release-please-config.json)
      return 0 ;;
    *) return 1 ;;
  esac
}

global_service_build_input_changed() {
  local changed="$1"
  local build_inputs=""
  local path
  while IFS= read -r path; do
    [ -z "$path" ] && continue
    # The shared Markdown docs are bundled by Admin UI, not used as JVM build
    # inputs. Ignore only these direct children; unknown/nested paths stay safe.
    if [[ "$path" =~ ^openbank-libs/docs/[^/]+\.md$ ]]; then continue; fi
    build_inputs="${build_inputs}${build_inputs:+$'\n'}${path}"
  done <<< "$changed"
  grep -qE '^(openbank-libs[^/]*/|gradle/|settings\.gradle\.kts|build\.gradle\.kts|gradle\.properties|build-logic/)' <<< "$build_inputs" \
    || grep -Fxq '.github/workflows/Dockerfile.deploy' <<< "$changed"
}

pact_build_modules() {
  local base prov cons
  base=$(basename "$1" .json)
  case "$base" in *-openbank-*) ;; *) return 1 ;; esac
  prov="openbank-${base##*-openbank-}"
  cons="${base%-openbank-*}"
  if [ "$cons" != "openbank-admin-ui" ]; then printf '%s\n' "$cons"; fi
  printf '%s\n' "$prov"
}

admin_ui_pact_changed() {
  grep -qE '^pacts/openbank-admin-ui-openbank-[a-z0-9-]+\.json$' <<< "$1"
}

pact_build_modules_self_test() {
  ! is_inert_service_path "docs/adr/0039-ledger-as-golden-source-balance-as-projection.md" \
    || { echo "selector self-test: real ADR test fixture must remain relevant" >&2; return 1; }
  is_inert_service_path "openbank-infra/aws/envs/sandbox-platform/providers.tf" \
    || { echo "selector self-test: platform Terraform must be service-inert" >&2; return 1; }
  ! is_inert_service_path "openbank-infra/gitops/components/payments/transaction-service-msg-override.yaml" \
    || { echo "selector self-test: GitOps test input must remain relevant" >&2; return 1; }
  ! is_inert_service_path "openbank-infra/aws/envs/unknown/main.tf" \
    || { echo "selector self-test: unowned infrastructure must retain safe fallback" >&2; return 1; }
  is_inert_service_path "openbank-document-renderer/requirements.txt" \
    || { echo "selector self-test: Python renderer lock is not a JVM build input" >&2; return 1; }
  ! is_inert_service_path "openbank-document-renderer/requirements.txt.backup" \
    || { echo "selector self-test: unknown renderer sibling must retain safe fallback" >&2; return 1; }
  ! is_inert_service_path "openbank-unknown-component/requirements.txt" \
    || { echo "selector self-test: unknown component must retain safe fallback" >&2; return 1; }
  ! is_inert_service_path "openbank-document-renderer/build.gradle.kts" \
    || { echo "selector self-test: new renderer Gradle input must remain relevant" >&2; return 1; }
  ! is_inert_service_path "build.gradle.kts" \
    || { echo "selector self-test: root Gradle input must remain relevant" >&2; return 1; }
  global_service_build_input_changed "build.gradle.kts" \
    || { echo "selector self-test: root Gradle input must select the full fleet" >&2; return 1; }
  is_inert_service_path ".github/workflows/security-regression-test.yml" \
    || { echo "selector self-test: unrelated workflow must be inert" >&2; return 1; }
  # Under .github/, "unknown" no longer means "rebuild the fleet": the build job can only
  # execute what the recipe references, and that set is derived from the recipe itself. A
  # helper the recipe does not name cannot change any build; one it does name runs the canary.
  is_inert_service_path ".github/scripts/check-anything-new.py" \
    || { echo "selector self-test: a check script no service compiles against must be inert" >&2; return 1; }
  is_inert_service_path ".github/gates/gates.yaml" \
    || { echo "selector self-test: the gate manifest must be inert" >&2; return 1; }
  ! is_inert_service_path ".github/scripts/service-ci-steps/build-test.sh" \
    || { echo "selector self-test: a recipe helper the build job executes must stay relevant" >&2; return 1; }
  ! is_inert_service_path ".github/scripts/retry-testcontainers-ebusy.sh" \
    || { echo "selector self-test: a staged retry helper must stay relevant" >&2; return 1; }
  is_service_ci_recipe_path ".github/scripts/service-ci-steps/anything.sh" \
    || { echo "selector self-test: recipe set must be derived from the recipe's own references" >&2; return 1; }
  ! is_service_ci_recipe_path ".github/scripts/check-anything-new.py" \
    || { echo "selector self-test: the derived recipe set must not swallow .github/scripts/" >&2; return 1; }
  recipe_path_changed $'docs/x.md\n.github/scripts/service-ci-steps/build-test.sh' \
    || { echo "selector self-test: recipe change inside a file list must be detected" >&2; return 1; }
  ! recipe_path_changed $'docs/x.md\n.github/scripts/check-anything-new.py' \
    || { echo "selector self-test: a non-recipe file list must not read as a recipe change" >&2; return 1; }
  ! is_inert_service_path ".github/workflows/services-ci.yml" \
    || { echo "selector self-test: service CI recipe must remain relevant" >&2; return 1; }
  ! is_inert_service_path ".github/workflows/_service-ci-pr.yml" \
    || { echo "selector self-test: generated PR service CI recipe must remain relevant" >&2; return 1; }
  ! is_inert_service_path ".github/workflows/Dockerfile.deploy" \
    || { echo "selector self-test: canonical image recipe must remain relevant" >&2; return 1; }
  global_service_build_input_changed ".github/workflows/Dockerfile.deploy" \
    || { echo "selector self-test: canonical recipe must select the full Gradle fleet" >&2; return 1; }
  ! global_service_build_input_changed ".github/workflows/Dockerfile.deploy.backup" \
    || { echo "selector self-test: backup recipe must not fan out" >&2; return 1; }
  ! global_service_build_input_changed ".github/workflows/auto-deploy.yml" \
    || { echo "selector self-test: unrelated workflow must not fan out" >&2; return 1; }
  ! is_inert_service_path "openbank-ledger-service/src/main/kotlin/Ledger.kt" \
    || { echo "selector self-test: service source must remain relevant" >&2; return 1; }
  is_inert_service_path "openbank-libs/docs/01-overview.en.md" \
    || { echo "selector self-test: shared umbrella Markdown is not a JVM input" >&2; return 1; }
  ! is_inert_service_path "openbank-libs/docs/nested/guide.md" \
    || { echo "selector self-test: nested shared docs retain conservative handling" >&2; return 1; }
  ! is_inert_service_path "openbank-libs/docs/diagram.svg" \
    || { echo "selector self-test: non-Markdown shared docs retain conservative handling" >&2; return 1; }
  ! global_service_build_input_changed $'openbank-libs/docs/01-overview.en.md\nopenbank-libs/docs/README.md' \
    || { echo "selector self-test: shared umbrella Markdown must not fan out JVM builds" >&2; return 1; }
  global_service_build_input_changed "openbank-libs/docs/nested/guide.md" \
    || { echo "selector self-test: nested shared paths retain conservative global handling" >&2; return 1; }
  global_service_build_input_changed "openbank-libs/docs/diagram.svg" \
    || { echo "selector self-test: non-Markdown shared docs retain global handling" >&2; return 1; }
  global_service_build_input_changed "openbank-libs/src/main/kotlin/Shared.kt" \
    || { echo "selector self-test: compiled shared library source must fan out" >&2; return 1; }
  ! global_service_build_input_changed "openbank-ledger-service/src/main/resources/docs/03-api.en.md" \
    || { echo "selector self-test: packaged service docs are module-scoped, not global" >&2; return 1; }
  ! is_inert_service_path "openbank-ledger-service/src/main/resources/docs/03-api.en.md" \
    || { echo "selector self-test: packaged service docs must remain module-relevant" >&2; return 1; }
  is_inert_service_path ".github/scripts/unknown-service-build-helper.sh" \
    || { echo "selector self-test: automation the recipe never references is inert (derived recipe set, 2026-11-04)" >&2; return 1; }
  is_inert_service_path ".github/scripts/check-tofu-image-pull-through.py" \
    || { echo "selector self-test: Terraform image gate must be service-inert" >&2; return 1; }
  is_inert_service_path ".github/scripts/check-tofu-image-pull-through-test-helper.py" \
    || { echo "selector self-test: a Terraform helper no service compiles against is inert" >&2; return 1; }
  is_inert_service_path ".github/scripts/test-unknown-admin-ui-helper.py" \
    || { echo "selector self-test: an admin-ui helper is inert for the JVM fleet" >&2; return 1; }
  is_inert_service_path ".github/scripts/check-service-build-inputs.py" \
    || { echo "selector self-test: a build-policy script the recipe never references is inert" >&2; return 1; }
  is_inert_service_path ".github/scripts/unknown-evidence-reconcile-helper.py" \
    || { echo "selector self-test: an evidence helper the recipe never references is inert" >&2; return 1; }
  is_inert_service_path ".github/scripts/check-flyway-version-commit-order.py" \
    || { echo "selector self-test: Flyway order gate must be service-inert" >&2; return 1; }
  is_inert_service_path ".github/scripts/verify-dependabot-auto-merge.py" \
    || { echo "selector self-test: Dependabot validator must be service-inert" >&2; return 1; }
  is_inert_service_path ".github/scripts/publish-admin-ui-pacts.py" \
    || { echo "selector self-test: Pact publisher must not full-fleet" >&2; return 1; }
  for admin_evidence_path in \
    .github/scripts/collect-admin-ui-test-evidence.py \
    .github/scripts/test-collect-admin-ui-test-evidence.py \
    .github/scripts/select_admin_ui_evidence_datasets.py \
    .github/scripts/select_admin_ui_evidence_datasets_test.py \
    .github/scripts/await-admin-ui-source-validation.py \
    .github/scripts/test-await-admin-ui-source-validation.py; do
    is_inert_service_path "$admin_evidence_path" \
      || { echo "selector self-test: exact Admin UI evidence path must not full-fleet: $admin_evidence_path" >&2; return 1; }
  done
  is_inert_service_path ".github/scripts/reconcile-test-evidence.py" \
    || { echo "selector self-test: evidence reconcile script must not full-fleet" >&2; return 1; }
  is_inert_service_path ".github/scripts/reconcile_test_evidence_test.py" \
    || { echo "selector self-test: evidence reconcile tests must not full-fleet" >&2; return 1; }
  is_inert_service_path ".github/scripts/check-readiness-collectors-agree.py" \
    || { echo "selector self-test: readiness collector parity gate must not full-fleet" >&2; return 1; }
  is_inert_service_path ".github/gates/workflow-write-permissions-baseline.txt" \
    || { echo "selector self-test: Pact permission baseline is service-inert" >&2; return 1; }
  [ "$(pact_build_modules pacts/openbank-admin-ui-openbank-context-service.json)" = "openbank-context-service" ] \
    || { echo "selector self-test: Admin UI Pact must select its provider only" >&2; return 1; }
  [ "$(pact_build_modules pacts/openbank-ledger-service-openbank-balance-service.json)" = $'openbank-ledger-service\nopenbank-balance-service' ] \
    || { echo "selector self-test: service Pact must select both sides" >&2; return 1; }
  if pact_build_modules pacts/invalid.json >/dev/null; then
    echo "selector self-test: malformed Pact filename must fail" >&2
    return 1
  fi
  admin_ui_pact_changed $'pacts/openbank-admin-ui-openbank-context-service.json\nopenbank-admin-ui/src/app/page.tsx' \
    || { echo "selector self-test: changed admin UI pact needs publication" >&2; return 1; }
  ! admin_ui_pact_changed 'openbank-admin-ui/src/app/page.tsx' \
    || { echo "selector self-test: UI source alone needs no publication wait" >&2; return 1; }
}
