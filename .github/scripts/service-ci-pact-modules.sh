#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Sourced by Services CI for safe path classification and Pact module selection.
# Admin UI is a consumer but not a Gradle module; its trusted Pact workflow
# publishes the contract, and the provider remains in the service matrix.

is_inert_service_path() {
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
    .github/scripts/check-authz-enforce-money-path.py|.github/scripts/check-flyway-version-commit-order.py|.github/scripts/check-public-workload-ha.py|.github/scripts/check-tofu-image-pull-through.py|.github/scripts/auto-update-armed-prs.py|.github/scripts/deploy-window-inputs.sh|.github/scripts/supersede-deploy-prs.sh|.github/scripts/publish-admin-ui-pacts.py|.github/scripts/test-publish-admin-ui-pacts.py|.github/scripts/verify-dependabot-auto-merge.py|.github/scripts/test-verify-dependabot-auto-merge.py|.github/gates/gates.yaml|.github/gates/workflow-write-permissions-baseline.txt|.github/canary-rollout-realisable-baseline.txt|.github/public-workload-ha-baseline.txt)
      # Separate governance and Pact controls own these files; they do not build services.
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
  grep -qE '^(openbank-libs[^/]*/|gradle/|settings\.gradle\.kts|build\.gradle\.kts|gradle\.properties|build-logic/)' <<< "$changed" \
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
  ! is_inert_service_path ".github/scripts/unknown-service-build-helper.sh" \
    || { echo "selector self-test: unknown automation must retain safe fallback" >&2; return 1; }
  is_inert_service_path ".github/scripts/check-tofu-image-pull-through.py" \
    || { echo "selector self-test: Terraform image gate must be service-inert" >&2; return 1; }
  ! is_inert_service_path ".github/scripts/check-tofu-image-pull-through-test-helper.py" \
    || { echo "selector self-test: unowned Terraform helper must retain safe fallback" >&2; return 1; }
  is_inert_service_path ".github/scripts/check-flyway-version-commit-order.py" \
    || { echo "selector self-test: Flyway order gate must be service-inert" >&2; return 1; }
  is_inert_service_path ".github/scripts/verify-dependabot-auto-merge.py" \
    || { echo "selector self-test: Dependabot validator must be service-inert" >&2; return 1; }
  is_inert_service_path ".github/scripts/publish-admin-ui-pacts.py" \
    || { echo "selector self-test: Pact publisher must not full-fleet" >&2; return 1; }
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
