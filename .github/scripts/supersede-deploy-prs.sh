#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Close every OLDER open bot deploy PR when a newer one opens.
#
# Why this exists: a deploy PR rewrites an image tag to the newest build. Two of them are therefore
# never additive — the newer one already contains everything the older one would have done, and the
# older one is worthless the moment it exists. But both edit the SAME manifest line, so whichever
# merges first leaves the rest DIRTY, and a DIRTY PR with green checks and auto-merge armed reads as
# healthy from every angle except mergeability. Measured 2026-07-26: SEVEN open admin-ui deploy PRs
# out of 23 open PRs in the whole repo — 30% of the review queue was superseded deploy noise, all of
# it accumulated within three hours, and it grew by two while this script was being written. One of
# them (#2594) would have REVERTED 851 lines of main had it been merged to clear the backlog, which
# is what makes the pile actively dangerous rather than merely untidy.
#
# branch-cleanup.yml already assumes this: its comment reads "a superseded deploy PR is CLOSED-
# unmerged (a newer deploy won the race)" and it deletes the branch on close. Nothing ever performed
# that close. This script is the missing half, not a new mechanism — with it, exactly one deploy PR
# per prefix is open at a time, it is always the newest, and it is always based on current main, so
# the conflict class cannot arise.
#
# ANCESTRY, NOT RECENCY (issue #6231)
# "Newer" was originally read off PR creation time — the PR that opened last kept, every other one
# closed. That is not what "newer" means here: a deploy PR is created when its BUILD finishes, and
# builds do not finish in commit order. Measured 2026-08-21: #6222 pinned `579dda8e` (merged to main
# 14:08:59Z) and #6225 pinned `a9e9d082` (merged 13:58:07Z, an ANCESTOR of it) — but a9e9d082's build
# ran ~12 min longer, so its PR was created at 14:47:01Z, twelve minutes after #6222's, and this
# script closed #6222 at 14:47:04Z as "superseded". The survivor rewound copilot-service from
# sandbox-579dda8e to sandbox-a9e9d082 and left agent-service short of #6204's code, with every job
# in both runs green. Fail-open, and invisible: check-deploy-drift compares version.txt, which those
# commits share (release-please bumps in a separate commit), so the drift watch reads "in sync".
#
# So the decision is now ancestry, asked of the GitHub compare API (the job's checkout is shallow,
# `git merge-base --is-ancestor` cannot answer there):
#   * other is an ancestor of keep (`ahead`/`identical`) -> genuinely superseded, close it.
#   * keep is an ancestor of other (`behind`)            -> THIS run is the stale one. Close nothing,
#     ::error, exit 1. The step fails, so the `Enable auto-merge` step that follows it never runs and
#     the rollback PR cannot merge itself. Loud beats a silent rewind.
#   * diverged, or a branch name with no parsable sha    -> ::warning, leave it open. Unknown is not
#     permission to close someone's PR.
#
# Deliberately scoped:
#   * Only the bot prefixes (chore/gitops-auto-deploy-, chore/admin-ui-deploy-). A hand-cut deploy PR
#     — they exist, see record-deployment-on-merge.yml — is never touched.
#   * Only same-repo heads, never a fork.
#   * Never the PR just opened.
#   * A HELD PR passes its hold to the survivor (issue #11503). A draft deploy PR, or one labelled
#     `blocked`, is an owner's deployment hold. On 2026-09-29 sanctions #11491 was drafted for an
#     unresolved regression (#11492), this script closed it as superseded by #11499 (a descendant
#     pin), and #11499 — never drafted — merged and promoted the held code. Ancestry transfers the
#     image, never the safety decision, so before a held PR is closed the survivor is converted to
#     draft and labelled `blocked` (deploy-window-flush only arms non-draft PRs), and the step
#     exits non-zero so no `Enable auto-merge` step after it runs. If the hold cannot be transferred
#     the held PR is left OPEN. The durable, service-scoped hold remains `rules.yaml: deploy_holds`
#     (check-deploy-holds.py); this closes the gap for a hold expressed only on the PR.
#   * A comment is left on every PR it closes. A silent close of someone's PR is not acceptable even
#     when the PR is bot-generated: the next person to look must find out why from the PR itself.
#
# Usage: supersede-deploy-prs.sh <branch-prefix> <keep-pr-number>
#   e.g. supersede-deploy-prs.sh chore/admin-ui-deploy- 2604
#        supersede-deploy-prs.sh --self-test     # falsify the ancestry decision, no network
# Requires: gh, jq, GH_TOKEN with pull-requests: write.

set -euo pipefail

# ── ancestry ────────────────────────────────────────────────────────────────────────────────────
# Extract the 40-hex commit a deploy branch pins. Anything else yields "" and is treated as unknown.
sha_of_branch() {
  local sha="${1#"$2"}" run
  # auto-deploy isolates candidates by producer run (#13460): `<sha>-run<digits>`. Strip exactly
  # that suffix; anything else after the sha leaves the remainder non-hex, so it stays unknown.
  case "$sha" in
    *-run*)
      run="${sha##*-run}"
      case "$run" in
        ''|*[!0-9]*) printf ''; return ;;
      esac
      sha="${sha%-run"$run"}"
      ;;
  esac
  # 40 hex characters exactly. A truncated, suffixed or hand-cut branch name yields "" (unknown),
  # and unknown must never mean "close it" — that was the pre-#6231 behaviour.
  case "$sha" in
    *[!0-9a-f]*) printf '' ;;
    *) if [ "${#sha}" -eq 40 ]; then printf '%s' "$sha"; else printf ''; fi ;;
  esac
}

# Ask GitHub how `head` relates to `base`: ahead | behind | identical | diverged.
# Overridable for the self-test (SUPERSEDE_COMPARE_HOOK), which is the ONLY seam — the classify /
# act loop below is the same code in both lanes.
compare_status() {
  local base="$1" head="$2"
  if [ -n "${SUPERSEDE_COMPARE_HOOK:-}" ]; then
    "$SUPERSEDE_COMPARE_HOOK" "$base" "$head"
    return
  fi
  gh api "repos/$REPO/compare/$base...$head" --jq '.status' 2>/dev/null || printf 'unknown'
}

# CLOSE | STALE_KEEP | SKIP, from (keep sha, other sha). Pure — no I/O, so the self-test exercises
# the real decision and not a paraphrase of it.
classify() {
  local keep_sha="$1" other_sha="$2" status
  if [ -z "$keep_sha" ] || [ -z "$other_sha" ]; then printf 'SKIP'; return; fi
  if [ "$keep_sha" = "$other_sha" ]; then printf 'CLOSE'; return; fi
  status="$(compare_status "$other_sha" "$keep_sha")"
  case "$status" in
    ahead|identical) printf 'CLOSE' ;;       # keep contains other — genuinely superseded
    behind)          printf 'STALE_KEEP' ;;  # keep is an ANCESTOR of other — this run is the stale one
    *)               printf 'SKIP' ;;        # diverged / unknown — never close on a guess
  esac
}

