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
# The contract job may already be in a historical checkout. Its healer must be
# the trusted staged script, invoked through Bash even when copied mode is 0600.
for relative_helper in missing poisoned; do
  sandbox="$tmp/warm-$relative_helper"
  mkdir -p "$sandbox/work" "$sandbox/bin" "$sandbox/trusted" "$sandbox/runtime"
  cp "$repo_root/.github/scripts/service-ci-provider-task-list.sh" "$sandbox/runtime/"
  cp "$tmp/complete.log" "$sandbox/listing.log"
  cat > "$sandbox/bin/timeout" <<'EOF'
#!/usr/bin/env bash
shift 2
exec "$@"
EOF
  cat > "$sandbox/work/gradlew" <<'EOF'
#!/usr/bin/env bash
count=0
[ ! -e "$WARM_CASE/count" ] || count=$(cat "$WARM_CASE/count")
count=$((count + 1))
printf '%s' "$count" > "$WARM_CASE/count"
if [ "$count" -eq 1 ]; then
  echo 'synthetic verification cache failure'
  exit 1
fi
cat "$WARM_CASE/listing.log"
EOF
  cat > "$sandbox/trusted/heal-gradle-verification-failure.sh" <<'EOF'
#!/usr/bin/env bash
grep -q 'synthetic verification cache failure' "$1" || exit 3
printf 'trusted' > "$WARM_CASE/healer"
EOF
  chmod 700 "$sandbox/bin/timeout" "$sandbox/work/gradlew"
  chmod 600 "$sandbox/trusted/heal-gradle-verification-failure.sh"
  if [ "$relative_helper" = poisoned ]; then
    mkdir -p "$sandbox/work/.github/scripts"
    cat > "$sandbox/work/.github/scripts/heal-gradle-verification-failure.sh" <<'EOF'
#!/usr/bin/env bash
printf 'poisoned' > "$WARM_CASE/poison"
exit 0
EOF
    chmod 700 "$sandbox/work/.github/scripts/heal-gradle-verification-failure.sh"
  fi
  (
    cd "$sandbox/work"
    WARM_CASE="$sandbox" RUNNER_TEMP="$sandbox/runtime" SERVICE_CI_HELPERS="$sandbox/trusted" \
      SERVICE=openbank-card-service GITHUB_RUN_ID=42 GITHUB_RUN_ATTEMPT=1 \
      PATH="$sandbox/bin:$PATH" bash "$repo_root/.github/scripts/service-ci-steps/warm-gradle-cache.sh"
  ) > "$sandbox/output.log" 2>&1
  [ "$(cat "$sandbox/count")" = 2 ]
  [ "$(cat "$sandbox/healer")" = trusted ]
  [ ! -e "$sandbox/poison" ]
  service_ci_provider_task_reuse_is_proven \
    "$sandbox/runtime/provider-pact-task-openbank-card-service-42-1.proof" openbank-card-service
done
echo 'service-ci provider task listing and trusted warm-cache self-test passed'
