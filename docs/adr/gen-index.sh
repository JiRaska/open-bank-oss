#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Generate the three DERIVED views of the ADR registry from the ADRs themselves:
#
#   README.md    the human index -- one table row per ADR, plus a by-tag map.
#   index.json   the machine registry -- for the admin UI, audit exports, and any
#                tool that wants the decision history as data rather than prose.
#   DIGEST.md    the whole decision history as one-line summaries (~16k tokens).
#                THIS is the file a reviewer, an auditor or an AI agent reads: the
#                full fleet is ~1.6 MB / ~400k tokens, so nobody -- human or model --
#                ever loads it, which made a 170+-ADR registry unsearchable. The
#                digest restores "hold the whole thing in your head, then open the
#                two files that matter".
#
# DERIVED DATA: never hand-edit any of the three. Edit the ADRs (or this script)
# and re-run `bash docs/adr/gen-index.sh`. check-adr-registry.sh fails the PR if a
# committed artefact differs from what this script regenerates.
#
# Input is the YAML front-matter defined in SCHEMA.md, read through the single
# shared parser in lib-frontmatter.sh -- the generator and the validator must never
# disagree about what a field says. (Before the schema there were four header
# conventions in the fleet and this script carried a fallback regex per convention.)
# -----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck source=lib-frontmatter.sh
. ./lib-frontmatter.sh

# Fail loudly on duplicate ADR numbers -- a self-colliding registry is exactly the
# "governance-as-code that cannot govern itself" footgun this index exists to prevent.
dupes=$(ls [0-9]*.md | sed -E 's/-.*//' | sort | uniq -d)
if [ -n "$dupes" ]; then
  echo "ERROR: duplicate ADR number(s): $dupes" >&2
  exit 1
fi

adrs=$(ls [0-9]*.md | sort)

# Title is derived from the H1, and the number from the filename, so neither is
# duplicated into front-matter where it could drift. See SCHEMA.md.
# One grep, then pure bash. This used to pipe into `perl -CSD`, and a perl
# interpreter start per ADR was ~10% of this script's total runtime for three
# substitutions. The dash class is written as an alternation rather than a bracket
# expression on purpose: em-dash and en-dash are multi-byte in UTF-8, and inside a
# bracket expression their bytes can be matched individually under a C locale.
# Takes the H1 LINE (supplied by fm_extract_many's `!h1`), not a filename — so no
# grep, and the file is read once for everything. This used to pipe into `perl -CSD`;
# a perl interpreter start per ADR was ~10% of this script's runtime for three
# substitutions. The dash class is an alternation rather than a bracket expression on
# purpose: em-dash and en-dash are multi-byte in UTF-8, and inside a bracket
# expression their bytes can be matched individually under a C locale.
h1_title_from() {
  local t=$1
  [[ $t =~ ^\#[[:space:]]+(.*)$ ]] && t=${BASH_REMATCH[1]}
  [[ $t =~ ^ADR-[0-9]+[[:space:]]*(:|—|–|-)[[:space:]]+(.*)$ ]] && t=${BASH_REMATCH[2]}
  [[ $t =~ ^[0-9]+\.[[:space:]]+(.*)$ ]] && t=${BASH_REMATCH[1]}
  printf '%s\n' "$t"
}

# Present an enum to humans: `n-a` -> `N/A`, otherwise capitalise.
# Called twice per ADR; the old form forked a subshell and a `tr` each time.
# Bash 3.2 (the macOS default) has no ${var^}, so capitalise with a case.
pretty() {
  case "$1" in
    n-a) printf 'N/A\n'; return ;;
  esac
  local first=${1:0:1} rest=${1:1}
  case "$first" in
    a) first=A ;; b) first=B ;; c) first=C ;; d) first=D ;; e) first=E ;;
    f) first=F ;; g) first=G ;; h) first=H ;; i) first=I ;; j) first=J ;;
    k) first=K ;; l) first=L ;; m) first=M ;; n) first=N ;; o) first=O ;;
    p) first=P ;; q) first=Q ;; r) first=R ;; s) first=S ;; t) first=T ;;
    u) first=U ;; v) first=V ;; w) first=W ;; x) first=X ;; y) first=Y ;;
    z) first=Z ;;
  esac
  printf '%s%s\n' "$first" "$rest"
}

# Field separator for the cached table. NOT a tab: tab is IFS whitespace, so bash
# `read` collapses runs of it and an ADR with an empty list field would silently
# shift every later column. \037 (US) is non-whitespace and cannot occur in an ADR.
SEP=$'\037'

