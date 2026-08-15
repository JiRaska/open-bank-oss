#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# Did the verification-metadata sweep find a GAP, or did it fail to run? (issue #4162)
#
# WHY THIS EXISTS
# verification-metadata-drift.yml raises a tracking issue with `if: failure()`, which fires on
# ANY shard failure. The issue body is a fixed template asserting "at least one Gradle module on
# main resolves an artifact that verification-metadata.xml does not pin". On 2026-08-08 three of
# eight shards died of `Java heap space` and that template was published anyway — an issue whose
# central claim was never measured.
#
# The checker already knew the difference and said so in its own output ("this says nothing about
# whether the metadata is complete"), but returned 1 for both outcomes, so the one thing the
# caller reads could not distinguish them. It now exits 2 for "could not run", each shard records
# its verdict as an artifact, and this script reduces them.
#
# Verdicts are read from FILES, not from job logs. A job log contains the step's own `run:`
# script, so grepping it matches strings that never executed.
#
# Output (to $GITHUB_OUTPUT): kind=gap | could-not-run | unknown
#   gap           at least one shard found a real unpinned artifact -> the drift issue is true
#   could-not-run every failing shard died before reaching a verdict -> report a BUILD problem
#   unknown       no verdict files at all (upload failed, or the job died before writing one)
set -euo pipefail

dir="${1:?usage: classify-verification-sweep-failure.sh <verdict-dir>}"

gap=0
cnr=0
total=0
if [ -d "$dir" ]; then
  while IFS= read -r f; do
    total=$((total + 1))
    case "$(cat "$f")" in
      gap) gap=$((gap + 1)) ;;
      could-not-run) cnr=$((cnr + 1)) ;;
    esac
  done < <(find "$dir" -type f)
fi

# A single real gap outranks any number of infrastructure failures: the metadata IS incomplete,
# whatever else also went wrong. Only an all-infrastructure run may be reported as a build problem.
if [ "$gap" -gt 0 ]; then
  kind=gap
elif [ "$cnr" -gt 0 ]; then
  kind=could-not-run
else
  kind=unknown
fi

echo "kind=${kind}" >> "${GITHUB_OUTPUT:-/dev/stdout}"
echo "gaps=${gap}" >> "${GITHUB_OUTPUT:-/dev/stdout}"
echo "could_not_run=${cnr}" >> "${GITHUB_OUTPUT:-/dev/stdout}"
echo "classify-verification-sweep-failure: ${total} verdict(s) — ${gap} gap, ${cnr} could-not-run -> ${kind}"