# ── coverage (issue #7621) ─────────────────────────────────────────────────────────────────────
# Ancestry alone is NOT sufficient: an auto-deploy PR bumps only the services *its own run*
# detected as changed, so a descendant commit's PR can touch a completely disjoint set of gitops
# components. Measured live: #7313 (bumps balance-service.yaml) was closed "superseded by #7314"
# (bumps kyc-service.yaml), which was in turn closed "superseded by #7319" (bumps
# account-service.yaml) — #7319's diff never touched either of the other two files, so both
# services silently stopped deploying while every job read green. Ancestry is still required (it
# is what generation 1 / issue #6231 fixed, and removing it reopens the createdAt rewind) — this
# is an ADDITIONAL gate, never a replacement.
#
# List the file paths a PR's diff touches. Overridable via SUPERSEDE_FILES_HOOK for the self-test,
# the same seam pattern as compare_status.
changed_files() {
  local pr="$1"
  if [ -n "${SUPERSEDE_FILES_HOOK:-}" ]; then
    "$SUPERSEDE_FILES_HOOK" "$pr"
    return
  fi
  gh pr diff "$pr" --repo "$REPO" --name-only 2>/dev/null
}

# rc 0 iff every non-empty line of $2 (the PR being considered for closure) appears in $1 (the
# survivor). An EMPTY subset is deliberately never treated as "covered" here — that decision
# belongs to the caller, which must refuse to close on empty/unreadable file lists (unknown must
# never mean "close it", same rule as classify() above).
covers() {
  local superset="$1" subset="$2" line
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    grep -qxF -- "$line" <<<"$superset" || return 1
  done <<<"$subset"
  return 0
}

# CLOSE | SKIP, from (keep's changed files, other's changed files). Pure, same reason as classify().
# Fails safe in every direction: no keep files, no other files, or other not a subset -> SKIP.
classify_coverage() {
  local keep_files="$1" other_files="$2"
  if [ -z "$keep_files" ] || [ -z "$other_files" ]; then printf 'SKIP'; return; fi
  if covers "$keep_files" "$other_files"; then printf 'CLOSE'; else printf 'SKIP'; fi
}

RULES_PATH=openbank-libs/governance/rules.yaml

# ── holds (issue #11503) ───────────────────────────────────────────────────────────────────────
# A deployment hold lives in ONE place: `rules.yaml: deploy_holds` on main. auto-deploy filters
# held services out of every new deploy PR (check-deploy-holds.py --filter-tags), and the
# deploy-hold-image-pins gate refuses a held pin change. This script therefore never writes a
# hold anywhere and never drafts or labels a survivor.
#
# The previous design copied the predecessor's holds into the survivor's own rules.yaml and
# drafted + labelled the survivor. Its own draft/`blocked` then read as an owner hold on the next
# run, so every deploy PR inherited every earlier one and none was ever closed: by #13530 a
# 9-pin deploy carried ~3200 lines of copied holds over 64 services and failed its own gate.
#
# So "held" must mean held BY A HUMAN. A draft/`blocked` predecessor is classified by who applied
# that state (timeline `labeled blocked` / `convert_to_draft` actors):
#   owner   - some human applied it: leave the PR open, post a one-line notice once.
#   bot     - only automation applied it: not a hold; ordinary ancestry + coverage decide.
#   unknown - timeline unreadable or no applying event: leave open, untouched.
HOLD_NOTICE_MARKER='<!-- supersede-owner-hold -->'
HOLD_NOTICE_TEXT="A newer deploy PR supersedes this one, but it is held by an owner, so it stays open; if the hold should persist, move it to main's rules.yaml deploy_holds and close this PR. $HOLD_NOTICE_MARKER"

# The exact identities the deploy automation acts as when it drafts or labels a PR: the gitops
# App (auto-deploy's app_token) and GITHUB_TOKEN, its fallback. Matched by LOGIN, never by actor
# type — any other App is also type `Bot`, and it is not this automation.
DEPLOY_AUTOMATION_LOGINS="${DEPLOY_AUTOMATION_LOGINS:-openbank-gitops-bot[bot] github-actions[bot]}"

# Actor logins of every `labeled blocked` / `convert_to_draft` event on PR $1, one per line.
# Non-zero on an API failure. Overridable via SUPERSEDE_TIMELINE_HOOK for the self-test.
hold_event_logins() {
  if [ -n "${SUPERSEDE_TIMELINE_HOOK:-}" ]; then
    "$SUPERSEDE_TIMELINE_HOOK" "$1"
    return
  fi
  gh api --paginate "repos/$REPO/issues/$1/timeline" \
    --jq '.[] | select((.event == "labeled" and .label.name == "blocked") or .event == "convert_to_draft")
          | (.actor.login // "")' 2>/dev/null
}

# owner | bot | unknown. `bot` ONLY when the timeline was read, is non-empty, and EVERY applying
# event was made by a DEPLOY_AUTOMATION_LOGINS identity. A human or foreign login is `owner`; an
# API error or an empty timeline is `unknown`. Callers treat both owner and unknown as a HOLD.
hold_applier() {
  local logins login
  if ! logins="$(hold_event_logins "$1")"; then printf 'unknown'; return; fi
  [ -n "$logins" ] || { printf 'unknown'; return; }
  while IFS= read -r login; do
    case " $DEPLOY_AUTOMATION_LOGINS " in
      *" $login "*) [ -n "$login" ] || { printf 'unknown'; return; } ;;
      *) printf 'owner'; return ;;
    esac
  done <<<"$logins"
  printf 'bot'
}

# Legacy chain (issue #11503): branches opened under the retired design carry deploy_holds that
# were copied from owner-held PRs (e.g. #12948). Until those services are held on main, such a
# PR is itself a hold. Prints the PR branch's deploy_holds services that main does not hold;
# non-zero when either rules.yaml cannot be read or parsed. Overridable via
# SUPERSEDE_BRANCH_HOLDS_HOOK for the self-test.
unmigrated_branch_holds() {
  if [ -n "${SUPERSEDE_BRANCH_HOLDS_HOOK:-}" ]; then
    "$SUPERSEDE_BRANCH_HOLDS_HOOK" "$1"
    return
  fi
  local head branch_rules main_rules
  head="$(gh pr view "$1" --repo "$REPO" --json headRefOid --jq .headRefOid)" || return 1
  [[ "$head" =~ ^[0-9a-f]{40}$ ]] || return 1
  branch_rules="$(gh api -H 'Accept: application/vnd.github.raw' "repos/$REPO/contents/$RULES_PATH?ref=$head")" || return 1
  main_rules="$(gh api -H 'Accept: application/vnd.github.raw' "repos/$REPO/contents/$RULES_PATH?ref=main")" || return 1
  BRANCH_RULES="$branch_rules" MAIN_RULES="$main_rules" "${DEPLOY_HOLD_PYTHON:-python3}" -c '
import os, yaml
def held(text):
    doc = yaml.safe_load(text) or {}
    services = ((doc.get("deploy_holds") or {}).get("services")) or {}
    if not isinstance(services, dict):
        raise SystemExit("deploy_holds.services is not a map")
    return set(services)
for name in sorted(held(os.environ["BRANCH_RULES"]) - held(os.environ["MAIN_RULES"])):
    print(name)
'
}

