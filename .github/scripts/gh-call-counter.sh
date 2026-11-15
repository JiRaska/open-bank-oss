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
# Python scripts are counted too: install also puts gh_call_counter_site/sitecustomize.py on PYTHONPATH,
# which logs every urllib/requests call to the API host into the same log (rows marked `py`).
# LOWER BOUND: one invocation with --paginate may be many requests, so those are counted separately, and
# Octokit in github-script or requests made by an action never pass through either counter.
#
# BUDGET: .github/api-call-budgets.yaml declares the most requests one run of a counted job may send.
# report compares the total against it and emits `::warning title=api-call-budget::` above it (or exits 1
# with API_CALL_BUDGET_MODE=enforce, which the self-test uses to prove the comparison can fail).
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
  mkdir -p "$dir/py" && cp "$(dirname "${BASH_SOURCE[0]}")/gh_call_counter_site/sitecustomize.py" "$dir/py/" || true
}

budget_for() {  # label -> declared budget, or empty
  local file="${API_CALL_BUDGETS:-$(dirname "${BASH_SOURCE[0]}")/../api-call-budgets.yaml}"
  [ -f "$file" ] || return 0
  grep -F "\"$1\":" "$file" | head -1 | sed -E 's/.*:[[:space:]]*([0-9]+).*/\1/'
}

report() {
  local dir="$1" label="$2" log="$1/calls.tsv" line total py budget over=0
  if [ ! -f "$log" ]; then
    line="$label unavailable (counter not installed)"
  else
    total=$(wc -l < "$log" | tr -d ' ')
    py=$(cut -f2 "$log" | grep -c '^py ')
    line="$label total=$total gh=$((total - py)) py=$py paginate=$(cut -f3 "$log" | grep -c '^paginate$')"
    line="$line top=$(cut -f1 "$log" | sort | uniq -c | sort -rn | head -5 | awk '{printf "%s:%s,", $2, $1}')"
    budget="$(budget_for "$label")"
    if [ -z "$budget" ]; then
      line="$line budget=undeclared"
    else
      line="$line budget=$budget"
      if [ "$total" -gt "$budget" ]; then
        echo "::warning title=api-call-budget::$label sent $total GitHub API requests, over its declared budget of $budget (.github/api-call-budgets.yaml) - the step summary lists the callers"
        over=1
      fi
    fi
  fi
  echo "::notice title=gh-call-count::$line"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ] && [ -f "$log" ]; then
    { echo "gh-call-count: $line"; echo '```'; cut -f1,2 "$log" | sort | uniq -c | sort -rn | head -30; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
  fi
  if [ "$over" = 1 ] && [ "${API_CALL_BUDGET_MODE:-warn}" = enforce ]; then return 1; fi
  return 0
}

