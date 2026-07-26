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
xmxdir="$workdir/xmx"          # one file per pid, caching that JVM's fixed -Xmx
mkdir -p "$xmxdir"
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

    # Heap USED. Only the collector's heap line — `GC.heap_info` also prints "Metaspace
    # used ..." and "class space used ...", and summing those in inflated every figure by
    # roughly a gigabyte per JVM. Metaspace is not heap and is not bounded by -Xmx, so
    # counting it against -Xmx compared two different things (#2330).
    used_kb="$(printf '%s\n' "$info" \
      | grep -vE '^[[:space:]]*(Metaspace|class space)' \
      | grep -oE 'used [0-9]+[KMG]?' \
      | awk '{v=$2; u=substr(v,length(v)); n=v+0;
              if (u=="G") n*=1048576; else if (u=="M") n*=1024;
              s+=n} END {if (NR>0) printf "%d", s}')"

    # Heap CEILING — read the effective -Xmx from VM.flags, never from GC.heap_info.
    #
    # This is the whole point of the fix. `GC.heap_info`'s "reserved"/"capacity" is the
    # collector's current region bookkeeping, NOT the max heap, and on a JVM launched with
    # `-Xmx6g` the old parse reported a 1344 MiB "ceiling" and therefore "454% of its
    # configured -Xmx" — a figure that is arithmetically impossible for a real heap
    # ceiling, since a JVM that exceeded its max heap would have thrown OutOfMemoryError
    # rather than finished. `-XX:MaxHeapSize` is unambiguous, always present, and always
    # exactly the effective -Xmx, in bytes.
    # Cached per pid: -Xmx is fixed for a JVM's lifetime, and `jcmd` is not free — it
    # attaches to the target over a socket. Re-asking every JVM every five seconds would
    # add load to the very job whose memory pressure this script exists to measure, which
    # would be a self-defeating way to instrument it.
    if [ -s "$xmxdir/$pid" ]; then
      max_kb="$(cat "$xmxdir/$pid")"
    else
      max_kb="$(jcmd "$pid" VM.flags 2>/dev/null | tr ' ' '\n' \
        | awk -F= '/^-XX:MaxHeapSize=/ {printf "%d", $2/1024; exit}')"
      [ -n "$max_kb" ] && printf '%s' "$max_kb" > "$xmxdir/$pid"
    fi

    # No parse => say so. Never substitute 0, which would read as "used nothing".
    [ -n "$used_kb" ] || continue

    name="$(printf '%s' "$rest" | awk '{print $1}' | sed 's|.*[./]||')"
    printf '%s\t%s\t%s\t%s\t%s\n' \
      "$round" "$pid" "${name:-jvm}" "$((used_kb / 1024))" "$(( ${max_kb:-0} / 1024 ))"
  done >> "$samples"

  # Available system RAM, in MiB. /proc/meminfo is the CI (Linux) path; vm_stat is the
  # macOS fallback, and it exists purely so the memory-starved branch of the report can be
  # exercised by hand on a developer machine. A warning path that can only be reached on a
  # runner is a warning path nobody has ever read — which is how the contradictory advice
  # below shipped in the first place.
  if [ -r /proc/meminfo ]; then
    awk '/^MemAvailable:/ {print int($2/1024)}' /proc/meminfo >> "$freefile"
  elif command -v vm_stat >/dev/null 2>&1; then
    vm_stat 2>/dev/null | awk '
      /page size of/ { for (i=1;i<=NF;i++) if ($i+0 > 1024) { ps=$i+0; break } }
      /^Pages free/ || /^Pages inactive/ { gsub(/\./,"",$NF); f += $NF }
      END { if (ps > 0 && f > 0) print int(f*ps/1048576) }' >> "$freefile"
  fi
}

