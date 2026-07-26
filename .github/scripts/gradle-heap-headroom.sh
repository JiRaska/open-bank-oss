#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Measures how much Gradle heap a CI build ACTUALLY used, so `-Xmx` stops being a ratchet
# discovered by going red (issue #2177 problem 1).
#
# Today `fleet-lint`, `dependency-submission` and `security` each carry a hand-raised `-Xmx`
# (6g, 6g, 6g) with a comment explaining which OOM caused the last raise. Nothing anywhere
# records how close the current value is to the next one, so the only signal that headroom has run
# out is the build dying — and #2177 measured what that costs: an OOM'd `fleet-lint` reported a
# plain red while having silently left half the fleet unlinted.
#
# Usage:
#     .github/scripts/gradle-heap-headroom.sh <label> -- ./gradlew <tasks...>
#
# Wraps the command, samples every JVM it spawns, and reports peak heap per JVM against that
# JVM's own maximum. Writes a table to $GITHUB_STEP_SUMMARY when running under Actions, and always
# to stdout. Emits a ::warning when any JVM crosses WARN_AT_PERCENT of its ceiling, or when the
# machine's available RAM drops below MIN_FREE_MB.
#
# Deliberately NEVER changes the build's verdict: it exits with the wrapped command's exit code and
# nothing else. A measurement that can fail a build is a measurement people delete.
#
# Two things it is careful about, both learned the hard way in this repo:
#   * `jcmd` output is parsed for numbers this script itself computes with, so a parse that finds
#     nothing reports "not measured" rather than a plausible 0 — a silent 0 would read as infinite
#     headroom, which is the exact wrong direction to fail in.
#   * A JVM that dies mid-build (the OOM case, the one that matters most) keeps whatever peak it
#     reached: samples are accumulated per pid as they are taken, never re-read at the end.
set -uo pipefail

SAMPLE_INTERVAL_SECONDS="${SAMPLE_INTERVAL_SECONDS:-5}"
WARN_AT_PERCENT="${WARN_AT_PERCENT:-85}"
MIN_FREE_MB="${MIN_FREE_MB:-512}"

label="${1:?usage: gradle-heap-headroom.sh <label> -- <command...>}"
shift
[ "${1:-}" = "--" ] || { echo "gradle-heap-headroom: expected -- before the command" >&2; exit 2; }
shift
[ "$#" -gt 0 ] || { echo "gradle-heap-headroom: no command given" >&2; exit 2; }

workdir="$(mktemp -d)" || workdir=""
# Guard the empty case explicitly: an unset workdir would silently turn every path below into an
# absolute one ("/samples.tsv") and the script would go on "working" against the filesystem root.
[ -n "$workdir" ] && [ -d "$workdir" ] || { echo "gradle-heap-headroom: cannot create a temp dir" >&2; exit 2; }
samples="$workdir/samples.tsv"   # pid <TAB> name <TAB> used_mb <TAB> max_mb
freefile="$workdir/free.txt"
: > "$samples"
: > "$freefile"

# --- the sampler -----------------------------------------------------------------------------
# One line per (pid, observation). The reducer takes the max per pid, so a JVM that exits early
# still contributes its peak.
sample_once() {
  # `jcmd -l` lists live JVMs as "<pid> <main class or jar>". Skip jcmd's own pid.
  jcmd -l 2>/dev/null | while read -r pid rest; do
    case "$pid" in ''|*[!0-9]*) continue ;; esac
    case "$rest" in *jdk.jcmd*) continue ;; esac

    info="$(jcmd "$pid" GC.heap_info 2>/dev/null)" || continue

    # Sum "used" across the heap regions the collector reports, and take the ceiling from
    # "reserved"/"capacity" depending on collector. Values arrive as e.g. "used 1234M" or
    # "used 1234567K".
    used_kb="$(printf '%s\n' "$info" | grep -oE 'used [0-9]+[KMG]?' \
      | awk '{v=$2; u=substr(v,length(v)); n=v+0;
              if (u=="G") n*=1048576; else if (u=="M") n*=1024;
              s+=n} END {if (NR>0) printf "%d", s}')"
    max_kb="$(printf '%s\n' "$info" | grep -oE '(reserved|total reserved|capacity) [0-9]+[KMG]?' \
      | awk '{v=$NF; u=substr(v,length(v)); n=v+0;
              if (u=="G") n*=1048576; else if (u=="M") n*=1024;
              if (n>m) m=n} END {if (NR>0) printf "%d", m}')"

    # No parse => say so. Never substitute 0, which would read as "used nothing".
    [ -n "$used_kb" ] || continue

    name="$(printf '%s' "$rest" | awk '{print $1}' | sed 's|.*[./]||')"
    printf '%s\t%s\t%s\t%s\n' "$pid" "${name:-jvm}" "$((used_kb / 1024))" "$(( ${max_kb:-0} / 1024 ))"
  done >> "$samples"

  if [ -r /proc/meminfo ]; then
    awk '/^MemAvailable:/ {print int($2/1024)}' /proc/meminfo >> "$freefile"
  fi
}

