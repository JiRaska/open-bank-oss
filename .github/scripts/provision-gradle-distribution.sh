#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Install the Gradle distribution pinned in gradle/wrapper/gradle-wrapper.properties into
# $GRADLE_USER_HOME/wrapper/dists, retrying transient download failures (services.gradle.org
# answered HTTP 504 on 2026-10-01). Tests that run fixture builds then reuse it and never
# download on their own (test_dependency_resolution_guard.py fails if it is absent).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
for attempt in 1 2 3 4; do
  if ./gradlew --version --no-daemon >/dev/null; then
    exit 0
  fi
  echo "::warning::Gradle distribution download failed (attempt ${attempt}/4)"
  sleep $((attempt * 10))
done
echo "::error::could not provision the Gradle wrapper distribution after 4 attempts"
exit 1