self_test() {
  local t bad=0 out rc self caller
  self="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
  t="$(mktemp -d)"; mkdir -p "$t/real"
  printf '#!/usr/bin/env bash\nprintf "out:%%s\\n" "$*"; echo err-line >&2; exit 7\n' > "$t/real/gh"
  chmod +x "$t/real/gh"
  n=0
  check() { n=$((n + 1)); if [ "$2" != "$3" ]; then echo "FAIL $1: [$2] != [$3]"; bad=1; fi; }
  PATH="$t/real:$PATH" bash "$self" install "$t/c" >/dev/null
  caller="$t/check-x.sh"
  printf '#!/usr/bin/env bash\n"%s/c/gh" "$@"\n' "$t" > "$caller"
  out="$(bash "$caller" api 'repos/o/r/pulls?state=open' --paginate -f body=SECRETVALUE 2>"$t/e")"; rc=$?
  check "stdout passes through" "$out" "out:api repos/o/r/pulls?state=open --paginate -f body=SECRETVALUE"
  check "exit code passes through" "$rc" 7
  check "stderr passes through" "$(cat "$t/e")" err-line
  check "log line" "$(cat "$t/c/calls.tsv")" "$(printf 'check-x.sh\tapi repos/o/r/pulls\tpaginate')"
  check "no field value logged" "$(grep -c SECRETVALUE "$t/c/calls.tsv")" 0
  check "report counts" "$(bash "$self" report "$t/c" job | grep -c 'total=1 gh=1 py=0 paginate=1 top=check-x.sh:1')" 1
  rm -f "$t/c/calls.tsv"; chmod 555 "$t/c"
  check "unwritable log still runs gh" "$("$t/c/gh" pr view 2>/dev/null)" "out:pr view"
  chmod 755 "$t/c"
  check "missing counter is not read as zero" "$(bash "$self" report "$t/none" job | grep -c 'unavailable')" 1
  # Python: a urllib request to the API host is logged (path, no query), another host is not, and the
  # request still goes out (refused by a closed local port, after the line is written).
  : > "$t/c/calls.tsv"
  GH_CALL_COUNTER_LOG="$t/c/calls.tsv" GITHUB_API_URL="http://127.0.0.1:9" PYTHONPATH="$t/c/py" python3 -c '
import urllib.request, urllib.error
for u in ("http://127.0.0.1:9/repos/o/r/pulls?token=SECRETVALUE", "http://localhost:9/other"):
    try: urllib.request.urlopen(u, timeout=2)
    except (urllib.error.URLError, OSError): pass
' 2>/dev/null
  check "python request logged" "$(cat "$t/c/calls.tsv")" "$(printf -- '-c\tpy GET /repos/o/r/pulls\t-')"
  check "python query not logged" "$(grep -c SECRETVALUE "$t/c/calls.tsv")" 0
  check "python without counter env logs nothing" "$(PYTHONPATH="$t/c/py" python3 -c 'pass'; wc -l < "$t/c/calls.tsv" | tr -d ' ')" 1
  # Budget: over budget warns and fails under enforce; within budget is silent; no entry says so.
  printf '"job/over": 0\n"job/ok": 5\n' > "$t/budgets.yaml"
  out="$(API_CALL_BUDGETS="$t/budgets.yaml" API_CALL_BUDGET_MODE=enforce bash "$self" report "$t/c" job/over)"; rc=$?
  check "over budget fails under enforce" "$rc" 1
  check "over budget warns" "$(grep -c '::warning title=api-call-budget::job/over sent 1 ' <<< "$out")" 1
  out="$(API_CALL_BUDGETS="$t/budgets.yaml" API_CALL_BUDGET_MODE=enforce bash "$self" report "$t/c" job/ok)"; rc=$?
  check "within budget passes" "$rc:$(grep -c 'api-call-budget' <<< "$out")" "0:0"
  check "over budget only warns by default" "$(API_CALL_BUDGETS="$t/budgets.yaml" bash "$self" report "$t/c" job/over >/dev/null; echo $?)" 0
  check "undeclared budget is reported" "$(API_CALL_BUDGETS="$t/budgets.yaml" bash "$self" report "$t/c" job/none | grep -c 'budget=undeclared')" 1
  rm -rf "$t"
  echo "SUBJECTS=$n"
  echo "gh-call-counter self-test failed=$bad"
  return "$bad"
}

dir="${RUNNER_TEMP:-/tmp}/gh-call-counter"
case "${1:-}" in
  install)
    install_shim "${2:-$dir}" || exit 0
    if [ -n "${GITHUB_PATH:-}" ]; then echo "${2:-$dir}" >> "$GITHUB_PATH"; fi
    if [ -n "${GITHUB_ENV:-}" ] && [ -f "${2:-$dir}/py/sitecustomize.py" ]; then
      { echo "GH_CALL_COUNTER_LOG=${2:-$dir}/calls.tsv"
        echo "PYTHONPATH=${2:-$dir}/py${PYTHONPATH:+:$PYTHONPATH}"; } >> "$GITHUB_ENV"
    fi
    exit 0 ;;
  report) report "${2:-$dir}" "${3:-${GITHUB_WORKFLOW:-?}/${GITHUB_JOB:-?}}"; exit $? ;;
  --self-test) self_test ;;
  *) echo "usage: $0 install|report|--self-test"; exit 2 ;;
esac
