// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.close

/**
 * What a day-close participant reports to the ledger for one accounting day (ADR-0342 D1).
 * `WAIVED` is deliberately absent: only an operator, on the ledger side, can waive a participant.
 */
enum class CloseOutcome {
    RAN,
    SKIPPED,
    REFUSED,
}
