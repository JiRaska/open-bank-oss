#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Moved verbatim out of _service-ci.yml, which is expanded once per Services CI caller
# under GitHub's expanded-workflow size cap. pipefail keeps the inline-step semantics.
set -o pipefail
set -euo pipefail
mkdir -p "${GRADLE_USER_HOME}/init.d"
tmp="$(mktemp "${GRADLE_USER_HOME}/init.d/.cache-retention.XXXXXX")"
cat > "$tmp" <<'GRADLE_INIT'
beforeSettings { settings ->
    settings.caches {
        releasedWrappers.removeUnusedEntriesAfterDays = 1
        snapshotWrappers.removeUnusedEntriesAfterDays = 1
    }
}
GRADLE_INIT
mv -f "$tmp" "${GRADLE_USER_HOME}/init.d/cache-retention.gradle"
