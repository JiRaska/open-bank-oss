#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# ADR-0363 remote Gradle build cache: read everywhere, write only from main's own code (a push,
# or a workflow_dispatch seeding run; never a historical inputs.ref) with the gradle-cache-writer
# environment secret present. A blank URL leaves the cache off (fail open).
set -o pipefail
u=ci-read p=${R:-} w=false
case "${GITHUB_EVENT_NAME:-}" in push|workflow_dispatch) e=1 ;; *) e= ;; esac
if [ -n "$e" ] && [ "${GITHUB_REF:-}" = refs/heads/main ] && [ -z "${H:-}" ] && [ -n "${W:-}" ]; then
  u=ci-write p=$W w=true
fi
if [ -n "$p" ]; then echo "::add-mask::$p"; fi
printf 'GRADLE_REMOTE_CACHE_%s\n' "URL=${U:-}" "USER=$u" "PASSWORD=$p" "PUSH=$w" >> "$GITHUB_ENV"
