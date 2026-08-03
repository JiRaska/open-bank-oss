#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# Decide WHICH VERSION can-i-deploy should ask the broker about (issue #3082, defect 1).
#
# THE PROBLEM THIS SOLVES
# auto-deploy asks `can-i-deploy --pacticipant <svc> --latest main`, and `can-i-deploy`
# has `needs: [changes, build-push]` — auto-deploy's OWN image build, NOT services-ci,
# which is the workflow that publishes pact versions. Both are triggered by the same push
# and run concurrently, so the gate routinely asks about a commit whose pacts are still
# being built. Measured on run 30690770972: it asked about account-service at 08:36:49,
# and that service's build did not START until 08:39:03.
#
# `--latest main` then resolves to the PREVIOUS main build's version, and the answer is
# about a different commit than the one being deployed. That has two outcomes, and the
# quieter one is the dangerous one:
#
#   * the older version was NOT verified  → red, labelled PENDING_BUILD. Noisy, honest,
#     self-clearing. This is the half that shows up in the failure rate.
#   * the older version WAS verified      → GREEN, and auto-deploy ships an image built
#     from $GITHUB_SHA whose contracts were never verified at all. Nothing anywhere says
#     so. A contract gate that answers about the wrong version is not a gate.
#
# The broker says this itself, on every single run:
#   WARN: It is recommended to specify the version number (rather than the tag or branch)
#   of the pacticipant you wish to deploy to avoid race conditions. Without a version
#   number, this result will not be reliable.
#
# WHY NOT JUST ALWAYS PASS --version $GITHUB_SHA
# Because the existing `--latest main` is not an oversight — auto-deploy.yml records the
# reason: path-scoped CI skips most services on any given commit, so for those the broker
# legitimately has no version for $GITHUB_SHA and `--version` errors with "No pacts or
# verifications". Switching unconditionally would break the common case to fix the rare
# one. So the selector is chosen from a PROBE of whether this commit's version actually
# exists, and falls back to today's exact behaviour whenever it does not.
#
# WHY A SEPARATE SCRIPT
# Same reason as classify-can-i-deploy-block.sh next door: inline in the workflow this is
# unreachable by any test, and a gate whose version-selection logic is untested is a gate
# that can silently start asking about the wrong thing. Here it is a pure function of
# (probe result, sha) with no network of its own, so the unit test can drive every branch.
#
# WHY A workflow_dispatch WITH NO VERSION IS REFUSED (issue #3318)
# The `no` fallback is right for a PUSH: path-scoped CI skips most services on most commits,
# so most of the fleet legitimately has no version for that sha. It is NOT right for a manual
# dispatch. auto-deploy builds `sandbox-${GITHUB_SHA::8}` from the CURRENT main tip, a commit
# services-ci never built for that service — so a version for it cannot exist, and falling
# back means the gate answers about a DIFFERENT commit than the one being pinned into gitops.
# Both outcomes are wrong and the quiet one ships: see the "WHY NOT JUST ALWAYS PASS" note
# above. Measured on openbank-fx-service 2026-08-02 — five manual dispatches, five verdicts
# about consumer version 8d321a8, a commit unrelated to the change being deployed (#3306).
# So on (no + workflow_dispatch) this emits the REFUSE sentinel and the caller stops. Push
# behaviour is untouched.
#
# Usage:
#   PACT_VERSION_PRESENT=yes|no|absent|unknown|equivalent:<sha> [EVENT_NAME=<github.event_name>] \
#     resolve-can-i-deploy-selector.sh <service> <sha>
#
#   PACT_VERSION_PRESENT — the caller's probe of
#     GET <broker>/pacticipants/<svc>/versions/<sha>, same vocabulary the classifier uses:
#       yes     → 200, a version exists for THIS commit
#       equivalent:<sha>
#               → no version for THIS commit, but the probe PROVED from git that <sha> — the
#                 commit that does have one — is byte-identical in every build input of this
#                 service (#3432). Not a fallback: a narrower, justified question.
#       no      → 404 on the version, but the pacticipant EXISTS: this commit has published nothing yet
#       absent  → the broker does not know the pacticipant AT ALL (no contracts either way)
#       unknown → the probe itself failed (broker unreachable / non-2xx-non-404)
#
# Prints one TAB-separated line: <selector args>\t<human reason>
# The selector is emitted as the literal CLI arguments, so the caller does no string
# surgery on it — a caller that has to re-split the answer is a caller that can get it
# wrong.
#
# Always exits 0 — this chooses a question, it does not answer one.
set -uo pipefail

