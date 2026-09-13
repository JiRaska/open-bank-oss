#!/usr/bin/env bash
# Resolve the ONE issue a long-lived, machine-written ledger should be appended to.
#
# WHY THIS EXISTS (issue #5266)
# `auto-retry-cancelled.yml` picked its target with
#     gh issue list --state open --search "$TITLE in:title" --json number --jq '.[0].number'
# and `.[0]` pins nothing. Two open issues carried the byte-identical title
# "Flaky test observations (recorded from CI re-run evidence)" — #5266 (created 14:45Z) and
# #5285 (17:37Z, same day, same author `app/github-actions`, same labels, identical body —
# the duplicate is itself a product of this file's other defect, see NO AUTO-CREATE below).
# Measured 2026-08-30: the search returned #5285 first, so #5285 holds 85 flake records and
# #5266 holds 1 — and that 1 is the proof the ledger MIGRATES: the first record went to #5266
# at 16:19Z and every record from 17:37Z onward went to #5285. Nothing anywhere said so. A
# split ledger is worse than an absent one: each half reads as a low flake count and neither
# reader knows they are looking at part of the data.
#
# WHY NOT `--sort created --order asc`
# It would have picked #5266 today and still selects by a MUTABLE property of a candidate SET
# whose size nobody checks. The day a third same-titled issue appears it silently re-points
# again. The defect is not the ordering, it is that the identity was never pinned — so this
# script pins an identity and makes an ambiguous candidate set an ERROR rather than an
# arbitrary choice. Reintroducing "just take one" at this level would be the same bug one
# layer up.
#
# TWO MODES
#   --pinned <n>   The strongest form: the caller names the issue. We only VERIFY (open, and
#                  its title still matches) and print the number. No search, no ambiguity, and
#                  no auto-create, so a race can no longer mint a rival ledger. This is what
#                  the flake ledger uses.
#   (no --pinned)  Title mode, for an alarm issue that must be able to open itself when none
#                  exists. Search narrows; the DECISION is a client-side EXACT title match
#                  (GitHub's `in:title` is fuzzy and will hand back near-misses). Then:
#                  0 candidates -> create (only if --create-body-file was given), 1 -> use it,
#                  >1 -> exit 1 naming every candidate. Never `.[0]`.
#
# NO AUTO-CREATE ON THE PINNED PATH — that is the point, not an omission.
# GitHub's issue search index is eventually consistent, so `create` followed seconds later by
# `list --search` in a sibling run legitimately returns nothing and creates a second issue.
# That is the most likely origin of #5266/#5285 (2h52m apart, identical bodies). A pinned
# number cannot lose that race because it never asks the index a question.
#
# LOUD FAILURE IS THE CONTRACT. Every path that cannot name exactly one issue exits 1 with the
# reason on stderr. The caller is a `workflow_run` job whose red is addressed to nobody, so the
# workflow routes that red to the `raise-issue` alarm; silently appending somewhere plausible
# is the behaviour being removed.
#
# API COST: unchanged. Title mode makes the same single `gh issue list` call as before (the
# strictness is client-side, over the same response). Pinned mode makes one `gh issue view`
# instead of that one `gh issue list`. Net zero against the repo's 1,000 req/hr budget.
#
# `-R "$GH_REPO"` on every call: this runs in jobs with a sparse or absent checkout, where a
# bare `gh` dies with `failed to determine base repo` (#2898, 208 silent failures).
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
usage: resolve-tracking-issue.sh --title <title> [--pinned <number>]
                                 [--create-body-file <path>] [--create-label <label>]...
                                 [--status-file <path>]   # 'existing' | 'created'
       resolve-tracking-issue.sh --self-test
Prints the resolved issue number on stdout. Exits 1 if exactly one cannot be named.
USAGE
  exit 2
}

TITLE=""
PINNED=""
CREATE_BODY=""
STATUS_FILE=""
CREATE_LABELS=()

# `existing` or `created`, so a caller that appends a comment can avoid posting the same text
# twice into an issue whose BODY it just wrote. Written to a file rather than mixed into stdout
# because stdout is the issue number and nothing else.
note_status() { [ -n "$STATUS_FILE" ] && printf '%s' "$1" > "$STATUS_FILE"; return 0; }