sampler_loop() {
  while :; do
    sample_once
    sleep "$SAMPLE_INTERVAL_SECONDS"
  done
}

if command -v jcmd >/dev/null 2>&1; then
  sampler_loop &
  sampler_pid=$!
else
  echo "gradle-heap-headroom: jcmd not on PATH — running the command unmeasured." >&2
  sampler_pid=""
fi

# --- the build -------------------------------------------------------------------------------
"$@"
rc=$?

if [ -n "$sampler_pid" ]; then
  sample_once            # one last sample before the JVMs are gone
  kill "$sampler_pid" 2>/dev/null || true
  wait "$sampler_pid" 2>/dev/null || true
fi

# --- the report ------------------------------------------------------------------------------
report="$workdir/report.md"
{
  echo "### Gradle heap headroom — \`$label\`"
  echo
} > "$report"

if [ ! -s "$samples" ]; then
  {
    echo "No JVM heap samples were taken (no \`jcmd\`, or the build finished inside one sample"
    echo "interval). Heap headroom is **not measured** for this run — treat it as unknown, not as fine."
  } >> "$report"
  worst=-1
else
  # Max used per pid, carrying that pid's ceiling.
  reduced="$workdir/reduced.tsv"
  sort -k1,1n "$samples" \
    | awk -F'\t' '{ if ($3+0 > used[$1]) {used[$1]=$3+0; name[$1]=$2}
                    if ($4+0 > max[$1]) max[$1]=$4+0 }
                  END { for (p in used) printf "%s\t%s\t%d\t%d\n", p, name[p], used[p], max[p] }' \
    | sort -k3,3nr > "$reduced"

  {
    echo "| JVM | peak heap used | ceiling | used |"
    echo "|---|---:|---:|---:|"
    awk -F'\t' '{
      if ($4 > 0) { pct = sprintf("%.0f%%", 100*$3/$4); ceil = $4 " MiB" }
      else        { pct = "n/a";                        ceil = "not reported" }
      printf "| `%s` (pid %s) | %s MiB | %s | %s |\n", $2, $1, $3, ceil, pct
    }' "$reduced"
    echo
  } >> "$report"

  worst="$(awk -F'\t' 'BEGIN{w=-1} $4>0 { p = 100*$3/$4; if (p>w) w=p } END{printf "%d", (w<0 ? -1 : int(w+0.5))}' "$reduced")"

  if [ -s "$freefile" ]; then
    min_free="$(sort -n "$freefile" | head -1)"
    echo "Lowest available system memory during the build: **${min_free} MiB**." >> "$report"
    if [ "$min_free" -lt "$MIN_FREE_MB" ]; then
      echo "::warning title=CI runner nearly out of memory::\`$label\` ran with only ${min_free} MiB of available system RAM at its lowest point (floor ${MIN_FREE_MB} MiB). At this level the kernel starts reclaiming, Gradle worker processes fail to start, and the runner can be killed outright — which GitHub reports as a *cancelled* job with no failing step, not as a failure. See #2330."
    fi
  fi
fi

if [ "$worst" -ge "$WARN_AT_PERCENT" ]; then
  echo "::warning title=Gradle heap headroom low::\`$label\` peaked at ${worst}% of its configured -Xmx (warn at ${WARN_AT_PERCENT}%). This build did not OOM, but the next fleet growth may. Raise -Xmx in this workflow's GRADLE_OPTS before it goes red, not after. See #2177."
  echo >> "$report"
  echo "> :warning: Peaked at **${worst}%** of the configured ceiling — above the ${WARN_AT_PERCENT}% warning line." >> "$report"
elif [ "$worst" -ge 0 ]; then
  echo >> "$report"
  echo "Peak was **${worst}%** of the configured ceiling (warning line ${WARN_AT_PERCENT}%)." >> "$report"
fi

cat "$report"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$report" >> "$GITHUB_STEP_SUMMARY"

rm -rf "$workdir"
exit "$rc"
