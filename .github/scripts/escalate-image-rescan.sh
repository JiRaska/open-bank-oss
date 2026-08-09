#!/usr/bin/env bash
# Escalation path for image-rescan.yml (issue #4026).
#
# WHY THIS EXISTS. image-rescan.yml ran four times (2026-07-20, -07-27, -08-03 and one
# earlier) and failed every time, and the failures were CORRECT: trivy found
# undispositioned fixable HIGH CVEs (libexpat CVE-2026-56408, p11-kit CVE-2026-2100) in
# the shipped `ghcr.io/jiraska/openbank-*-service:latest` images. Nothing happened for
# four weeks, because a scheduled workflow's red is addressed to nobody — it lands in the
# Actions tab, which nobody reads on a Monday morning. The repo already knows this shape
# ("anything whose output others depend on needs an escalation path"), and vex-triage.yml
# — named in image-rescan.yml's own header as sharing its escalation pattern — actually
# has one. image-rescan.yml did not. That asymmetry is the defect this closes.
#
# The finding itself (a fleet-wide base-image CVE) was deliberately NOT fixed here: it is
# a base-image bump plus a fleet redeploy, or ~50 VEX verdicts, and it needed its own PR.
# That PR bumped the pinned base digest and put `.github/workflows/Dockerfile.deploy` — the
# one recipe every published image is built from — under Dependabot, which is why it had sat
# at a 2026-07-16 base while upstream had already rebuilt it. What this script guarantees is
# that the next such finding is addressed to a human within a day instead of a month.
#
# DEDUPE. One issue per finding-class, keyed on an exact title, refreshed with a comment
# on each subsequent red run rather than opening a new issue every Monday — the same
# open-or-refresh shape vex-triage.py uses. A closed issue of the same title is NOT
# reopened: if a human closed it deliberately, a new run opens a fresh one, which is the
# louder and more honest signal.
#
# Inputs (env): GH_TOKEN, REPO, RUN_URL. `gh` needs an explicit -R because the working
# directory is not guaranteed to be a checkout.
set -euo pipefail

TITLE="Image rescan: undispositioned fixable CRITICAL/HIGH in shipped images"

existing="$(gh issue list -R "$REPO" --state open --search "\"$TITLE\" in:title" \
  --json number,title --jq "[.[] | select(.title == \"$TITLE\")] | .[0].number // empty")"

body_file="$(mktemp)"
{
  printf '%s\n\n' "The weekly \`Image rescan\` run found at least one undispositioned fixable CRITICAL/HIGH vulnerability in an image already published to GHCR."
  printf '%s\n\n' "Run: $RUN_URL"
  printf '%s\n' "Two ways to clear it, per the workflow's failure semantics:"
  printf '%s\n' "1. Bump the affected package and redeploy the service (Dependabot usually has the PR open already)."
  printf '%s\n' "2. Record a VEX verdict in \`openbank-libs/governance/vex/<component>.openvex.json\` if the finding is not exploitable in this context."
  printf '\n%s\n' "A red rescan that nobody actions is the same as having no rescan — that is why this issue exists rather than only a red run (#4026)."
} > "$body_file"

if [ -n "$existing" ]; then
  gh issue comment "$existing" -R "$REPO" --body-file "$body_file"
  echo "::notice::refreshed existing escalation issue #${existing}"
else
  # Labels are code (.github/labels.yml) — only names declared there. Deliberately NOT
  # `vex-triage`: vex-triage.py enumerates open issues BY that label and auto-closes the
  # ones whose CVE has left its queue, so borrowing it would hand this issue's lifecycle
  # to a script that knows nothing about it.
  url="$(gh issue create -R "$REPO" --title "$TITLE" --body-file "$body_file" \
    --label "severity:high" --label governance)"
  echo "::notice::opened escalation issue ${url}"
fi
