// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.case

/** Relative urgency assigned to a case. */
enum class CasePriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL,
}
