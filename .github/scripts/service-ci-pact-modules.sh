#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Sourced by Services CI for safe path classification and Pact module selection.
# Admin UI is a consumer but not a Gradle module; its trusted Pact workflow
# publishes the contract, and the provider remains in the service matrix.

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
    openbank-infra/aws/envs/sandbox-platform/*.tf)
      # This Terraform environment has its own Platform OpenTofu workflow.
      # GitOps manifests remain relevant: JVM tests read some ConfigMaps and policies.
      return 0 ;;
    openbank-admin-ui/*|docs/*|*/version.txt|*/CHANGELOG.md|.release-please-manifest.json|release-please-config.json)
      return 0 ;;
    .github/scripts/check-authz-enforce-money-path.py|.github/scripts/check-flyway-version-commit-order.py|.github/scripts/check-public-workload-ha.py|.github/scripts/check-tofu-image-pull-through.py|.github/scripts/auto-update-armed-prs.py|.github/scripts/deploy-window-inputs.sh|.github/scripts/supersede-deploy-prs.sh|.github/scripts/publish-admin-ui-pacts.py|.github/scripts/test-publish-admin-ui-pacts.py|.github/scripts/verify-dependabot-auto-merge.py|.github/scripts/test-verify-dependabot-auto-merge.py|.github/gates/gates.yaml|.github/gates/workflow-write-permissions-baseline.txt|.github/canary-rollout-realisable-baseline.txt|.github/public-workload-ha-baseline.txt|.github/scripts/collect-admin-ui-test-evidence.py|.github/scripts/test-collect-admin-ui-test-evidence.py|.github/scripts/select_admin_ui_evidence_datasets.py|.github/scripts/select_admin_ui_evidence_datasets_test.py|.github/scripts/await-admin-ui-source-validation.py|.github/scripts/test-await-admin-ui-source-validation.py|.github/scripts/reconcile-test-evidence.py|.github/scripts/reconcile_test_evidence_test.py)
      # Exact governance, Pact and evidence paths are inert; unknown helpers stay relevant.
      return 0 ;;
    .github/scripts/test-service-ci-job-roster.py|.github/scripts/test-service-ci-path-selection.py)
      # The changes job exercises this test; its source is not a Gradle/Pact input.
      return 0 ;;
    .github/workflows/*)
      # The copied runtime Dockerfile is a build input for every Gradle image.
      # Other workflows remain inert unless they are the service CI recipes.
      case "$1" in
        .github/workflows/services-ci.yml|.github/workflows/_service-ci.yml|.github/workflows/Dockerfile.deploy) return 1 ;;
        *) return 0 ;;
      esac ;;
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
  is_inert_service_path ".github/workflows/security-regression-test.yml" \
    || { echo "selector self-test: unrelated workflow must be inert" >&2; return 1; }
  ! is_inert_service_path ".github/workflows/services-ci.yml" \
    || { echo "selector self-test: service CI recipe must remain relevant" >&2; return 1; }
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
  ! is_inert_service_path ".github/scripts/unknown-service-build-helper.sh" \
    || { echo "selector self-test: unknown automation must retain safe fallback" >&2; return 1; }
  is_inert_service_path ".github/scripts/check-tofu-image-pull-through.py" \
    || { echo "selector self-test: Terraform image gate must be service-inert" >&2; return 1; }
  ! is_inert_service_path ".github/scripts/check-tofu-image-pull-through-test-helper.py" \
    || { echo "selector self-test: unowned Terraform helper must retain safe fallback" >&2; return 1; }
  ! is_inert_service_path ".github/scripts/test-unknown-admin-ui-helper.py" \
    || { echo "selector self-test: unknown UI helper must retain safe fallback" >&2; return 1; }
  ! is_inert_service_path ".github/scripts/check-service-build-inputs.py" \
    || { echo "selector self-test: unknown build policy must retain safe fallback" >&2; return 1; }
  ! is_inert_service_path ".github/scripts/unknown-evidence-reconcile-helper.py" \
    || { echo "selector self-test: unknown evidence helper must retain safe fallback" >&2; return 1; }
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