sampler_loop() {
  round=0
  while :; do
    round=$((round + 1))
    sample_once
    sleep "$SAMPLE_INTERVAL_SECONDS"
  done
}
round=0

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
  round=999999           # distinct round id so this sample is its own concurrent snapshot
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
  # Max used per pid, carrying that pid's ceiling. Fields: round pid name used max.
  reduced="$workdir/reduced.tsv"
  awk -F'\t' '{ if ($4+0 > used[$2]) {used[$2]=$4+0; name[$2]=$3}
                if ($5+0 > max[$2]) max[$2]=$5+0 }
              END { for (p in used) printf "%s\t%s\t%d\t%d\n", p, name[p], used[p], max[p] }' \
    "$samples" | sort -k3,3nr > "$reduced"

  # PEAK CONCURRENT footprint: the largest per-round sum across all live JVMs.
  #
  # On a shared 16 GB runner this is the number that decides whether a worker can fork, and
  # the per-JVM table cannot express it — that table is a peak-per-pid roll-up over the whole
  # build, so ~40 rows of `GradleWorkerMain` are mostly JVMs that lived and died at different
  # times, not 40 at once. Reading it as a concurrent total overstates the load; reading any
  # single row as the load understates it. Neither is the quantity #2330 is about.
  peak_concurrent="$(awk -F'\t' '{ s[$1] += $4+0; n[$1]++ }
                                  END { for (r in s) if (s[r] > m) { m = s[r]; c = n[r] }
                                        printf "%d %d", m, c }' "$samples")"
  peak_total="${peak_concurrent%% *}"
  peak_jvms="${peak_concurrent##* }"

  {
    echo "| JVM | peak heap used | -Xmx | used |"
    echo "|---|---:|---:|---:|"
    awk -F'\t' '{
      if ($4 > 0) { pct = sprintf("%.0f%%", 100*$3/$4); ceil = $4 " MiB" }
      else        { pct = "n/a";                        ceil = "not reported" }
      printf "| `%s` (pid %s) | %s MiB | %s | %s |\n", $2, $1, $3, ceil, pct
    }' "$reduced"
    echo
    echo "Peak **concurrent** heap across all live JVMs: **${peak_total} MiB** (${peak_jvms} JVMs in that sample)."
    echo
    echo "_Rows are peak-per-JVM over the whole build, so they do not sum to a concurrent"
    echo "total — most of those JVMs never existed at the same moment. Use the concurrent"
    echo "figure above and the system-memory floor below to judge runner pressure._"
  } >> "$report"

  worst="$(awk -F'\t' 'BEGIN{w=-1} $4>0 { p = 100*$3/$4; if (p>w) w=p } END{printf "%d", (w<0 ? -1 : int(w+0.5))}' "$reduced")"

  if [ -s "$freefile" ]; then
    min_free="$(sort -n "$freefile" | head -1)"
    echo >> "$report"
    echo "Lowest available system memory during the build: **${min_free} MiB**." >> "$report"
    if [ "$min_free" -lt "$MIN_FREE_MB" ]; then
      memory_starved=1
      echo "::warning title=CI runner nearly out of memory::\`$label\` ran with only ${min_free} MiB of available system RAM at its lowest point (floor ${MIN_FREE_MB} MiB). At this level the kernel starts reclaiming, Gradle worker processes fail to start, and the runner can be killed outright — which GitHub reports as a *cancelled* job with no failing step, not as a failure. See #2330."
    fi
  fi
fi

if [ "$worst" -ge "$WARN_AT_PERCENT" ]; then
  # "Raise -Xmx" is only sound advice when the MACHINE has memory to give. If the runner
  # already bottomed out below MIN_FREE_MB, a bigger heap makes the fork failures worse,
  # not better — and the two warnings would otherwise print adjacent, opposite
  # instructions. The first real run of this script on the CodeQL job did exactly that:
  # "only 499 MiB of available system RAM" immediately followed by "raise -Xmx". Advice
  # that contradicts itself is worse than none, because someone acts on one half.
  if [ "${memory_starved:-0}" = "1" ]; then
    echo "::warning title=Gradle heap pressure on a full runner::\`$label\` peaked at ${worst}% of its configured -Xmx AND the runner bottomed out at ${min_free:-?} MiB of available RAM. Do NOT raise -Xmx here — the machine has nothing to give, and a larger heap makes the worker-fork failures worse. Reduce the concurrent footprint instead (fewer parallel workers, a smaller Kotlin daemon heap, or less work per job). See #2330."
    echo >> "$report"
    echo "> :warning: Peaked at **${worst}%** of -Xmx **while the runner was memory-starved**" >> "$report"
    echo "> (${min_free:-?} MiB free at the floor). This is a *reduce the footprint* signal," >> "$report"
    echo "> not a *raise -Xmx* one." >> "$report"
  else
    echo "::warning title=Gradle heap headroom low::\`$label\` peaked at ${worst}% of its configured -Xmx (warn at ${WARN_AT_PERCENT}%). This build did not OOM, but the next fleet growth may. Raise -Xmx in this workflow's GRADLE_OPTS before it goes red, not after. See #2177."
    echo >> "$report"
    echo "> :warning: Peaked at **${worst}%** of -Xmx — above the ${WARN_AT_PERCENT}% warning line." >> "$report"
  fi
elif [ "$worst" -ge 0 ]; then
  echo >> "$report"
  echo "Peak was **${worst}%** of -Xmx (warning line ${WARN_AT_PERCENT}%)." >> "$report"
fi

cat "$report"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$report" >> "$GITHUB_STEP_SUMMARY"

rm -rf "$workdir"
exit "$rc"