resolve() {
  local repo="${GH_REPO:?GH_REPO must be set}"

  if [ -n "$PINNED" ]; then
    local meta state actual
    if ! meta=$(gh issue view "$PINNED" -R "$repo" --json number,state,title 2>&1); then
      echo "resolve-tracking-issue: pinned issue #${PINNED} could not be read: ${meta}" >&2
      return 1
    fi
    state=$(printf '%s' "$meta" | jq -r '.state')
    actual=$(printf '%s' "$meta" | jq -r '.title')
    if [ "$state" != "OPEN" ]; then
      echo "resolve-tracking-issue: pinned issue #${PINNED} is ${state}, not OPEN. The ledger is" >&2
      echo "  append-only and must not silently relocate: re-open it, or update the pinned" >&2
      echo "  number in the workflow in a reviewed PR." >&2
      return 1
    fi
    if [ "$actual" != "$TITLE" ]; then
      echo "resolve-tracking-issue: pinned issue #${PINNED} is titled" >&2
      echo "  '${actual}'" >&2
      echo "  but the caller expected '${TITLE}'. Refusing to append to an issue that may have" >&2
      echo "  been repurposed." >&2
      return 1
    fi
    note_status existing
    printf '%s\n' "$PINNED"
    return 0
  fi

  # Title mode. `in:title` is a fuzzy full-text match, so the response is a CANDIDATE list; the
  # exact-title filter below is what decides. Without it a differently-titled issue sharing
  # enough words is indistinguishable from the real one.
  local raw matches count
  raw=$(gh issue list -R "$repo" --state open --search "$TITLE in:title" \
          --limit 100 --json number,title)
  matches=$(printf '%s' "$raw" | jq --arg t "$TITLE" '[.[] | select(.title == $t) | .number] | sort')
  count=$(printf '%s' "$matches" | jq 'length')

  if [ "$count" -gt 1 ]; then
    echo "resolve-tracking-issue: ${count} open issues share the exact title" >&2
    echo "  '${TITLE}'" >&2
    echo "  -> $(printf '%s' "$matches" | jq -r 'map("#\(.)") | join(", ")')" >&2
    echo "  Refusing to pick one. Consolidate them, then pin the survivor with --pinned." >&2
    return 1
  fi

  if [ "$count" -eq 1 ]; then
    note_status existing
    printf '%s' "$matches" | jq -r '.[0]'
    return 0
  fi

  if [ -z "$CREATE_BODY" ]; then
    echo "resolve-tracking-issue: no open issue titled '${TITLE}' and no --create-body-file given." >&2
    return 1
  fi

  local args=(issue create -R "$repo" --title "$TITLE" --body-file "$CREATE_BODY")
  local l
  for l in ${CREATE_LABELS+"${CREATE_LABELS[@]}"}; do args+=(--label "$l"); done
  local url created
  url=$(gh "${args[@]}")
  created=$(printf '%s' "$url" | grep -oE '[0-9]+$' || true)
  if [ -z "$created" ]; then
    echo "resolve-tracking-issue: could not read an issue number out of 'gh issue create' output: ${url}" >&2
    return 1
  fi
  note_status created
  printf '%s\n' "$created"
}

# ── self-test ────────────────────────────────────────────────────────────────
# Drives the real `resolve` against a stub `gh` on PATH. Every case asserts the exit code AND
# the resolved number, and the ambiguous case is the prevent-proof: the OLD expression takes
# `.[0]` and is green, this one must be RED.
self_test() {
  local tmp; tmp=$(mktemp -d); trap 'rm -rf "$tmp"' RETURN
  mkdir -p "$tmp/bin"
  cat > "$tmp/bin/gh" <<'STUB'
#!/usr/bin/env bash
case "$1 $2" in
  "issue view")
    case "$3" in
      5285) echo '{"number":5285,"state":"OPEN","title":"Ledger"}' ;;
      5266) echo '{"number":5266,"state":"CLOSED","title":"Ledger"}' ;;
      4242) echo '{"number":4242,"state":"OPEN","title":"Something else entirely"}' ;;
      *) echo 'gh: Could not resolve to an Issue' >&2; exit 1 ;;
    esac ;;
  "issue list") cat "${STUB_LIST_JSON}" ;;
  "issue create") echo "https://github.com/o/r/issues/9001" ;;
  *) echo "stub gh: unexpected: $*" >&2; exit 99 ;;
