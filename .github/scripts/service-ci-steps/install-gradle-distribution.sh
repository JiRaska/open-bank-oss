#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
# Install the lock-pinned Gradle wrapper distribution once, with bounded retry, so no later
# ./gradlew in this job downloads it from upstream (runs 42461736478, 42570587622).
# The checkout here is main or a provider ref proven to be on main. A ref older than the
# installer keeps the wrapper's own download, exactly as before.
installer=.github/scripts/ensure-gradle-distribution.py
if [ ! -f "$installer" ] || [ ! -f .github/scripts/fetch-artifact.py ]; then
  echo "::notice::$installer is not in this checkout; the wrapper downloads its own distribution"
  exit 0
fi
python3 -I "$installer"
