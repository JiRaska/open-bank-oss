#!/usr/bin/env bash
# Validate constrained targeted reconcile dispatch inputs before service selection.
set -euo pipefail

if [ -z "${INPUT_RECONCILE_SERVICES:-}" ]; then
  exit 0
fi
if [ "${INPUT_RECONCILE:-}" != "true" ]; then
  echo "::error::reconcile_services requires reconcile=true"
  exit 1
fi
if [ "${INPUT_CODEPLOY:-false}" = "true" ] || [ -n "${INPUT_CODEPLOY_PACT_VERSIONS:-}" ]; then
  echo "::error::targeted reconcile cannot use co-deploy or Pact replay inputs"
  exit 1
fi
if [ "${INPUT_BOOTSTRAP_IMAGE_ONLY:-}" = "true" ]; then
  echo "::error::targeted reconcile cannot be combined with bootstrap_image_only"
  exit 1
fi