# Backslash first, then quote — reversing the order would double-escape the
# backslashes this step just introduced.
json_escape() {
  local v=${1//\\/\\\\}
  printf '%s' "${v//\"/\\\"}"
}

# --- one pass over the fleet, cached as a $SEP-separated table -----------------
# Bash 3.2 (the macOS default) has no associative arrays, so the cache is one
# delimited table held in a variable and sliced with awk. ~180 rows -- fine.
# ONE awk process for the whole fleet (fm_extract_many), then pure bash. This loop
# used to run fm_extract + grep per ADR and slice the result with forking helpers:
# ~30 processes per ADR, ~6800 for the fleet, and 62 s of CPU on a check that runs on
# every PR (issue #3108). Nothing about the parse changed — only how many times the
# process table is touched.
#
# `flush_row` is called when the stream moves to a new file, and once more at the end.
# Reading the stream with `while read` in the CURRENT shell (a here-string, not a
# pipe) matters: a pipeline would put the loop in a subshell and TABLE would be
# discarded at the end of it — the row-building would run, and the table would be
# empty.
TABLE=""
cur=""; c_title=""; c_dec=""; c_del=""; c_date=""; c_sum=""
c_tags=""; c_repos=""; c_sup=""; c_supby=""; c_status=""

join_list() {  # newline-separated -> comma-separated, no `paste`
  local out="" line
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    out="${out}${out:+,}${line}"
  done <<< "$1"
  printf '%s' "$out"
}

flush_row() {
  [[ -z "$cur" ]] && return 0
  if [[ "$c_status" != "ok" ]]; then
    echo "ERROR: $cur: no valid front-matter block (see SCHEMA.md)" >&2
    exit 1
  fi
  local num=${cur%%-*}
  TABLE="${TABLE}${num}${SEP}${cur}${SEP}${c_title}${SEP}${c_dec}${SEP}${c_del}${SEP}${c_date}${SEP}${c_tags}${SEP}${c_repos}${SEP}${c_sup}${SEP}${c_supby}${SEP}${c_sum}
"
}

while IFS=$'\t' read -r file key value; do
  [[ -z "$file" ]] && continue
  if [[ "$file" != "$cur" ]]; then
    flush_row
    cur=$file; c_title=""; c_dec=""; c_del=""; c_date=""; c_sum=""
    c_tags=""; c_repos=""; c_sup=""; c_supby=""; c_status=""
  fi
  case "$key" in
    # First occurrence wins, matching fm_field's `exit` on first match.
    decision-status)  [[ -z "$c_dec"   ]] && c_dec=$value ;;
    delivery-status)  [[ -z "$c_del"   ]] && c_del=$value ;;
    date)             [[ -z "$c_date"  ]] && c_date=$value ;;
    summary)          [[ -z "$c_sum"   ]] && c_sum=$(fm_unquote "$value") ;;
    tags)             [[ -z "$c_tags"  ]] && c_tags=$(join_list "$(fm_list "$value")") ;;
    delivery-repos)   [[ -z "$c_repos" ]] && c_repos=$(join_list "$(fm_list "$value")") ;;
    supersedes)       [[ -z "$c_sup"   ]] && c_sup=$(join_list "$(fm_list "$value")") ;;
    superseded-by)    [[ -z "$c_supby" ]] && c_supby=$(join_list "$(fm_list "$value")") ;;
    '!h1')            c_title=$(h1_title_from "$value") ;;
    '!status')        c_status=$value ;;
  esac
done <<< "$(fm_extract_many $adrs)"
flush_row