esac
STUB
  chmod +x "$tmp/bin/gh"
  PATH="$tmp/bin:$PATH"; export GH_REPO="o/r"

  local fails=0 n=0
  check() { # name expected_rc expected_out
    n=$((n + 1))
    local name="$1" want_rc="$2" want_out="${3-}" out rc
    set +e; out=$(resolve 2>"$tmp/err"); rc=$?; set -e
    if [ "$rc" != "$want_rc" ] || { [ -n "$want_out" ] && [ "$out" != "$want_out" ]; }; then
      echo "FAIL  ${name}: rc=${rc} out='${out}' (wanted rc=${want_rc} out='${want_out}')" >&2
      sed 's/^/      /' "$tmp/err" >&2
      fails=$((fails + 1))
    else
      echo "ok    ${name}"
    fi
  }

  TITLE="Ledger"; CREATE_BODY=""; CREATE_LABELS=(); STATUS_FILE="$tmp/status"


  PINNED=5285; check "pinned + open + title matches -> that number" 0 5285
  PINNED=5266; check "pinned but CLOSED -> loud failure, never a fallback search" 1
  PINNED=4242; check "pinned but retitled -> loud failure" 1
  PINNED=9999; check "pinned but unreadable -> loud failure" 1

  PINNED=""
  # THE PREVENT-PROOF. This is the real #5266/#5285 candidate set. The old `.[0]` expression
  # returns 5285 and exits 0; this one must refuse.
  export STUB_LIST_JSON="$tmp/dupes.json"
  cat > "$tmp/dupes.json" <<'J'
[{"number":5285,"title":"Ledger"},{"number":5266,"title":"Ledger"}]
J
  check "two exact-title candidates -> REFUSES (old code silently took .[0])" 1
  n=$((n + 1))
  if [ "$(jq -r '.[0].number' "$tmp/dupes.json")" = "5285" ]; then
    echo "ok    control: the old '.[0]' expression does answer 5285 on this same input"
  else
    echo "FAIL  control: stub input no longer reproduces the ambiguity" >&2; fails=$((fails + 1))
  fi

  export STUB_LIST_JSON="$tmp/one.json"
  echo '[{"number":5285,"title":"Ledger"}]' > "$tmp/one.json"
  check "exactly one exact-title candidate -> that number" 0 5285

  # Fuzzy `in:title` near-miss: search returns it, exact match must reject it.
  export STUB_LIST_JSON="$tmp/fuzzy.json"
  echo '[{"number":7,"title":"Ledger (archived)"},{"number":5285,"title":"Ledger"}]' > "$tmp/fuzzy.json"
  check "fuzzy near-miss is not a candidate -> the exact one" 0 5285

  export STUB_LIST_JSON="$tmp/none.json"; echo '[]' > "$tmp/none.json"
  check "no candidate and no --create-body-file -> loud failure, not a silent no-op" 1
  CREATE_BODY="$tmp/body.md"; echo body > "$CREATE_BODY"; CREATE_LABELS=(tech-debt)
  check "no candidate + --create-body-file -> creates and returns the new number" 0 9001
  n=$((n + 1))
  # The caller writes the issue BODY when it creates, and appends a COMMENT otherwise. Without
  # this distinction a freshly created alarm issue gets its own text posted straight back as a
  # duplicate comment.
  if [ "$(cat "$tmp/status")" = "created" ]; then
    echo "ok    status-file says 'created' on the create path"
  else
    echo "FAIL  status-file said '$(cat "$tmp/status")', wanted 'created'" >&2; fails=$((fails + 1))
  fi

  export STUB_LIST_JSON="$tmp/three.json"
  echo '[{"number":1,"title":"Ledger"},{"number":2,"title":"Ledger"},{"number":3,"title":"Ledger"}]' > "$tmp/three.json"
  check "three candidates + create allowed -> still REFUSES (never creates a 4th)" 1

  # `SUBJECTS=` is the gate runner's floor check (min_subjects): it is what stops this
  # suite from going green after someone deletes half its cases.
  echo "SUBJECTS=${n}  # assertions driven against the stub gh"
  echo "${n} assertions, ${fails} failed"
  [ "$fails" -eq 0 ]
}

[ $# -gt 0 ] || usage
if [ "$1" = "--self-test" ]; then self_test; exit; fi

while [ $# -gt 0 ]; do
  case "$1" in
    --title)            TITLE="$2"; shift 2 ;;
    --pinned)           PINNED="$2"; shift 2 ;;
    --create-body-file) CREATE_BODY="$2"; shift 2 ;;
    --status-file)      STATUS_FILE="$2"; shift 2 ;;
    --create-label)     CREATE_LABELS+=("$2"); shift 2 ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done
[ -n "$TITLE" ] || usage
resolve