# rc 0 iff PR $1 already carries the notice marked $2 (default: the owner-hold notice).
# Overridable via SUPERSEDE_NOTICE_HOOK.
hold_noticed() {
  local bodies marker="${2:-$HOLD_NOTICE_MARKER}"
  if [ -n "${SUPERSEDE_NOTICE_HOOK:-}" ]; then
    "$SUPERSEDE_NOTICE_HOOK" "$1" "$marker"
    return
  fi
  # Unreadable comments count as noticed: a duplicate notice is noise, a missing one is not harm.
  bodies="$(gh api --paginate "repos/$REPO/issues/$1/comments" --jq '.[].body' 2>/dev/null)" || return 0
  grep -qF -- "$marker" <<<"$bodies"
}

# ── an owner hold blocks an OVERLAPPING survivor (regression of #13531) ────────────────────────
# An owner hold on an older deploy PR lives only on that PR until someone records it in main's
# deploy_holds — auto-deploy's filter and the deploy-hold-image-pins gate cannot see it. Arming a
# newer PR that pins any of the same services would therefore deploy straight through the hold.
# So when a predecessor is held by an owner (or by an applier that cannot be established) and the
# survivor pins ANY service it pins — or that overlap cannot be computed — the run fails and the
# survivor is not armed, whatever the ancestry/coverage verdict. A bot-applied state never blocks.
BLOCK_NOTICE_MARKER='<!-- supersede-owner-hold-blocks -->'

# Service names a deploy diff pins: the manifest basenames, rules.yaml (the retired hold copy)
# excluded. Matching by basename, not path, is deliberately conservative: one service pinned from
# two components (ledger/ and ledger-pension-co/ledger-service.yaml) still counts as overlap.
pinned_services() {
  local line
  while IFS= read -r line; do
    [ -n "$line" ] && [ "$line" != "$RULES_PATH" ] || continue
    line="${line##*/}"
    printf '%s\n' "${line%.y*ml}"
  done <<<"$1" | sort -u
}

# Prints the services both file lists pin. rc 2 when either side pins nothing (not computable).
pin_overlap() {
  local a b
  a="$(pinned_services "$1")"; b="$(pinned_services "$2")"
  [ -n "$a" ] && [ -n "$b" ] || return 2
  comm -12 <(printf '%s\n' "$a") <(printf '%s\n' "$b")
}

# ── main ────────────────────────────────────────────────────────────────────────────────────────
run() {
  local PREFIX="$1" KEEP="$2" KEEP_SHA="$3" PAIRS="$4"
  local n ref held other_sha verdict applier failed=0 stale_keep=0 closed=0 skipped=0 owner_held=0
  local KEEP_FILES other_files cov_verdict keep_files_ok=1 safety_unknown=0 carries_rules=0 legacy
  local hold_files overlap owner_block=0 block_list=""

  if [ -z "$PAIRS" ]; then
    echo "supersede: no other open '$PREFIX*' PRs besides #$KEEP — nothing to close."
    return 0
  fi

  # Unknown is never permission: a failed producer's partial stdout is discarded.
  if ! KEEP_FILES="$(changed_files "$KEEP")"; then
    KEEP_FILES=""
    keep_files_ok=0
    safety_unknown=1
    echo "::error::supersede: could not read #$KEEP's changed-file list; refusing coverage-based closes."
  fi

  while IFS=$'\t' read -r n ref held; do
    [ -n "$n" ] || continue
    other_sha="$(sha_of_branch "$ref" "$PREFIX")"
    # Who held it is decided ONCE, before any verdict, because the block below must apply on every
    # path (CLOSE, STALE_KEEP, SKIP and coverage SKIP) — not only where a close was on the table.
    applier=""
    if [ "${held:-0}" = "1" ]; then
      applier="$(hold_applier "$n")"
      if [ "$applier" != "bot" ]; then
        if [ "$keep_files_ok" -eq 1 ] && hold_files="$(changed_files "$n")" \
           && overlap="$(pin_overlap "$KEEP_FILES" "$hold_files")"; then
          if [ -n "$overlap" ]; then
            owner_block=1; block_list="$block_list #$n"
            echo "::error::supersede: #$n is held (applier: $applier) and #$KEEP pins the same services —" \
                 "overlap: $(printf '%s' "$overlap" | tr '\n' ' ') — #$KEEP must not be armed."
          else
            echo "supersede: #$n is held (applier: $applier), but #$KEEP pins none of its services — not blocking."
          fi
        else
          owner_block=1; block_list="$block_list #$n"
          echo "::error::supersede: #$n is held (applier: $applier) and its pins vs #$KEEP's:" \
               "overlap cannot be computed — failing closed; #$KEEP must not be armed."
        fi
      fi
    fi
    verdict="$(classify "$KEEP_SHA" "$other_sha")"
    if [ "$verdict" = "CLOSE" ]; then
      if [ "$keep_files_ok" -eq 1 ] && other_files="$(changed_files "$n")"; then
        # rules.yaml on a deploy branch is the retired hold copy, never an image pin to cover.
        carries_rules=0
        grep -qxF -- "$RULES_PATH" <<<"$other_files" && carries_rules=1
        other_files="$(grep -vxF -- "$RULES_PATH" <<<"$other_files" || true)"
        cov_verdict="$(classify_coverage "$KEEP_FILES" "$other_files")"
      else
        [ "$keep_files_ok" -eq 0 ] || { safety_unknown=1; echo "::error::supersede: could not read #$n's changed-file list; refusing to close it."; }
        cov_verdict=SKIP
      fi
      if [ "$cov_verdict" != "CLOSE" ]; then
        skipped=$((skipped + 1))
        echo "::warning::supersede: #$KEEP is a descendant of #$n's commit, but #$KEEP's changed" \
             "files do not cover #$n's (coverage check, issue #7621) — leaving #$n OPEN." \
             "keep_files=[$(printf '%s' "$KEEP_FILES" | tr '\n' ' ')]" \
             "other_files=[$(printf '%s' "$other_files" | tr '\n' ' ')]"
        continue
      fi
    fi
    case "$verdict" in
      STALE_KEEP)
        stale_keep=1
        echo "::error::supersede: #$KEEP pins ${KEEP_SHA:0:8}, which is an ANCESTOR of #$n's ${other_sha:0:8}." \
             "This build finished later than a NEWER commit's, so #$KEEP would roll those services BACK." \
             "Closing nothing and failing the step: #$n stays open, and auto-merge is not armed on #$KEEP." \
             "Re-dispatch auto-deploy for the current main once #$n has landed (issue #6231)."
        ;;
      SKIP)
        skipped=$((skipped + 1))
        echo "::warning::supersede: cannot establish that #$KEEP supersedes #$n (keep=${KEEP_SHA:-?} other=${other_sha:-?}, unrelated or unparsable) — leaving #$n OPEN."
        ;;
      CLOSE)
        if [ "$carries_rules" -eq 1 ]; then
          if ! legacy="$(unmigrated_branch_holds "$n")"; then
            owner_held=$((owner_held + 1)); skipped=$((skipped + 1))
            echo "::warning::supersede: cannot read #$n's branch deploy_holds against main — treating it as held; leaving it OPEN and untouched."
            continue
          fi
          if [ -n "$legacy" ]; then
            owner_held=$((owner_held + 1)); skipped=$((skipped + 1))
            echo "::warning::supersede: #$n carries deploy_holds not yet on main — held; leaving it OPEN and untouched." \
                 "migrate to main: $(printf '%s' "$legacy" | tr '\n' ' ')"
            continue
          fi
        fi
        if [ "${held:-0}" = "1" ]; then
          case "$applier" in
            bot)
              echo "supersede: #$n's draft/blocked state was applied only by automation — not an owner hold." ;;
            owner)
              owner_held=$((owner_held + 1)); skipped=$((skipped + 1))
              echo "::notice::supersede: #$KEEP supersedes #$n, but #$n is held by an owner — leaving it OPEN; nothing is propagated."
              if hold_noticed "$n"; then
                :
              elif [ "${DRY_RUN:-}" = "true" ]; then
                echo "supersede: DRY_RUN — would post the owner-hold notice on #$n."
              elif ! gh pr comment "$n" --repo "$REPO" --body "$HOLD_NOTICE_TEXT"; then
                echo "::warning::supersede: could not post the owner-hold notice on #$n."
              fi
              continue ;;
            *)
              skipped=$((skipped + 1))
              echo "::warning::supersede: cannot establish who applied #$n's draft/blocked state — leaving it OPEN and untouched."
              continue ;;
          esac
        fi
        if [ "${DRY_RUN:-}" = "true" ]; then
          echo "supersede: DRY_RUN — would close #$n (${other_sha:0:8} is an ancestor of ${KEEP_SHA:0:8})."
          closed=$((closed + 1))
          continue
        fi
        # Comment first, then close: if the comment fails, leave the PR OPEN rather than close it
        # silently — the reason must be findable from the PR itself.
        if ! gh pr comment "$n" --repo "$REPO" --body \
