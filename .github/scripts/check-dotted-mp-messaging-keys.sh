#!/usr/bin/env bash
# Guard against a silent SmallRye Config / Quarkus YAML footgun in Kafka channel config:
#
#   mp:
#     messaging:
#       incoming:
#         my-channel:
#           group.id: my-consumer-group        # <- SILENTLY DISCARDED
#           auto.offset.reset: earliest        # <- SILENTLY DISCARDED
#
# quarkus-config-yaml (SmallRye's YamlConfigSource#flattenYaml) unconditionally quotes
# any YAML leaf map key containing a literal dot when registering it as a MicroProfile
# Config property name — a YAML key written as `group.id: foo` registers ONLY as the
# property name `mp.messaging.incoming.my-channel."group.id"` (literal quote characters
# in the property name), NEVER as the plain `mp.messaging.incoming.my-channel.group.id`.
# This happens whether or not the YAML key itself is written quoted or unquoted.
#
# Quarkus's KafkaConnectorIncomingConfiguration getters (getGroupId(), getAutoOffsetReset(),
# the dead-letter-queue.topic equivalent, ...) read the PLAIN unquoted key, so they
# silently resolve to Optional.empty() and fall back to defaults — group.id falls back
# to quarkus.application.name. If that fallback isn't the identity granted Read/Describe
# in the service's Strimzi KafkaUser ACL, every consumer poll gets a silent
# org.apache.kafka.common.errors.GroupAuthorizationException (fail-silent, no error
# logged, until it blocks Quarkus's synchronous startup window and becomes a fatal
# crash-loop). auto.offset.reset has a quieter failure mode: it silently falls back to
# Kafka's client default (latest) instead of an intended earliest, so a consumer
# silently skips backlogged messages on first boot / after any group reset.
#
# Root-caused while investigating the openbank-fraud-service boot-crash; a fleet audit
# (open-bank-oss#686) found the same pattern live (BROKEN, not just latent) in
# audit-service, notification-service, kyc-service, onboarding-service,
# card-issuance-service, and agent-service.
#
# This check flags ANY YAML leaf key containing a literal dot inside an
# mp.messaging.(incoming|outgoing).<channel> block in a service application.yaml —
# not just group.id/auto.offset.reset/dead-letter-queue.topic. Some dotted keys (e.g.
# value.deserializer, failure-strategy) empirically resolve correctly anyway via a
# different, coincidental Quarkus Kafka connector default/alias path — but that's not
# something this static check can safely tell apart from the broken ones, and the
# syntax itself is the defect: never write a literal dot in an mp.messaging leaf key.
# Fix: move the value to an MP_MESSAGING_INCOMING_<CHANNEL>_<KEY> (or _OUTGOING_)
# environment variable in the service's gitops manifest instead (env vars go through
# MicroProfile Config's env-var source directly and bypass the YAML flattener).
#
# Mode (mirrors the ADR-0071/ADR-0034/ADR-0074/#1193 advisory->enforce rollout):
#   (default)   advisory -> warn (::warning::) on any finding, exit 0. The 12-service
#                           audit in #686 is not a fleet-wide guarantee — the other
#                           ~20 services haven't been individually verified yet.
#   --enforce            -> hard fail (exit 1). Flip once the full fleet is confirmed
#                           clean (or each remaining dotted key is deliberately
#                           accepted and moved to an env var).
#
# stdlib-only (POSIX awk + shell); no PyYAML/yamllint dependency.
# Usage: check-dotted-mp-messaging-keys.sh [root-dir] [--enforce]
set -euo pipefail
# --- self-test ------------------------------------------------------------------------
# A literal dot in an `mp.messaging.(incoming|outgoing).<channel>` leaf key registers as a
# QUOTED MicroProfile property that the Kafka connector's plain getters never read — the value
# silently falls back to a default. `group.id` written this way caused the #686 incident
# chain, and `auto.offset.reset` has no coincidence to hide behind at all.
#
# The trap this guard exists for is subtle enough that its own falsification matters: six
# services look correct and are not the same kind of correct.
if [ "${1:-}" = "--self-test" ]; then
  set +e
  td=$(mktemp -d); trap 'rm -rf "$td"' EXIT
  fails=0

  put() { mkdir -p "$(dirname "$1")"; printf '%b' "$2" > "$1"; }
  expect() { # expect <label> <root> <want-rc> [substring]
    local label="$1" root="$2" want="$3" sub="${4:-}" out rc
    out=$(bash "$0" "$root" --enforce 2>&1); rc=$?
    if [ "$rc" -ne "$want" ]; then
      echo "::error::self-test: $label — expected rc=$want, got rc=$rc: $out" >&2; fails=$((fails+1))
    elif [ -n "$sub" ] && ! printf '%s' "$out" | grep -qF -- "$sub"; then
      echo "::error::self-test: $label — rc right, reason wrong (no '$sub'): $out" >&2; fails=$((fails+1))
    fi
  }
  y() { echo "openbank-$1/src/main/resources/application.yaml"; }

  # THE DEFECT: a dotted leaf under an incoming channel.
  a="$td/dotted"; put "$a/$(y x)" 'mp:\n  messaging:\n    incoming:\n      ch:\n        group.id: svc\n'
  expect "a dotted leaf under incoming is FLAGGED" "$a" 1 "leaf key contains a literal dot"

  # ...and outgoing, which is the same wire and the same silence.
  b="$td/outgoing"; put "$b/$(y x)" 'mp:\n  messaging:\n    outgoing:\n      ch:\n        key.serializer: k\n'
  expect "a dotted leaf under outgoing is FLAGGED" "$b" 1 "leaf key contains a literal dot"

  # The correct nested spelling must be clean, or the gate blocks the fix it demands.
  c="$td/nested"; put "$c/$(y x)" 'mp:\n  messaging:\n    incoming:\n      ch:\n        connector: smallrye-kafka\n        topic: t\n'
  expect "an undotted leaf is clean" "$c" 0 "no dotted mp.messaging leaf keys"

  # SCOPE: a dot in a key OUTSIDE mp.messaging is ordinary YAML and must not be reported —
  # `quarkus.http.port` style keys are everywhere and flagging them makes the gate unusable.
  d="$td/elsewhere"; put "$d/$(y x)" 'quarkus:\n  http:\n    port: 8080\nsome:\n  other.key: v\n'
  expect "a dotted key outside mp.messaging is ignored" "$d" 0 "no dotted mp.messaging leaf keys"

  # A dotted CHANNEL NAME (depth 4) is not a leaf property and is not this defect.
  e="$td/channel"; put "$e/$(y x)" 'mp:\n  messaging:\n    incoming:\n      my.channel:\n        topic: t\n'
  expect "a dotted channel name is not reported as a leaf" "$e" 0 "no dotted mp.messaging leaf keys"

  # Comments must not trip it — the fix comments in this fleet name the very keys involved.
  f="$td/comment"; put "$f/$(y x)" 'mp:\n  messaging:\n    incoming:\n      ch:\n        # group.id: never write it here\n        topic: t\n'
  expect "the key named in a comment is not a hit" "$f" 0 "no dotted mp.messaging leaf keys"

  # ADVISORY vs ENFORCE must actually differ, or `mode:` means nothing.
  out=$(bash "$0" "$a" 2>&1); rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "::error::self-test: advisory mode should exit 0, got $rc" >&2; fails=$((fails+1))
  elif ! printf '%s' "$out" | grep -qF "ADVISORY mode"; then
    echo "::error::self-test: advisory mode did not say so: $out" >&2; fails=$((fails+1))
  fi

  # EMPTY SCOPE is not a pass. This script reported one until the same commit that added this
  # self-test — its sibling over the same corpus had already been fixed (#4339).
  g="$td/empty"; mkdir -p "$g"
  expect "an empty scope FAILS rather than reporting clean" "$g" 1 "scope moved"

  if [ "$fails" -gt 0 ]; then echo "self-test FAILED ($fails case(s))" >&2; exit 1; fi
  echo "self-test ok: dotted mp.messaging key guard is falsifiable (8 cases)"
  exit 0
