#!/usr/bin/env bash
# Guard: openbank-libs-domain must stay framework-free (ADR-0122, issue #3670).
#
# The domain module is the pure half of the domain/runtime split: ports, value
# types and algorithms only. Framework imports crept in once already — the
# @Authorize/@FeatureFlag CDI interceptor bindings (jakarta.interceptor) and a
# JBoss logging facade in OutboxDispatch lived here while ADR-0122 and
# AGENTS.md claimed "zero framework imports". Anything needing CDI, HTTP or a
# logging facade belongs in openbank-libs-runtime under the SAME package name,
# so consumers never see the move.
#
# Two detection axes, both anchored to code (not kdoc prose, which legitimately
# references Quarkus extensions such as quarkus-oidc-client-reactive-filter):
#   1. import lines for framework packages
#   2. fully-qualified annotation use (e.g. @jakarta.interceptor.InterceptorBinding,
#      @get:jakarta.enterprise.util.Nonbinding)
#
# stdlib-only (grep/find). ENFORCED.
# Usage: check-libs-domain-purity.sh [root]   (default root: .)
set -euo pipefail
root="${1:-.}"
dir="$root/openbank-libs-domain/src/main/kotlin"
fail=0
checked=0

if [ ! -d "$dir" ]; then
  echo "check-libs-domain-purity: $dir not found — module layout changed; update this guard."
  exit 1
fi

while IFS= read -r f; do
  checked=$((checked + 1))
  hits=$(grep -nE '^[[:space:]]*import[[:space:]]+(jakarta|io\.quarkus|org\.jboss|io\.smallrye|org\.eclipse\.microprofile|io\.micrometer|software\.amazon)\.' "$f" || true)
  if [ -n "$hits" ]; then
    fail=1
    while IFS= read -r hit; do
      lineno="${hit%%:*}"
      echo "::error file=$f,line=$lineno::framework import in openbank-libs-domain — ADR-0122 keeps this module framework-free; move the class to openbank-libs-runtime under the same package (issue #3670)."
    done <<< "$hits"
  fi
  fqhits=$(grep -nE '@[[:alnum:]:]*jakarta\.' "$f" | grep -vE '^[0-9]+:[[:space:]]*\*' || true)
  if [ -n "$fqhits" ]; then
    fail=1
    while IFS= read -r hit; do
      lineno="${hit%%:*}"
      echo "::error file=$f,line=$lineno::fully-qualified jakarta annotation in openbank-libs-domain — move the annotation class to openbank-libs-runtime (issue #3670)."
    done <<< "$fqhits"
  fi
done < <(find "$dir" -name '*.kt' | sort)

echo "check-libs-domain-purity: $checked domain source file(s) checked, $( [ "$fail" -eq 0 ] && echo "all framework-free." || echo "VIOLATIONS above." )"
exit "$fail"