SVC="${1:-}"
SHA="${2:-}"
if [ -z "$SVC" ] || [ -z "$SHA" ]; then
  echo "usage: PACT_VERSION_PRESENT=yes|no|absent|unknown|equivalent:<sha> $0 <service> <sha>" >&2
  exit 2
fi

PRESENT="${PACT_VERSION_PRESENT:-unknown}"
# Absent EVENT_NAME behaves as a push: the refusal is opt-in on the event that needs it, so a
# caller that forgets to pass it keeps today's behaviour rather than blocking the fleet.
EVENT="${EVENT_NAME:-push}"

emit() { printf '%s\t%s\n' "$1" "$2"; }

# A malformed `equivalent:` — no sha, or something that is not one — is not a proof of anything,
# and must not become a bare `--version` with an empty argument, which the pact CLI would read as
# the next flag. Demote it to plain `no`, which on a dispatch is #3318's REFUSE.
case "$PRESENT" in
  equivalent:*)
    case "${PRESENT#equivalent:}" in
      [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;;
      *) PRESENT=no ;;
    esac
    ;;
esac

case "$PRESENT" in
  equivalent:*)
    # #3432. The probe could not find a version for THIS sha, but it proved — from git tree
    # objects, via pact-version-tree-equivalent.sh — that the commit which DOES have one is
    # byte-identical to this one in every input this service's image is built from, and is an
    # ancestor of it. So this is not the #3318 case at all: there is no "different commit" here
    # in any sense that can reach the artifact, and asking by version number is more precise than
    # the `--latest main` that a push would have used. Without this the manual/reconcile path can
    # deploy NOTHING, ever — 54 of 54 refused on run 30761923908 — which is the state the whole
    # fleet lands in exactly when a reconcile is needed. The refusal below is untouched for every
    # case where the equivalence could NOT be proved, including a broker the probe could not read.
    emit "--version ${PRESENT#equivalent:}" \
      "no pact version for ${SHA}, but ${PRESENT#equivalent:} is byte-identical to it in every build input of ${SVC} (git tree objects, ancestor) — asking about that version by number is a question about the same source, not about a different commit (#3432, #3318)"
    ;;
  yes)
    # The precise question, and the one the broker's own warning asks for: can THIS commit
    # of this service go to sandbox? No race window, and a green here cannot be borrowed
    # from an older build.
    emit "--version ${SHA}" \
      "this commit's pact version is in the broker — asking about it exactly, so the verdict cannot be inherited from an earlier build"
    ;;
  no)
    if [ "$EVENT" = "workflow_dispatch" ]; then
      # A manual deploy of a sha nobody has built cannot be gated on itself, and gating it on
      # another commit is what #3318 exists to stop. Refuse instead of answering the wrong
      # question — a manual dispatch is a human act, so a loud stop is actionable where a
      # misdirected verdict is not.
      emit "REFUSE" \
        "workflow_dispatch of ${SHA} which has no published pact version for ${SVC} — the gate cannot ask about the sha being deployed, and asking about a different commit is not a gate (#3318)"
    else
      # Today's behaviour on a push, deliberately unchanged. `--version` would error "No pacts
      # or verifications" for a service path-scoped CI skipped on this commit, which is most
      # of the fleet on most commits. The block classifier downstream then labels this
      # PENDING_BUILD from the same probe result, and the reconcile tick re-drives it.
      emit "--latest main" \
        "no pact version for this commit — falling back to latest/main (ADR-0092); the block classifier will label this PENDING_BUILD"
    fi
    ;;
  absent)
    # The broker has never heard of this pacticipant: the service publishes no consumer pact
    # and verifies no provider pact, so there is no contract for can-i-deploy to check. This is
    # NOT the #3318 case — that one is a service WITH contracts whose sha has no version, where
    # answering about a different commit would be a false verdict. Here there is no question to
    # answer at all, and REFUSE would make a service undeployable for as long as it has no
    # pacts, which is every service's first deploy (it blocked openbank-delegation-service's).
    # `--latest main` on an unknown pacticipant is the pre-#3318 behaviour and can-i-deploy
    # answers it the same way it answers a 404 pacticipant today.
    emit "--latest main" \
      "broker does not know ${SVC} — no published contracts either way, so there is nothing to verify (not the #3318 case: that is a service WITH contracts and no version for this sha)"
    ;;
  *)
    # Probe failed. Fall back rather than invent precision: an unreachable broker must not
    # silently change which question the gate asks.
    emit "--latest main" \
      "broker probe inconclusive — falling back to latest/main rather than assuming a version that may not exist"
    ;;
esac
