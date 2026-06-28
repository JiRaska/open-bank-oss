#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# ADR allocator — create a new Architecture Decision Record with a collision-free
# number and a canonical header (ADR-0001 / ADR-0029 "governance as code").
#
# WHY THIS EXISTS — it removes the *cause* the adr-registry CI gate only *catches*:
#
#   The number an ADR gets is a "next sequential integer" picked by hand against
#   whatever tree the author happens to have. With parallel sessions (multi-agent +
#   Mac-mini + humans) that races three ways, all of which really happened (2026-06):
#     - against a STALE local tree   -> `origin/main` already moved on  (0120-0122),
#     - against `origin/main` only   -> ANOTHER open PR already claimed N (0110, #2425),
#     - hand-copied TEMPLATE.md      -> the H1 number is mistyped        (the 0113-0118 off-by-one).
#
#   This script makes the deterministic part deterministic and the racy part rare:
#     * the H1 number is DERIVED from the allocated number  -> off-by-one is impossible;
#     * the number is the max over the UNION of {local ∪ origin/main ∪ every open PR}
#       plus one, after a fresh `git fetch` -> the only residual race is two ADRs
#       authored in the same minute before either opens a PR. The CI gate
#       (.github/scripts/check-adr-registry.sh) remains the hard backstop for that tail.
#
# Body stays DRY: this script owns only the front-matter (number / title / date /
# two-axis status); everything from "## Context" down is spliced from TEMPLATE.md,
# so the template is still the single source for ADR structure.
#
# Usage:
#   docs/adr/new.sh "Same-account FX pocket exchange"
#   docs/adr/new.sh -n                      # dry-run: print the next free number only
#   docs/adr/new.sh --slug fx-exchange "Same-account FX pocket exchange"
#   docs/adr/new.sh --no-fetch --no-prs "…" # fully offline (origin/main as-cached only)
#
# Flags:
#   -n, --dry-run    print the next number and exit (no file written)
#   -s, --slug X     override the kebab-case filename slug (default: derived from title)
#       --author X   Author(s) value (default: git config user.name)
#       --no-fetch   skip `git fetch origin main` (offline; origin/main may be stale)
#       --no-prs     skip the open-PR scan (offline, or no gh)
#   -h, --help       this help
# -----------------------------------------------------------------------------
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || echo .)"
ADR_DIR="$ROOT/docs/adr"
TEMPLATE="$ADR_DIR/TEMPLATE.md"
REMOTE_REF="origin/main"

dry_run=0 slug="" author="" do_fetch=1 do_prs=1 title=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--dry-run) dry_run=1; shift ;;
    -s|--slug)    slug="$2"; shift 2 ;;
    --author)     author="$2"; shift 2 ;;
    --no-fetch)   do_fetch=0; shift ;;
    --no-prs)     do_prs=0; shift ;;
    -h|--help)    sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)           echo "unknown flag: $1" >&2; exit 2 ;;
    *)            title="${title:+$title }$1"; shift ;;
  esac
done

[[ -f "$TEMPLATE" ]] || { echo "error: $TEMPLATE not found" >&2; exit 1; }
if [[ $dry_run -eq 0 && -z "$title" ]]; then
  echo "error: a title is required (or use -n/--dry-run). See --help." >&2; exit 2
fi

note() { echo "adr-new: $*" >&2; }

# --- gather candidate numbers from every namespace that can already hold one ----
maxnum=0
consider() { local n=$((10#$1)); (( n > maxnum )) && maxnum=$n; return 0; }

# local working tree
shopt -s nullglob
for f in "$ADR_DIR"/[0-9]*.md; do b="$(basename "$f")"; consider "${b%%-*}"; done

# origin/main (the #1 root cause was authoring against a stale tree)
if [[ $do_fetch -eq 1 ]]; then
  git -C "$ROOT" fetch --quiet origin main 2>/dev/null \
    || note "could not fetch origin/main — number checked against the cached ref only."
fi
while IFS= read -r path; do
  b="$(basename "$path")"; [[ "$b" =~ ^[0-9]+- ]] && consider "${b%%-*}"
done < <(git -C "$ROOT" ls-tree -r --name-only "$REMOTE_REF" docs/adr 2>/dev/null || true)

# every OPEN pull request (catches in-flight ADRs not yet on main — the 0119 case),
# in a single gh call; best-effort so the tool still works offline / without gh.
inflight=""
if [[ $do_prs -eq 1 ]] && command -v gh >/dev/null 2>&1; then
  if pr_paths="$(gh pr list --state open --limit 300 --json files \
        -q '.[].files[].path | select(test("docs/adr/[0-9]+-"))' 2>/dev/null)"; then
    while IFS= read -r path; do
      [[ -z "$path" ]] && continue
      b="$(basename "$path")"; consider "${b%%-*}"; inflight="${inflight} ${b%%-*}"
    done <<< "$pr_paths"
  else
    note "open-PR scan failed (gh error) — in-flight ADRs not checked; CI gate is the backstop."
  fi
elif [[ $do_prs -eq 1 ]]; then
  note "gh not found — in-flight ADRs not checked; CI gate is the backstop."
fi

next=$(printf '%04d' $((maxnum + 1)))

if [[ $dry_run -eq 1 ]]; then
  echo "$next"
  [[ -n "${inflight// }" ]] && note "in-flight ADR numbers seen in open PRs:${inflight}"
  exit 0
fi

# --- derive slug + author, then scaffold -------------------------------------
if [[ -z "$slug" ]]; then
  slug="$(printf '%s' "$title" \
    | tr '[:upper:]' '[:lower:]' \
    | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//; s/-+/-/g')"
fi
[[ -n "$slug" ]] || { echo "error: empty slug from title '$title'" >&2; exit 1; }
[[ -z "$author" ]] && author="$(git -C "$ROOT" config user.name 2>/dev/null || echo '<name>')"

out="$ADR_DIR/${next}-${slug}.md"
[[ -e "$out" ]] && { echo "error: $out already exists" >&2; exit 1; }

# Front-matter (owned here) + body spliced from TEMPLATE.md ("## Context" onward).
{
  printf '# ADR-%s — %s\n\n' "$next" "$title"
  printf 'Date: %s\n' "$(date +%F)"
  printf 'Decision-Status: Proposed   <!-- Proposed | Accepted | Superseded by ADR-NNNN | Deprecated | Rejected -->\n'
  printf 'Delivery-Status: Planned    <!-- Planned | Partial | Shipped | N/A — decision-only -->\n'
  printf 'Author(s): %s\n\n' "$author"
  awk '/^## /{f=1} f' "$TEMPLATE"
} > "$out"

note "created $out"
[[ -n "${inflight// }" ]] && note "in-flight ADR numbers seen in open PRs:${inflight}"
note "next steps: write the ADR, then open a PR PROMPTLY so the number is claimed on origin."
note "verify locally: bash docs/adr/gen-index.sh && bash .github/scripts/check-adr-registry.sh"
echo "$out"
