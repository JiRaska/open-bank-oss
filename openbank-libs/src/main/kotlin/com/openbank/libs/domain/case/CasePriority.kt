// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.domain.case

/** Relative urgency assigned to a case. */
enum class CasePriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL,
}
