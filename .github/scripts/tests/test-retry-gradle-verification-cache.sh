#!/usr/bin/env bash
set -euo pipefail

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
export GRADLE_USER_HOME="$tmp/gradle"
export RETRY_TEST_COUNT="$tmp/count"
export RETRY_TEST_MODE=before-task
runner=.github/scripts/retry-gradle-verification-cache.sh
cache_entry="$GRADLE_USER_HOME/caches/modules-2/files-2.1/com.example/foo/1.2.3"

cat > "$tmp/fake-build" <<'EOF'
#!/usr/bin/env bash
count=0
[ ! -f "$RETRY_TEST_COUNT" ] || count="$(cat "$RETRY_TEST_COUNT")"
count=$((count + 1))
printf '%s\n' "$count" > "$RETRY_TEST_COUNT"
if [ "$count" -eq 1 ]; then
  if [ "$RETRY_TEST_MODE" = after-task ]; then
    echo '> Task :compileKotlin'
  fi
  if [ "$RETRY_TEST_MODE" != unrelated ]; then
    echo '1 artifact failed verification:'
    echo '  - foo-1.2.3.jar (com.example:foo:1.2.3) from repository maven'
  fi
  exit 19
fi
echo '> Task :test'
EOF
chmod +x "$tmp/fake-build"

mkdir -p "$cache_entry"
bash "$runner" -- "$tmp/fake-build" > "$tmp/result"
[ "$(cat "$RETRY_TEST_COUNT")" = 2 ]
[ ! -e "$cache_entry" ]
grep -Fq 'retrying build once' "$tmp/result"

printf '0\n' > "$RETRY_TEST_COUNT"
export RETRY_TEST_MODE=after-task
mkdir -p "$cache_entry"
if bash "$runner" -- "$tmp/fake-build" > "$tmp/result"; then
  echo 'post-task failure was retried' >&2
  exit 1
else
  [ "$?" -eq 19 ]
fi
[ "$(cat "$RETRY_TEST_COUNT")" = 1 ]
[ -d "$cache_entry" ]

printf '0\n' > "$RETRY_TEST_COUNT"
export RETRY_TEST_MODE=unrelated
if bash "$runner" -- "$tmp/fake-build" > "$tmp/result"; then
  echo 'unrelated failure was retried' >&2
  exit 1
else
  [ "$?" -eq 19 ]
fi
[ "$(cat "$RETRY_TEST_COUNT")" = 1 ]

echo 'retry-gradle-verification-cache: 3 controls passed'
