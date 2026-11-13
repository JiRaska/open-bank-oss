#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Moved verbatim out of _service-ci.yml, which is expanded once per Services CI caller
# under GitHub's expanded-workflow size cap. pipefail keeps the inline-step semantics.
set -o pipefail
cur=$(cat /proc/sys/fs/aio-max-nr 2>/dev/null || echo 0)
if [ "${cur:-0}" -lt 1048576 ]; then
  if sudo -n sysctl -w fs.aio-max-nr=1048576 >/dev/null 2>&1; then
    echo "raised fs.aio-max-nr: ${cur} -> 1048576"
  else
    echo "::warning::could not raise fs.aio-max-nr (currently ${cur}); parallel Testcontainers may exhaust the AIO pool on this runner (no passwordless sudo?)"
  fi
else
  echo "fs.aio-max-nr already ${cur} (>= 1048576)"
fi
