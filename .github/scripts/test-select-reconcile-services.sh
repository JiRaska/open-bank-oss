#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
set -euo pipefail

selector="$(dirname "$0")/select-reconcile-services.sh"
known='openbank-notification-service openbank-copilot-service openbank-customer-edge'
actual="$(bash "$selector" "$known" 'openbank-notification-service openbank-copilot-service openbank-notification-service')"
[ "$actual" = 'openbank-notification-service openbank-copilot-service' ]

for bad in 'openbank-ledger-service' 'openbank-notification-service;false' 'openbank-notification-service/../openbank-copilot-service'; do
  if bash "$selector" "$known" "$bad" >/dev/null 2>&1; then
    echo "FAIL: invalid reconcile target accepted: $bad" >&2
    exit 1
  fi
done
echo 'PASS: manual reconcile selects only known requested services'
