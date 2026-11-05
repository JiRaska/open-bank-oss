#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# ADR-0363 remote Gradle build cache: read everywhere, write only on a push to main with the
# gradle-cache-writer environment secret present. A blank URL leaves the cache off (fail open).
set -o pipefail
u=ci-read p=${R:-} w=false
if [ "${GITHUB_EVENT_NAME}${GITHUB_REF}${H:-}" = pushrefs/heads/main ] && [ -n "${W:-}" ]; then
  u=ci-write p=$W w=true
fi
if [ -n "$p" ]; then echo "::add-mask::$p"; fi
printf 'GRADLE_REMOTE_CACHE_%s\n' "URL=${U:-}" "USER=$u" "PASSWORD=$p" "PUSH=$w" >> "$GITHUB_ENV"
