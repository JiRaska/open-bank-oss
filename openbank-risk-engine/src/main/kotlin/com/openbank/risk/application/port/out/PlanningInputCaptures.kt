// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.port.out

import java.util.UUID

/** Records the exact inputs chosen for a draft. */
interface PlanningInputCaptures {
    suspend fun capture(snapshot: UUID, curves: UUID, treasuryRevisions: List<Long>): UUID
}
