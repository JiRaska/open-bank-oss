#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Moved verbatim out of _service-ci.yml, which is expanded once per Services CI caller
# under GitHub's expanded-workflow size cap. pipefail keeps the inline-step semantics.
set -o pipefail
if [ -z "${DOCKER_HOST:-}" ] || [ "${DOCKER_HOST:-}" = "tcp://localhost:2375" ]; then
  echo "DOCKER_HOST=unix:///var/run/docker.sock" >> "$GITHUB_ENV"
  echo "normalized DOCKER_HOST -> unix:///var/run/docker.sock"
else
  echo "DOCKER_HOST left as: ${DOCKER_HOST}"
fi