fi

ROOT="."
ENFORCE=0
for arg in "$@"; do
  case "$arg" in
    --enforce) ENFORCE=1 ;;
    *) ROOT="$arg" ;;
  esac
done

files="$(
  find "$ROOT" \
    \( -type d \( -name build -o -name node_modules -o -name .git -o -name .claude \) -prune \) -o \
    \( -path '*/src/main/resources/application.yaml' -print \)
)"

if [ -z "$files" ]; then
  # NOT "nothing to check". The corpus is every service's application.yaml — dozens of files —
  # so an empty list means the scope moved, not that the fleet is clean. Its sibling over the
  # same corpus (check-duplicate-yaml-keys.sh) already fails here for that reason (#4339);
  # this one still reported a pass, which is the same defect the two gates exist to catch.
  echo "::error::check-dotted-mp-messaging-keys: no service application.yaml found under" \
       "'$ROOT' — the scope moved, the gate did not."
  exit 1
fi

count_files=0
violations=""
for f in $files; do
  count_files=$((count_files + 1))
  out="$(awk '
    function get_indent(s,    i) {
      i = 0
      while (substr(s, i + 1, 1) == " ") i++
      return i
    }
    /^[[:space:]]*#/ { next }
    /^[[:space:]]*$/ { next }
    {
      line = $0
      rest = line
      sub(/^[[:space:]]*/, "", rest)
      colon = index(rest, ":")
      if (colon == 0) next
      after = substr(rest, colon + 1)
      if (after != "" && substr(after, 1, 1) != " ") next
      key = substr(rest, 1, colon - 1)
      if (substr(key, 1, 1) == "\"" && substr(key, length(key), 1) == "\"") {
        key = substr(key, 2, length(key) - 2)
      }
      indent = get_indent(line)
      while (depth > 0 && ind[depth] >= indent) depth--
      depth++
      ind[depth] = indent
      ky[depth] = key
      if (depth >= 5 && ky[1] == "mp" && ky[2] == "messaging" && (ky[3] == "incoming" || ky[3] == "outgoing")) {
        leaf = ky[depth]
        if (leaf ~ /\./) {
          printf "%s:%d: mp.messaging.%s.%s.%s (leaf key contains a literal dot)\n", FILENAME, FNR, ky[3], ky[4], leaf
        }
      }
    }
  ' "$f")"
  if [ -n "$out" ]; then
    violations="${violations}${out}
