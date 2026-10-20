// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.close

/** Outcomes a service participant may report for one accounting day. Operator waiver is separate. */
enum class CloseOutcome {
    RAN,
    SKIPPED,
    REFUSED,
}