# --- numbering gaps, computed (never hand-typed, so the claim cannot rot) ------
gaps=$(printf '%s' "$TABLE" | awk -F"$SEP" 'NF{print $1+0}' | sort -n | awk '
  NR>1 && $1 > prev+1 { for (g = prev+1; g < $1; g++) printf "%04d ", g }
  { prev = $1 }')

count=$(printf '%s' "$TABLE" | grep -c . || true)

# =============================== README.md ===================================
{
  echo "# Architecture Decision Records — index"
  echo
  echo "_Generated by \`docs/adr/gen-index.sh\` — do not hand-edit. Re-run after adding or"
  echo "changing an ADR._"
  echo
  echo "**Reading ${count} ADRs:** don't. [DIGEST.md](DIGEST.md) is the entire decision history"
  echo "as one-line summaries — read that, then open only what you need. [index.json](index.json)"
  echo "is the same data for tools. Start with [ADR-0001](0001-record-architecture-decisions.md);"
  echo "governance is [0029](0029-versioning-release-and-governance-as-code.md) /"
  echo "[0030](0030-supply-chain-security-and-ssdlc-hardening.md)."
  echo
  echo "Create a new ADR with \`docs/adr/new.sh \"Title\"\` — it allocates a collision-free"
  echo "number (across local, \`origin/main\`, and open PRs) and scaffolds the front-matter"
  echo "defined in [SCHEMA.md](SCHEMA.md). The two status axes are independent: **Decision**"
  echo "is whether the decision stands, **Delivery** is whether it was built."
  echo
  echo "| ADR | Title | Decision | Delivery | Tags | Repos |"
  echo "|----:|-------|----------|----------|------|-------|"
  printf '%s' "$TABLE" | while IFS="$SEP" read -r num f title decision delivery date tags repos sup supby summary; do
    [ -z "$num" ] && continue
    d=$(pretty "$decision")
    # A superseded decision links to what replaced it -- that pointer is the whole
    # value of the status, so burying it in the file would waste the index.
    if [ -n "$supby" ]; then
      links=""
      for t in $(echo "$supby" | tr ',' ' '); do
        tf=$(ls "$t"-*.md 2>/dev/null | head -1)
        links="${links}${links:+, }[ADR-$t]($tf)"
      done
      d="Superseded by $links"
    fi
    printf '| [%s](%s) | %s | %s | %s | %s | %s |\n' \
      "$num" "$f" "$title" "$d" "$(pretty "$delivery")" \
      "${tags:-—}" "${repos:-—}"
  done

  echo
  echo "## By tag"
  echo
  echo "_Tags come from the closed vocabulary in [tags.txt](tags.txt); the validator rejects"
  echo "anything else, so this map cannot sprawl into synonyms._"
  echo
  for tag in $(printf '%s' "$TABLE" | awk -F"$SEP" 'NF{print $7}' | tr ',' '\n' | grep -v '^$' | sort -u); do
    nums=$(printf '%s' "$TABLE" | awk -F"$SEP" -v t="$tag" \
      'NF { n = split($7, a, ","); for (i = 1; i <= n; i++) if (a[i] == t) print $1 }' \
      | sort | paste -sd' ' -)
    printf -- '- **%s** — %s\n' "$tag" "$nums"
  done

  if [ -n "$gaps" ]; then
    echo
    echo "---"
    echo
    echo "**Numbering gaps:** $(echo "$gaps" | sed 's/ $//')"
    echo
    echo "These numbers correspond to no file in this repo's history — confirmed by"
    echo "\`git log --diff-filter=A\` across all branches, not just an absent current file."
    echo "The list above is computed from the files, so it cannot rot into a stale claim."
    echo
    echo "Known history: ADR-0132 was one of these gaps until it was cited in code/config"
    echo "comments before the file existed, and has since been written down properly — a real,"
    echo "gap-filling backfill. ADR-0128 turned out to be a plain typo for"
    echo "[ADR-0037](0037-anacredit-credit-exposure-reporting.md) in two gitops comments"
    echo "(fixed); it stays a gap because no ADR-0128 decision was ever made. The pre-launch"
    echo "gaps are formally closed as unrecoverable: no decision record, commit, or PR in this"
    echo "repo's full history accounts for any of them, and no one with access to"
    echo "pre-public-launch history has identified one. Treat them as numbering artifacts from"
    echo "before the public repo transition, not pending ADRs — no ADR will be retroactively"
    echo "written for them absent new evidence."
  fi
} > README.md

# =============================== DIGEST.md ===================================
{
  echo "# ADR digest — the whole decision history, one line each"
  echo
  echo "_Generated by \`docs/adr/gen-index.sh\` — do not hand-edit._"
  echo
  echo "The ADR fleet is ~400k tokens. This file is the same history in ~16k — a 25x"
  echo "reduction, and the difference between 'loadable in one read' and 'not loadable at all'."
  echo "Read it in full — as a reviewer, an auditor, or an AI agent — before deciding which"
  echo "ADRs to actually open. Each line is one ADR's \`summary\` field (see [SCHEMA.md](SCHEMA.md));"
  echo "if a line does not tell you what was decided, fix that ADR's summary, not this file."
  echo
  echo "Format: **number** · title · \`decision/delivery\` · summary"
  echo
  printf '%s' "$TABLE" | while IFS="$SEP" read -r num f title decision delivery date tags repos sup supby summary; do
    [ -z "$num" ] && continue
    printf -- '- **[%s](%s)** · %s · `%s/%s` · %s\n' \
      "$num" "$f" "$title" "$decision" "$delivery" "$summary"
  done
} > DIGEST.md

# =============================== index.json ==================================
# Four processes per call, four calls per ADR, in pure bash instead.
json_arr() {
  local out="" e parts=() IFS=','
  read -ra parts <<< "$1"
  IFS=$' \t\n'
  for e in ${parts[@]+"${parts[@]}"}; do   # empty array + set -u = unbound in bash 3.2
    [[ -z "$e" ]] && continue
    out="${out}${out:+,}\"${e}\""
  done
  printf '%s' "$out"
}
{
  echo '{'
  echo '  "_comment": "DERIVED — generated by docs/adr/gen-index.sh from ADR front-matter (docs/adr/SCHEMA.md). Do not hand-edit.",'
  printf '  "count": %s,\n' "$count"
  echo '  "adrs": ['
  printf '%s' "$TABLE" | while IFS="$SEP" read -r num f title decision delivery date tags repos sup supby summary; do
    [ -z "$num" ] && continue
    printf '    {"number": "%s", "file": "%s", "title": "%s", "date": "%s", "decision_status": "%s", "delivery_status": "%s", "tags": [%s], "delivery_repos": [%s], "supersedes": [%s], "superseded_by": [%s], "summary": "%s"},\n' \
      "$num" "$f" "$(json_escape "$title")" "$date" "$decision" "$delivery" \
      "$(json_arr "$tags")" "$(json_arr "$repos")" "$(json_arr "$sup")" "$(json_arr "$supby")" \
      "$(json_escape "$summary")"
  done | sed '$ s/,$//'
  echo '  ]'
  echo '}'
} > index.json

echo "Wrote README.md, DIGEST.md, index.json ($count ADRs indexed)."