"Superseded by #$KEEP and closed automatically.

#$KEEP pins a descendant of this PR's commit and edits every file this PR edits, so it already
contains everything this PR would have applied. (Ancestry, not creation time: a slower build can open a
newer PR for an older commit, which is issue #6231.)

Left open, both PRs edit the same manifest line and whichever merges first leaves the other conflicting,
which reads as healthy — green checks, auto-merge armed — right up to the point someone tries to merge it.

If you believe otherwise, reopen it and say what it carries that #$KEEP does not.

(\`.github/scripts/supersede-deploy-prs.sh\`)"; then
          echo "::warning::supersede: could not comment on #$n — leaving it OPEN rather than closing it silently."
          failed=1
          continue
        fi
        if ! gh pr close "$n" --repo "$REPO" --delete-branch; then
          echo "::warning::supersede: could not close #$n."
          failed=1
        else
          closed=$((closed + 1))
        fi
        ;;
    esac
  done <<EOF
$PAIRS
EOF

  echo "supersede: #$KEEP (${KEEP_SHA:0:8}) — closed=$closed skipped=$skipped stale_keep=$stale_keep owner_held=$owner_held"

  # Non-zero when THIS PR must not be armed: it would roll services back, it would deploy through
  # an owner hold that is not on main yet, or the evidence needed to decide was unreadable.
  if [ "$owner_block" -ne 0 ]; then
    echo "::error::supersede: owner hold on${block_list} overlaps #$KEEP — refusing to let #$KEEP be armed."
    if hold_noticed "$KEEP" "$BLOCK_NOTICE_MARKER"; then
      :
    elif [ "${DRY_RUN:-}" = "true" ]; then
      echo "supersede: DRY_RUN — would post the owner-hold block notice on #$KEEP."
    elif ! gh pr comment "$KEEP" --repo "$REPO" --body \
"This deploy PR pins services that an owner holds on${block_list}, so it will not be armed. The owner hold on${block_list} must be recorded in main's \`rules.yaml: deploy_holds\` (or${block_list} released) before this PR can deploy. $BLOCK_NOTICE_MARKER"; then
      echo "::warning::supersede: could not post the owner-hold block notice on #$KEEP."
    fi
    return 1
  fi
  if [ "$stale_keep" -ne 0 ]; then
    return 1
  fi
  if [ "$safety_unknown" -ne 0 ]; then
    echo "::error::supersede: coverage evidence was unreadable; no predecessor may be closed and auto-merge must not be armed."
    return 1
  fi
  if [ "$failed" -ne 0 ]; then
    echo "::warning::supersede: one or more superseded deploy PRs could not be closed; close them by hand."
  fi
  return 0
}

# ── self-test ───────────────────────────────────────────────────────────────────────────────────
# Falsification, not decoration: case 1 is the losing interleaving from #6231 (an older commit's PR
# created LAST) and must fail; case 2 is the ordinary case and must still close the older PR.
# Print WHAT THE ASSERTION SAW, not what the script printed (#6618).
#
# On 2026-08-23 case 2 failed on `main` with a byte-identical script, reporting "the older PR was
# not closed" while the very output it echoed contained `would close #6225` as plain ASCII. Passed
# on the 13 commits either side. Encoding, locale, a code change and the commit's own content were
# all ruled out; what was never available is the bytes `grep` was actually given. An echoed string
# cannot answer that — a stray CR, a truncated capture or an empty `$out` all render the same way
# in a log, and the second of those looks exactly like the third.
#
# So every failure branch dumps the length and `od -c`. This does not fix the flake. It makes the
# next occurrence say which of the three it is, which is the only thing that can.
dump_out() { # dump_out <captured output>
  printf '        captured %d byte(s):\n' "${#1}"
  printf '%s' "$1" | od -c | sed 's/^/        /'
}

# Match the captured text in-process. `grep -q` exits as soon as it finds a match, which can close
# the pipe while printf is still writing; with pipefail that turns a successful assertion into a
# failure when captured output is larger than the pipe buffer.
contains_text() { # contains_text <captured output> <literal>
  case "$1" in *"$2"*) return 0 ;; *) return 1 ;; esac
}

