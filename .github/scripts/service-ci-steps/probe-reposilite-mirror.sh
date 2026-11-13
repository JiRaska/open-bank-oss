#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Moved verbatim out of _service-ci.yml, which is expanded once per Services CI caller
# under GitHub's expanded-workflow size cap. pipefail keeps the inline-step semantics.
set -o pipefail
if curl -sf --connect-timeout 2 \
    http://reposilite.reposilite.svc.cluster.local:8080/api/status \
    > /dev/null 2>&1; then
  echo "REPOSILITE_MIRROR_URL=http://reposilite.reposilite.svc.cluster.local:8080" \
    >> "$GITHUB_ENV"
  echo "::notice title=Reposilite active::in-cluster Maven mirror responding — deps resolved from cluster (zero NAT)"
else
  echo "::notice title=Reposilite unavailable::falling back to GCS mirror + CodeArtifact"
fi
