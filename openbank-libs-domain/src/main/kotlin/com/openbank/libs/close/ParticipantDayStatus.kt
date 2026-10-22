// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.close

/** Read-side day-close status. WAIVED is operator-only; MISSING means no report exists. */
enum class ParticipantDayStatus {
    RAN,
    SKIPPED,
    REFUSED,
    WAIVED,
    MISSING,
}
