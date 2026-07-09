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
  echo "check-dotted-mp-messaging-keys: no service application.yaml found under '$ROOT' — nothing to check."
  exit 0
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