"
  fi
done

if [ -z "$violations" ]; then
  echo "check-dotted-mp-messaging-keys: $count_files application.yaml file(s) checked, no dotted mp.messaging leaf keys."
  exit 0
fi

count="$(printf '%s\n' "$violations" | grep -c . || true)"
level="warning"; [ "$ENFORCE" -eq 1 ] && level="error"
echo "::${level}::Found $count dotted mp.messaging leaf key(s) — silently dropped by the YAML config flattener."
echo "A literal dot in an mp.messaging.(incoming|outgoing).<channel> leaf key (e.g. group.id,"
echo "auto.offset.reset, dead-letter-queue.topic) registers as a QUOTED MicroProfile Config"
echo "property name that KafkaConnectorIncomingConfiguration's plain getters never read — the"
echo "value silently falls back to a default instead. See open-bank-oss#686 for the group.id ->"
echo "GroupAuthorizationException incident chain and the fix pattern (move the value to an"
echo "MP_MESSAGING_INCOMING_<CHANNEL>_<KEY> env var in the service's gitops manifest). Findings:"
echo ""
printf '%s\n' "$violations"

if [ "$ENFORCE" -eq 1 ]; then
  exit 1
fi
echo ""
echo "check-dotted-mp-messaging-keys: ADVISORY mode — not failing the build. Add --enforce"
echo "in .github/workflows/ci.yml once the fleet is confirmed clean to make this a hard gate."
exit 0
