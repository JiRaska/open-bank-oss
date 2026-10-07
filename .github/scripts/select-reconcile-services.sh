#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
# Validate a manual reconcile's requested subset against auto-deploy's buildable fleet.
set -euo pipefail
set -f

known="${1:?known services required}"
requested="${2:?requested services required}"
selected=()
for service in $requested; do
  if [[ ! "$service" =~ ^openbank-[a-z0-9-]+$ ]] \
    || [[ " $known " != *" $service "* ]]; then
    echo "::error::reconcile target is not a known buildable service: $service" >&2
    exit 1
  fi
  if [[ " ${selected[*]:-} " != *" $service "* ]]; then
    selected+=("$service")
  fi
done

if [ "${#selected[@]}" -eq 0 ]; then
  echo '::error::reconcile targets are empty' >&2
  exit 1
fi
printf '%s\n' "${selected[*]}"