self_test() {
  local tmp rc out ok=0
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  # Fake compare: OLD..NEW is `ahead`, NEW..OLD is `behind`, anything else `diverged`.
  cat > "$tmp/compare" <<'HOOK'
#!/usr/bin/env bash
base="$1"; head="$2"
old=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
new=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
if [ "$base" = "$old" ] && [ "$head" = "$new" ]; then echo ahead
elif [ "$base" = "$new" ] && [ "$head" = "$old" ]; then echo behind
else echo diverged; fi
HOOK
  chmod +x "$tmp/compare"
  export SUPERSEDE_COMPARE_HOOK="$tmp/compare"
  export DRY_RUN=true

  # Fake file lists, keyed by PR number. Cases 1-4 predate the coverage gate (issue #7621) and are
  # about ancestry only, so every PR in them shares one file — coverage is trivially satisfied and
  # cannot mask an ancestry regression. Cases 5-7 give distinct PRs distinct files to exercise the
  # coverage gate itself.
  cat > "$tmp/files" <<'HOOK'
#!/usr/bin/env bash
pr="$1"
case "$pr" in
  11491|11498|11499) echo "openbank-infra/gitops/components/sanctions-service/sanctions-service.yaml"
                     # a legacy branch also carries the retired rules.yaml hold copy
                     if [ "$pr" = 11491 ] && [ -n "${HOLD_BRANCH:-}" ]; then
                       echo "openbank-libs/governance/rules.yaml"; fi ;;
  13010) printf '%s\n%s\n' \
          'openbank-infra/gitops/components/accounts/account-service.yaml' \
          'openbank-infra/gitops/components/balances/balance-service.yaml' ;;
  13011) printf '%s\n' 'openbank-infra/gitops/components/accounts/account-service.yaml' ;;
  13020) printf '%s\n%s\n' \
          'openbank-infra/gitops/components/balances/balance-service.yaml' \
          'openbank-libs/governance/rules.yaml' ;;
  13021) printf '%s\n%s\n' \
          'openbank-infra/gitops/components/accounts/account-service.yaml' \
          'openbank-libs/governance/rules.yaml' ;;
  13030) printf '%s\n%s\n' \
          'openbank-infra/gitops/components/balances/balance-service.yaml' \
          'openbank-libs/governance/rules.yaml' ;;
  13031) printf '%s\n%s\n' \
          'openbank-infra/gitops/components/accounts/account-service.yaml' \
          'openbank-libs/governance/rules.yaml' ;;
  13032|13033) echo '' ;;
  13040|13041) echo 'openbank-infra/gitops/components/payments/payments-services.yaml' ;;
  13042|13043) echo 'openbank-infra/gitops/components/payments/payments-services.yaml'; exit 48 ;;
  # owner-hold overlap fixtures (cases 37-41): keep 13061 shares kyc with 13060, 13062 shares
  # nothing; 13064 cannot be read and 13065 pins nothing, so their overlap is not computable.
  13060) printf '%s\n%s\n' \
          'openbank-infra/gitops/components/kyc/kyc-service.yaml' \
          'openbank-infra/gitops/components/fraud-service/fraud-service.yaml' ;;
  13061) printf '%s\n%s\n' \
          'openbank-infra/gitops/components/kyc/kyc-service.yaml' \
          'openbank-infra/gitops/components/aml/aml-service.yaml' ;;
  13062) printf '%s\n' 'openbank-infra/gitops/components/aml/aml-service.yaml' ;;
  13064) exit 1 ;;
  13065) printf '%s\n' 'openbank-libs/governance/rules.yaml' ;;
  6222|6225|1|2) echo "openbank-infra/gitops/components/shared/shared-service.yaml" ;;
  7313) printf '%s\n' "openbank-infra/gitops/components/balances/balance-service.yaml" ;;
  7319) printf '%s\n' "openbank-infra/gitops/components/accounts/account-service.yaml" ;;
  7314) printf '%s\n' "openbank-infra/gitops/components/kyc/kyc-service.yaml" ;;
  7327) printf '%s\n%s\n' \
          "openbank-infra/gitops/components/aml/aml-service.yaml" \
          "openbank-infra/gitops/components/kyc/kyc-service.yaml" ;;
  7320) printf '%s\n' "openbank-infra/gitops/components/aml/aml-service.yaml" ;;
  *) echo "" ;;
