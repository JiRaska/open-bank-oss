#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$repo_root/.github/scripts/service-ci-provider-task-list.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/complete.log" <<'EOF'
> Task :openbank-card-service:dependencies

> Task :openbank-card-service:tasks

Tasks runnable from project ':openbank-card-service'
providerPactTest
BUILD SUCCESSFUL in 1s
EOF
cat > "$tmp/footer-before-listing.log" <<'EOF'
BUILD SUCCESSFUL in 1s
> Task :openbank-card-service:tasks
Tasks runnable from project ':openbank-card-service'
providerPactTest
EOF
cat > "$tmp/dependencies-only.log" <<'EOF'
> Task :openbank-card-service:dependencies
providerPactTest
BUILD SUCCESSFUL in 1s
EOF
cat > "$tmp/interleaved-task.log" <<'EOF'
> Task :openbank-card-service:tasks
Tasks runnable from project ':openbank-card-service'
providerPactTest
> Task :openbank-other-service:tasks
BUILD SUCCESSFUL in 1s
EOF
for fixture in missing-footer wrong-project missing-task empty footer-before-listing dependencies-only interleaved-task; do
  case "$fixture" in
    missing-footer) sed '$d' "$tmp/complete.log" > "$tmp/$fixture.log" ;;
    wrong-project) sed "s/openbank-card-service/openbank-other-service/" "$tmp/complete.log" > "$tmp/$fixture.log" ;;
    missing-task) sed '/providerPactTest/d' "$tmp/complete.log" > "$tmp/$fixture.log" ;;
    empty) : > "$tmp/$fixture.log" ;;
    footer-before-listing|dependencies-only|interleaved-task) : ;;
  esac
  if service_ci_provider_task_list_is_proven "$tmp/$fixture.log" openbank-card-service; then
    echo "provider task listing self-test: accepted $fixture fixture" >&2
    exit 1
  fi
done

service_ci_provider_task_list_is_proven "$tmp/complete.log" openbank-card-service
service_ci_record_provider_task_proof 0 "$tmp/complete.log" openbank-card-service "$tmp/proof"
service_ci_provider_task_reuse_is_proven "$tmp/proof" openbank-card-service

# Failed/timed-out runs and successful but incomplete task output never create reuse proof.
for status in 1 124; do
  rm -f "$tmp/proof"
  if service_ci_record_provider_task_proof "$status" "$tmp/complete.log" openbank-card-service "$tmp/proof"; then
    echo "provider task listing self-test: recorded proof for status $status" >&2
    exit 1
  fi
  [ ! -e "$tmp/proof" ]
done
for listing in missing-footer wrong-project missing-task empty; do
  rm -f "$tmp/proof"
  if service_ci_record_provider_task_proof 0 "$tmp/$listing.log" openbank-card-service "$tmp/proof"; then
    echo "provider task listing self-test: recorded proof for $listing listing" >&2
    exit 1
  fi
  [ ! -e "$tmp/proof" ]
done

if service_ci_provider_task_reuse_is_proven "$tmp/proof" openbank-other-service; then
  echo 'provider task listing self-test: reused proof for the wrong service' >&2
  exit 1
fi
rm -f "$tmp/proof"
if service_ci_provider_task_reuse_is_proven "$tmp/proof" openbank-card-service; then
  echo 'provider task listing self-test: reused missing proof' >&2
  exit 1
fi
echo 'service-ci provider task listing self-test passed'
