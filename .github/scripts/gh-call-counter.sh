#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Counts the gh invocations a job makes, by caller and endpoint. rate_limit reports only the bucket shared
# by the whole repository, so it cannot say which job spent the quota.
#
#   gh-call-counter.sh install   puts a counting gh first on PATH (after a checkout)
#   gh-call-counter.sh report    prints one notice line and writes the table to the step summary
#   gh-call-counter.sh --self-test
#
# LOWER BOUND: one invocation with --paginate may be many requests, so those are counted separately, and
# Octokit in github-script or requests made by an action never pass through gh.
# The shim fails open: output, exit code and arguments are the real gh's. It logs only the subcommand, the
# endpoint path without its query, and the calling script, never field values.
set -u

install_shim() {
  local dir="$1" real
  real="$(command -v gh)" || { echo "gh-call-counter: no gh on PATH"; return 1; }
  mkdir -p "$dir" || return 1
  cat > "$dir/gh" <<SHIM
#!/usr/bin/env bash
{
  pag=-; for a in "\$@"; do [ "\$a" = "--paginate" ] && pag=paginate; done
  what="\${1:-none} \${2:-none}"; what="\${what%%\\?*}"
  caller="\$(ps -o args= -p "\$PPID" 2>/dev/null | tr ' ' '\\n' | grep -m1 -E '\\.(py|sh)\$')"
  caller="\${caller##*/}"
  printf '%s\\t%s\\t%s\\n' "\${caller:-unknown}" "\$(printf '%s' "\$what" | tr -c 'a-zA-Z0-9/._ -' '_' | cut -c1-120)" "\$pag" >> "$dir/calls.tsv"
} 2>/dev/null || true
exec "$real" "\$@"
SHIM
  chmod +x "$dir/gh" || return 1
  : > "$dir/calls.tsv" || return 1
}

report() {
  local dir="$1" label="$2" log="$1/calls.tsv" line
  if [ ! -f "$log" ]; then
    line="$label unavailable (counter not installed)"
  else
    line="$label total=$(wc -l < "$log" | tr -d ' ') paginate=$(cut -f3 "$log" | grep -c '^paginate$')"
    line="$line top=$(cut -f1 "$log" | sort | uniq -c | sort -rn | head -5 | awk '{printf "%s:%s,", $2, $1}')"
  fi
  echo "::notice title=gh-call-count::$line"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ] && [ -f "$log" ]; then
    { echo "gh-call-count: $line"; echo '```'; cut -f1,2 "$log" | sort | uniq -c | sort -rn | head -30; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
  fi
  return 0
}

self_test() {
  local t bad=0 out rc self caller
  self="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
  t="$(mktemp -d)"; mkdir -p "$t/real"
  printf '#!/usr/bin/env bash\nprintf "out:%%s\\n" "$*"; echo err-line >&2; exit 7\n' > "$t/real/gh"
  chmod +x "$t/real/gh"
  check() { if [ "$2" != "$3" ]; then echo "FAIL $1: [$2] != [$3]"; bad=1; fi; }
  PATH="$t/real:$PATH" bash "$self" install "$t/c" >/dev/null
  caller="$t/check-x.sh"
  printf '#!/usr/bin/env bash\n"%s/c/gh" "$@"\n' "$t" > "$caller"
  out="$(bash "$caller" api 'repos/o/r/pulls?state=open' --paginate -f body=SECRETVALUE 2>"$t/e")"; rc=$?
  check "stdout passes through" "$out" "out:api repos/o/r/pulls?state=open --paginate -f body=SECRETVALUE"
  check "exit code passes through" "$rc" 7
  check "stderr passes through" "$(cat "$t/e")" err-line
  check "log line" "$(cat "$t/c/calls.tsv")" "$(printf 'check-x.sh\tapi repos/o/r/pulls\tpaginate')"
  check "no field value logged" "$(grep -c SECRETVALUE "$t/c/calls.tsv")" 0
  check "report counts" "$(bash "$self" report "$t/c" job | grep -c 'total=1 paginate=1 top=check-x.sh:1')" 1
  rm -f "$t/c/calls.tsv"; chmod 555 "$t/c"
  check "unwritable log still runs gh" "$("$t/c/gh" pr view 2>/dev/null)" "out:pr view"
  chmod 755 "$t/c"
  check "missing counter is not read as zero" "$(bash "$self" report "$t/none" job | grep -c 'unavailable')" 1
  rm -rf "$t"
  echo "gh-call-counter self-test failed=$bad"
  return "$bad"
}

dir="${RUNNER_TEMP:-/tmp}/gh-call-counter"
case "${1:-}" in
  install)
    install_shim "${2:-$dir}" || exit 0
    if [ -n "${GITHUB_PATH:-}" ]; then echo "${2:-$dir}" >> "$GITHUB_PATH"; fi
    exit 0 ;;
  report) report "${2:-$dir}" "${3:-${GITHUB_WORKFLOW:-?}/${GITHUB_JOB:-?}}" ;;
  --self-test) self_test ;;
  *) echo "usage: $0 install|report|--self-test"; exit 2 ;;
esac