esac
HOOK
  chmod +x "$tmp/files"
  export SUPERSEDE_FILES_HOOK="$tmp/files"
  local OLD=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  local NEW=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
  local P=chore/gitops-auto-deploy-

  # case 1 — the #6231 interleaving: KEEP is the OLDER commit, the open PR is the NEWER one.
  out="$(run "$P" 6225 "$OLD" "$(printf '6222\t%s%s' "$P" "$NEW")" 2>&1)" && rc=0 || rc=$?
  # Keep a realistic large-output regression case: the expected error is near the start, followed
  # by more than a typical pipe buffer. The assertion must still see it without a grep pipeline.
  out+="$(printf '%131072s' '' | tr ' ' x)"
  if [ "$rc" -eq 0 ]; then
    echo "SELF-TEST FAIL case 1: stale KEEP exited 0 (must be non-zero)"; ok=1
  elif ! contains_text "$out" '::error::supersede: #6225 pins aaaaaaaa, which is an ANCESTOR'; then
    echo "SELF-TEST FAIL case 1: no ::error naming the ancestry; got: $out"; ok=1; dump_out "$out"
  elif contains_text "$out" 'would close'; then
    echo "SELF-TEST FAIL case 1: it still closed the newer PR"; ok=1
  else
    echo "self-test case 1 OK (stale keep -> rc=$rc, ::error, nothing closed)"
  fi

  # case 2 — the ordinary case: KEEP is the NEWER commit. The older PR must still be closed.
  out="$(run "$P" 6222 "$NEW" "$(printf '6225\t%s%s' "$P" "$OLD")" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "SELF-TEST FAIL case 2: ordinary supersede exited $rc; got: $out"; ok=1; dump_out "$out"
  elif ! contains_text "$out" 'would close #6225'; then
    echo "SELF-TEST FAIL case 2: the older PR was not closed; got: $out"; ok=1; dump_out "$out"
  else
    echo "self-test case 2 OK (ordinary supersede -> rc=$rc, older PR closed)"
  fi

  # case 3 — unrelated shas must never be closed on a guess.
  out="$(run "$P" 1 cccccccccccccccccccccccccccccccccccccccc "$(printf '2\t%s%s' "$P" "$OLD")" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ] || contains_text "$out" 'would close'; then
    echo "SELF-TEST FAIL case 3: diverged pair was closed or failed; got: $out"; ok=1; dump_out "$out"
  else
    echo "self-test case 3 OK (diverged -> warning, left open)"
  fi

  # case 4 — an unparsable branch name is unknown, not permission.
  out="$(run "$P" 1 "$NEW" "$(printf '2\t%snot-a-sha' "$P")" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ] || contains_text "$out" 'would close'; then
    echo "SELF-TEST FAIL case 4: unparsable branch was closed or failed; got: $out"; ok=1; dump_out "$out"
  else
    echo "self-test case 4 OK (unparsable sha -> warning, left open)"
  fi

  # case 5 — coverage gate (issue #7621), the exact production shape: #7314 (kyc) is a genuine
  # ancestry-supersede of #7313 (balance), but #7314's diff never touches balance-service.yaml.
  # Ancestry alone would CLOSE this; the coverage gate must refuse.
  out="$(run "$P" 7314 "$NEW" "$(printf '7313\t%s%s' "$P" "$OLD")" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "SELF-TEST FAIL case 5: coverage-insufficient run exited $rc (must be 0, tidiness only); got: $out"; ok=1; dump_out "$out"
  elif contains_text "$out" 'would close #7313'; then
    echo "SELF-TEST FAIL case 5: #7313 was closed despite #7314 never touching balance-service.yaml; got: $out"; ok=1; dump_out "$out"
  elif ! contains_text "$out" 'coverage check, issue #7621) — leaving #7313 OPEN.'; then
    echo "SELF-TEST FAIL case 5: no coverage warning naming #7313; got: $out"; ok=1; dump_out "$out"
  else
    echo "self-test case 5 OK (ancestor but disjoint files -> #7313 left OPEN, #7313/#7314/#7319 shape)"
  fi

  # case 6 — coverage gate, the ordinary (should-close) case: #7327 bumps BOTH aml and kyc
  # manifests, a genuine superset of #7320's aml-only diff. Must still close.
  out="$(run "$P" 7327 "$NEW" "$(printf '7320\t%s%s' "$P" "$OLD")" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "SELF-TEST FAIL case 6: ordinary coverage-covered run exited $rc; got: $out"; ok=1; dump_out "$out"
  elif ! contains_text "$out" 'would close #7320'; then
    echo "SELF-TEST FAIL case 6: #7320 was not closed despite #7327's superset diff; got: $out"; ok=1; dump_out "$out"
  else
    echo "self-test case 6 OK (ancestor and superset files -> #7320 closed)"
  fi

  # case 8 — the same pair WITHOUT a hold is ordinary: closed, rc 0, nothing inherited.
  out="$(run "$P" 11499 "$NEW" "$(printf '11491\t%s%s\t0' "$P" "$OLD")" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ] || contains_text "$out" 'inherit hold' \
     || ! contains_text "$out" 'would close #11491'; then
    echo "SELF-TEST FAIL case 8: unheld supersede was not ordinary; got: $out"; ok=1; dump_out "$out"
  else
    echo "self-test case 8 OK (unheld -> closed, rc=0, no hold)"
  fi

  # ── holds live only on main (issue #11503, second design) ─────────────────────────────────────
  # A fake `gh` records every call. Cases 30-33 run LIVE (DRY_RUN=false) so a write would happen,
  # not merely be announced. The hold applier (owner | bot | unknown) and "notice already posted"
  # are the two seams; everything else is the production loop.
  local hb="$tmp/holdbin" hev="$tmp/hold-events"
  mkdir -p "$hb"
  cat >"$hb/gh" <<'HOOK'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$HOLD_EVENTS"
case "$*" in
  *headRefOid*) printf '%040d\n' 1 ;;
  *headRefName*) printf 'chore/gitops-auto-deploy-%s\n' bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb ;;
esac
exit 0
HOOK
  # Timeline seam: prints the actor LOGIN of every draft/`blocked` event, one per line; the
  # spec `ERR` simulates an API failure. Short names: bot = the deploy App, owner = a human.
  cat >"$tmp/timeline" <<'HOOK'
#!/usr/bin/env bash
case "$HOLD_APPLIER" in
  ERR|unknown) exit 1 ;;
  bot) printf '%s\n' 'openbank-gitops-bot[bot]' ;;
  owner) printf '%s\n' 'some-owner' ;;
  *) printf '%s' "$HOLD_APPLIER" | tr '|' '\n' ;;
esac
HOOK
  # Branch-holds seam: deploy_holds services on the PR's branch that are NOT on main; `ERR` fails.
  cat >"$tmp/branchholds" <<'HOOK'
#!/usr/bin/env bash
[ "${HOLD_BRANCH:-}" != ERR ] || exit 1
[ -z "${HOLD_BRANCH:-}" ] || printf '%s' "$HOLD_BRANCH" | tr '|' '\n'
HOOK
  cat >"$tmp/notice" <<'HOOK'
