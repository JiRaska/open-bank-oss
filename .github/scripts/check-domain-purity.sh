#!/usr/bin/env bash
# Domain-purity gate (ADR-0002 / rules.yaml architecture.domain_zero_framework_imports).
#
# The hexagonal invariant "the domain layer has zero framework imports" was documented
# and repeated in every PR template, but nothing enforced it — and the fleet had
# quietly accumulated violations (Quarkus @RegisterForReflection on a domain type,
# Jackson JsonNode in domain models). This gate greps every
# openbank-*/src/main/kotlin/**/domain/**/*.kt for framework import prefixes.
#
# BASELINE-RATCHETED (the detekt-baseline pattern): pre-existing violations are
# listed in domain-purity-baseline.txt next to this script and do not fail the
# build; any NEW violation does. Burn the baseline down as files are touched —
# never add to it (adding an entry needs the PR to justify why the domain type
# cannot stay framework-free).
#
# Module exemption: openbank-libs-runtime shares the com.openbank.libs.domain.*
# PACKAGE name for its JPA attribute converters, but it IS the framework side of
# the split (ADR-0122) — the layering rule is about the module, not the package
# string, so libs-runtime is excluded from the scan.
#
# Usage: check-domain-purity.sh [repo-root]
set -euo pipefail

root="${1:-.}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
baseline="${script_dir}/domain-purity-baseline.txt"

# Framework/library prefixes that must never appear in a domain-layer import.
# java.*/kotlin.*/kotlinx.coroutines are fine; serialization, DI, persistence,
# transport and runtime frameworks are not.
prefixes='jakarta\.|javax\.|io\.quarkus|org\.eclipse\.microprofile|org\.hibernate|io\.smallrye|org\.jboss|com\.fasterxml\.jackson|org\.apache\.kafka|io\.vertx|org\.flywaydb|org\.springframework|io\.micrometer|io\.opentelemetry'

violations=()
while IFS= read -r line; do
  violations+=("$line")
done < <(
  find "$root"/openbank-*/src/main/kotlin -path "*/domain/*" -name "*.kt" 2>/dev/null \
    | grep -v "^$root/openbank-libs-runtime/" \
    | sort \
    | xargs grep -H -E "^import ($prefixes)" 2>/dev/null \
    | sed "s|^$root/||" \
    || true
)

fail=0
new_count=0
for v in "${violations[@]:-}"; do
  [ -z "$v" ] && continue
  if [ -f "$baseline" ] && grep -qxF "$v" "$baseline"; then
    continue  # grandfathered — burn down as touched
  fi
  echo "::error::domain-purity: NEW framework import in the domain layer (ADR-0002): $v"
  fail=1
  new_count=$((new_count + 1))
done

# Ratchet integrity: a baseline entry whose violation no longer exists is stale —
# remove it so the fixed file can never regress silently.
stale=0
if [ -f "$baseline" ]; then
  while IFS= read -r b; do
    case "$b" in ''|'#'*) continue ;; esac
    found=0
    for v in "${violations[@]:-}"; do
      [ "$v" = "$b" ] && found=1 && break
    done
    if [ "$found" -eq 0 ]; then
      echo "::error::domain-purity: baseline entry no longer occurs — delete it from domain-purity-baseline.txt (ratchet-only): $b"
      stale=1
    fi
  done < "$baseline"
fi

total=${#violations[@]}
[ -n "${violations[0]:-}" ] || total=0
echo "check-domain-purity: ${total} violation(s) found, ${new_count} new, baseline $( [ -f "$baseline" ] && grep -cv '^\s*\(#\|$\)' "$baseline" || echo 0 ) entr(y/ies)."
exit $(( fail || stale ))
