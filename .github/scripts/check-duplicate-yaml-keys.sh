#!/usr/bin/env bash
# Guard against a silent SmallRye Config / SnakeYAML footgun:
#
#   openbank:
#     rate-limit: { enabled: true }      # <- SILENTLY DISCARDED
#   ...
#   openbank:
#     outbox: { poll-interval: 5s }      # <- only this block survives
#
# A duplicate mapping key is NOT merged — SnakeYAML (and therefore SmallRye Config,
# which Quarkus uses to load application.yaml) keeps the LAST occurrence and
# silently drops every earlier one. The dropped keys then fall back to library
# defaults, so a boot smoke-test passes while the intended config is gone. This is
# the defect class behind:
#   - #1170: a duplicate `quarkus.http` block dropped the configured port (8107).
#   - #1193: duplicate top-level `openbank:` blocks dropped rate-limit / resilience
#            / service config across 9 services (and a nested `psd2:` dup in psd2).
#
# This check flags any service application.yaml that declares the same key twice
# in the same mapping at ANY nesting level. It uses yamllint's `key-duplicates`
# rule (yamllint is already a required tool in the CI `validate` job) with a config
# that enables ONLY that rule, so it never double-lints style. Duplicate mapping
# keys are invalid YAML by spec, so there are no false positives to suppress.
#
# Mode (mirrors the ADR-0071 / ADR-0034 / ADR-0074 advisory→enforce rollout):
#   (default)   advisory -> warn (::warning::) on duplicates, exit 0. Stays green
#                           while the #1193 sweep PRs (#1185–#1202, one per service)
#                           land and the last stragglers (consent, pid) merge.
#   --enforce            -> hard fail (exit 1) on any duplicate. Flip to this — a
#                           one-flag change in .github/workflows/ci.yml — once the
#                           fleet is clean, so the gate becomes a true CI blocker.
#
# Usage: check-duplicate-yaml-keys.sh [root-dir] [--enforce]
set -euo pipefail
ROOT="."
ENFORCE=0
for arg in "$@"; do
  case "$arg" in
    --enforce) ENFORCE=1 ;;
    *) ROOT="$arg" ;;
  esac
done

# Minimal yamllint config: only the duplicate-key rule, nothing else.
CFG="$(mktemp "${TMPDIR:-/tmp}/dup-keys-XXXXXX.yml")"
trap 'rm -f "$CFG"' EXIT
cat >"$CFG" <<'YML'
---
rules:
  key-duplicates: enable
YML

# Every service's runtime config: <service>/src/main/resources/application.yaml.
# Exclude build outputs and isolated git worktrees (.claude/worktrees) so we only
# gate the committed source tree. (find|xargs, not mapfile — the macOS pool member
# ships bash 3.2; service paths contain no spaces.)
# ...and openbank-libs/governance/*.yaml, which has the same failure with a wider blast
# radius. `rules.yaml` is what CI enforces, and nothing lints it: yamllint's own gate covers
# `openbank-infra .github` only, so a key added above an existing one is dropped by SnakeYAML
# with no error anywhere and the rule simply stops applying (#2457 found this the hard way, by
# diffing yamllint finding sets by hand). Measured 2026-08-09: those 15 files have zero
# duplicates today, so this lands green and can only fire on a regression.
files="$(
  find "$ROOT" \
    \( -type d \( -name build -o -name node_modules -o -name .git -o -name .claude \) -prune \) -o \
    \( -path '*/src/main/resources/application.yaml' -print \) -o \
    \( -path '*/openbank-libs/governance/*.yaml' -print \)
)"

if [ -z "$files" ]; then
  # Not "nothing to check" — this gate has two corpora and both are large. An empty list
  # means the scope moved, and reporting that as a pass is the #4339 failure exactly.
  echo "::error::check-duplicate-yaml-keys: no application.yaml and no governance yaml found" \
       "under '$ROOT' — the scope moved, the gate did not."
  exit 1
fi

# yamllint prints nothing (and exits 0) when clean; with only key-duplicates
# enabled, any output is a real duplicate. `|| true` keeps set -e from aborting on
# the non-zero exit so we can annotate the findings ourselves.
count_files="$(printf '%s\n' "$files" | grep -c . || true)"
echo "SUBJECTS=$count_files"
violations="$(printf '%s\n' "$files" | xargs yamllint -f parsable -c "$CFG" || true)"

if [ -z "$violations" ]; then
  echo "check-duplicate-yaml-keys: $count_files file(s) checked (service application.yaml + openbank-libs/governance), no duplicate keys."
  exit 0
fi

count="$(printf '%s\n' "$violations" | grep -c . || true)"
level="warning"; [ "$ENFORCE" -eq 1 ] && level="error"
echo "::${level}::Found $count duplicate YAML key(s) in service application.yaml files."
echo "Duplicate mapping keys are SILENTLY DISCARDED by SmallRye Config / SnakeYAML"
echo "(last occurrence wins) — the dropped config falls back to defaults and a boot"
echo "smoke-test will NOT catch it. See #1170 (quarkus.http) and #1193 (openbank)."
echo "Fix: merge the duplicate blocks into a single key. Findings:"
echo ""
printf '%s\n' "$violations"

if [ "$ENFORCE" -eq 1 ]; then
  exit 1
fi
echo ""
echo "check-duplicate-yaml-keys: ADVISORY mode — not failing the build. Add --enforce"
echo "in .github/workflows/ci.yml once the fleet is clean to make this a hard gate."
exit 0