#!/usr/bin/env bash
[ "${HOLD_NOTICED:-false}" = true ]
HOOK
  chmod +x "$hb/gh" "$tmp/timeline" "$tmp/branchholds" "$tmp/notice"
  # shellcheck disable=SC2030,SC2031  # deliberately subshell-local exports (see ov_run)
  hold_run() { # hold_run <timeline spec> [noticed] [branch-holds spec] -> rc; events in $hev
    : >"$hev"
    ( export PATH="$hb:$PATH" HOLD_EVENTS="$hev" HOLD_APPLIER="$1" HOLD_NOTICED="${2:-false}" \
             HOLD_BRANCH="${3:-}" DRY_RUN=false REPO=fixture/repo \
             SUPERSEDE_TIMELINE_HOOK="$tmp/timeline" SUPERSEDE_BRANCH_HOLDS_HOOK="$tmp/branchholds" \
             SUPERSEDE_NOTICE_HOOK="$tmp/notice"
      run "$P" 11499 "$NEW" "$(printf '11491\t%s%s-run7\t1' "$P" "$OLD")" )
  }
  mutations() { grep -E '^(pr (close|ready|edit|comment)|api .*-X|api -X)' "$hev" || true; }

  # case 30 — a deploy branch never gains a rules.yaml diff, whoever applied the predecessor's hold.
  local who leaked=""
  for who in owner bot unknown; do
    out="$(hold_run "$who" 2>&1)" || true
    if grep -qE 'contents/|-X PUT|pr ready|pr edit' "$hev"; then leaked="$leaked $who"; fi
  done
  if [ -n "$leaked" ]; then
    echo "SELF-TEST FAIL case 30: survivor branch or state was written for applier(s):$leaked"; ok=1
  else
    echo "self-test case 30 OK (no deploy_holds copy, no draft/label on the survivor)"
  fi

  # case 31 — draft/blocked applied by the automation itself is not an owner hold: an ancestor
  # with covered files is closed like any other, and the run succeeds.
  out="$(hold_run bot 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ] || ! grep -q '^pr close 11491' "$hev"; then
    echo "SELF-TEST FAIL case 31: bot-applied hold was treated as a donor; rc=$rc events=$(tr '\n' '|' <"$hev")"; ok=1; dump_out "$out"
  else
    echo "self-test case 31 OK (bot-applied draft/blocked -> superseded and closed)"
  fi

  # case 32 — an owner-held predecessor is left open and untouched: exactly one one-line notice on
  # it, posted once (a second run with the notice present posts nothing), nothing closed. Its pins
  # overlap the survivor's, so the run is non-zero (case 37); only the predecessor is asserted here.
  older_mutations() { mutations | grep -vE '^pr comment 11499 ' || true; }
  out="$(hold_run owner 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -eq 0 ] || [ "$(older_mutations)" != "pr comment 11491 --repo fixture/repo --body $HOLD_NOTICE_TEXT" ]; then
    echo "SELF-TEST FAIL case 32: owner hold was not left open with one notice; rc=$rc mutations=$(mutations | tr '\n' '|')"; ok=1; dump_out "$out"
  else
    out="$(hold_run owner true 2>&1)" && rc=0 || rc=$?
    if [ "$rc" -eq 0 ] || [ -n "$(mutations)" ]; then
      echo "SELF-TEST FAIL case 32: repeated run touched the owner-held PR; mutations=$(mutations | tr '\n' '|')"; ok=1
    else
      echo "self-test case 32 OK (owner hold left open; notice posted once)"
    fi
  fi

  # case 33 — an applier that cannot be established is treated as an owner hold, but without a
  # notice: nothing is closed, commented or written.
  out="$(hold_run unknown 2>&1)" && rc=0 || rc=$?
  if [ -n "$(older_mutations)" ]; then
    echo "SELF-TEST FAIL case 33: unknown applier led to a mutation: $(mutations | tr '\n' '|')"; ok=1
  else
    echo "self-test case 33 OK (unknown applier -> left open, untouched)"
  fi

  # case 34 — a predecessor polluted by the retired hold copy (its diff includes rules.yaml) is
  # still covered by a survivor that pins the same manifests: rules.yaml is not an image pin.
  printf '#!/usr/bin/env bash\n' >"$tmp/nolegacy"; chmod +x "$tmp/nolegacy"
  out="$(SUPERSEDE_BRANCH_HOLDS_HOOK="$tmp/nolegacy" run "$P" 13011 "$NEW" "$(printf '13021\t%s%s\t0' "$P" "$OLD")" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ] || ! contains_text "$out" 'would close #13021'; then
    echo "SELF-TEST FAIL case 34: rules.yaml in a predecessor's diff blocked coverage; rc=$rc"; ok=1; dump_out "$out"
  else
    echo "self-test case 34 OK (retired rules.yaml copy ignored by coverage)"
  fi

  # case 35 — the applier is matched by exact automation LOGIN, never by actor type: another
  # App (type Bot), an empty timeline and an API error are each an owner hold — left open.
  local spec
  for spec in 'other-app[bot]' 'openbank-gitops-bot[bot]|other-app[bot]' '' ERR; do
    out="$(hold_run "$spec" 2>&1)" || true
    if grep -qE '^pr close' "$hev"; then
      echo "SELF-TEST FAIL case 35: timeline '$spec' was not treated as an owner hold"; ok=1; dump_out "$out"
    fi
  done
  out="$(hold_run 'github-actions[bot]|openbank-gitops-bot[bot]' 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ] || ! grep -q '^pr close 11491' "$hev"; then
    echo "SELF-TEST FAIL case 35: an all-automation timeline was not closable; rc=$rc"; ok=1; dump_out "$out"
  fi
  echo "self-test case 35 checked (exact automation logins only; foreign/empty/error = owner hold)"

  # case 36 — legacy chain: a branch carrying deploy_holds that main does not hold is a hold.
  # Leave it untouched and name the services to migrate; an unreadable branch is the same.
  local bh
  for bh in 'openbank-kyc-service|openbank-fraud-service' ERR; do
    out="$(hold_run bot false "$bh" 2>&1)" || true
    if [ -n "$(mutations)" ]; then
      echo "SELF-TEST FAIL case 36: branch holds '$bh' did not block closure: $(mutations | tr '\n' '|')"; ok=1; dump_out "$out"
    fi
  done
  out="$(hold_run bot false 'openbank-kyc-service|openbank-fraud-service' 2>&1)" || true
  if ! contains_text "$out" 'migrate to main: openbank-kyc-service openbank-fraud-service'; then
    echo "SELF-TEST FAIL case 36: services to migrate were not reported"; ok=1; dump_out "$out"
  fi
  echo "self-test case 36 checked (unmigrated branch holds -> held, untouched, reported)"

  # ── an owner hold on a predecessor blocks an OVERLAPPING survivor (#13531 regression) ────────
  # The hold lives only on the older PR until it is copied to main's deploy_holds, so arming a
  # newer PR that pins any of the same services deploys straight through it. Each case runs LIVE
  # (fake gh) and asserts the exit code the arming step keys on.
  local CCC=cccccccccccccccccccccccccccccccccccccccc
  # Same isolated-subshell pattern as hold_run: the exports must NOT leak into later cases.
  # shellcheck disable=SC2030,SC2031
  ov_run() { # ov_run <timeline spec> <keep> <other> <other sha> [keep sha] [noticed] -> rc; events in $hev
    : >"$hev"
    ( export PATH="$hb:$PATH" HOLD_EVENTS="$hev" HOLD_APPLIER="$1" HOLD_NOTICED="${6:-false}" \
             HOLD_BRANCH="" DRY_RUN=false REPO=fixture/repo \
             SUPERSEDE_TIMELINE_HOOK="$tmp/timeline" SUPERSEDE_BRANCH_HOLDS_HOOK="$tmp/branchholds" \
             SUPERSEDE_NOTICE_HOOK="$tmp/notice"
      run "$P" "$2" "${5:-$NEW}" "$(printf '%s\t%s%s\t1' "$3" "$P" "$4")" )
  }
  keep_noticed() { grep -cE "^pr comment $1 .*supersede-owner-hold-blocks" "$hev" || true; }
  local path_sha path_name
  # case 37 — owner hold + overlapping pins blocks the survivor on EVERY verdict path:
  # coverage SKIP (ancestor, uncovered files), SKIP (diverged) and STALE_KEEP (keep is older).
  for path_name in coverage-skip:$OLD diverged:$CCC; do
    path_sha="${path_name#*:}"
    out="$(ov_run owner 13061 13060 "$path_sha" 2>&1)" && rc=0 || rc=$?
    if [ "$rc" -eq 0 ] || ! contains_text "$out" 'overlap: kyc-service' \
       || [ "$(keep_noticed 13061)" != 1 ] || grep -q '^pr close' "$hev"; then
      echo "SELF-TEST FAIL case 37 (${path_name%%:*}): owner hold + overlap did not block #13061; rc=$rc notices=$(keep_noticed 13061)"; ok=1; dump_out "$out"
    fi
  done
  out="$(ov_run owner 13061 13060 "$NEW" "$OLD" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -eq 0 ] || ! contains_text "$out" 'overlap: kyc-service' || [ "$(keep_noticed 13061)" != 1 ]; then
    echo "SELF-TEST FAIL case 37 (stale-keep): owner overlap not reported/noticed; rc=$rc"; ok=1; dump_out "$out"
  fi
  # CLOSE path: case 32's pair shares the sanctions manifest.
  out="$(hold_run owner 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -eq 0 ] || [ "$(keep_noticed 11499)" != 1 ] || grep -q '^pr close' "$hev"; then
    echo "SELF-TEST FAIL case 37 (close): owner-held ancestor with shared pins did not block #11499; rc=$rc"; ok=1; dump_out "$out"
  fi
  # A notice already present is not posted again; the block itself still holds.
  out="$(ov_run owner 13061 13060 "$CCC" "$NEW" true 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -eq 0 ] || [ -n "$(mutations)" ]; then
    echo "SELF-TEST FAIL case 37 (noticed): rc=$rc mutations=$(mutations | tr '\n' '|')"; ok=1
  fi
  echo "self-test case 37 checked (owner hold + overlap -> non-zero on close/coverage-skip/skip/stale paths, one notice)"

  # case 38 — an unknown applier with overlapping pins blocks the same way.
  out="$(ov_run unknown 13061 13060 "$CCC" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -eq 0 ] || ! contains_text "$out" 'overlap: kyc-service'; then
    echo "SELF-TEST FAIL case 38: unknown applier + overlap exited $rc"; ok=1; dump_out "$out"
  else
    echo "self-test case 38 OK (unknown applier + overlap -> rc=$rc)"
  fi

  # case 39 — an owner hold on DISJOINT services does not block: rc 0, no survivor notice.
  out="$(ov_run owner 13062 13060 "$CCC" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ] || [ "$(keep_noticed 13062)" != 0 ]; then
    echo "SELF-TEST FAIL case 39: owner hold on disjoint pins blocked #13062; rc=$rc"; ok=1; dump_out "$out"
  else
    echo "self-test case 39 OK (owner hold, disjoint pins -> rc=0)"
  fi

  # case 40 — a bot-applied state never blocks, overlap or not.
  out="$(ov_run bot 13061 13060 "$CCC" 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ] || [ "$(keep_noticed 13061)" != 0 ]; then
    echo "SELF-TEST FAIL case 40: bot-applied state blocked #13061; rc=$rc"; ok=1; dump_out "$out"
  else
    echo "self-test case 40 OK (bot-applied state + overlap -> rc=0)"
  fi

  # case 41 — overlap that cannot be computed (unreadable list, or no image pins) is a block.
  local other
  for other in 13064 13065; do
    out="$(ov_run owner 13061 "$other" "$CCC" 2>&1)" && rc=0 || rc=$?
    if [ "$rc" -eq 0 ] || ! contains_text "$out" 'overlap cannot be computed'; then
      echo "SELF-TEST FAIL case 41: uncomputable overlap with #$other exited $rc"; ok=1; dump_out "$out"
    fi
  done
  echo "self-test case 41 checked (uncomputable overlap -> non-zero)"

  # case 29 — producer-run isolation (#13460) suffixes every branch with `-run<id>`. The sha must
  # still parse, or KEEP_SHA is empty and every run classifies every predecessor SKIP — measured on
  # main: `closed=0 skipped=24` with "could not read a commit sha off" on each run. A malformed
  # suffix (no digits, trailing text) remains unknown, never permission.
  if [ "$(sha_of_branch "${P}${NEW}-run39578054239" "$P")" != "$NEW" ]; then
    echo "SELF-TEST FAIL case 29: '-run<id>' branch did not yield its sha"; ok=1
  elif [ -n "$(sha_of_branch "${P}${NEW}-run" "$P")$(sha_of_branch "${P}${NEW}-runx1" "$P")$(sha_of_branch "${P}${NEW}-run1-x" "$P")" ]; then
    echo "SELF-TEST FAIL case 29: a malformed run suffix parsed as a sha"; ok=1
  else
    out="$(run "$P" 6222 "$NEW" "$(printf '6225\t%s%s-run123' "$P" "$OLD")" 2>&1)" && rc=0 || rc=$?
    if [ "$rc" -ne 0 ] || ! contains_text "$out" 'would close #6225'; then
      echo "SELF-TEST FAIL case 29: run-suffixed ancestor was not superseded; rc=$rc"; ok=1; dump_out "$out"
    else
      echo "self-test case 29 OK (run-suffixed branches parse; malformed suffixes stay unknown)"
    fi
  fi

  if [ "$ok" -ne 0 ]; then
    echo "self-test: FAILED"
    return 1
  fi
  echo "self-test: all cases OK"
  return 0
}

if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit $?
fi

PREFIX="${1:?usage: supersede-deploy-prs.sh <branch-prefix> <keep-pr-number> | --self-test}"
KEEP="${2:?usage: supersede-deploy-prs.sh <branch-prefix> <keep-pr-number> | --self-test}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must be set}"

# The sha THIS run's deploy PR pins. Without it ancestry cannot be classified, so close nothing;
# still inspect held candidates below because a pin overlap may require blocking the survivor.
KEEP_SHA="$(sha_of_branch "$(gh pr view "$KEEP" --repo "$REPO" --json headRefName --jq '.headRefName')" "$PREFIX")"
if [ -z "$KEEP_SHA" ]; then
  echo "::warning::supersede: could not read a commit sha off #$KEEP's branch — closing nothing; checking held pin overlaps fail-closed."
fi

# `gh pr list` caps at 30 by default; a backlog can exceed that, and a cap that silently truncates
# would leave the oldest — the very ones most likely to be conflicting — untouched.
#
# Read into a single variable, NOT `mapfile`: mapfile is a bash-4 builtin, absent from macOS's system
# bash 3.2 and from zsh, where it fails with "command not found" and leaves the array EMPTY. The
# script would then print "nothing to close" and exit 0 — a broken probe reporting a clean result,
# which is the failure mode this repo has been bitten by repeatedly.
PAIRS="$(gh pr list --repo "$REPO" --state open --limit 200 \
    --json number,headRefName,headRepositoryOwner,createdAt,isDraft,labels \
    --jq "[.[]
           | select(.headRefName | startswith(\"$PREFIX\"))
           | select(.number != ($KEEP | tonumber))
           | select(.headRepositoryOwner.login == \"${REPO%%/*}\")]
          | sort_by(.createdAt)
          | .[] | \"\(.number)\t\(.headRefName)\t\(if .isDraft or ([.labels[].name] | index(\"blocked\")) then 1 else 0 end)\"")"

run "$PREFIX" "$KEEP" "$KEEP_SHA" "$PAIRS"
exit $?
