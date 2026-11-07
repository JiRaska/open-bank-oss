// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.port.out

import com.openbank.libs.close.CloseJobRun
import io.smallrye.mutiny.Uni
import java.time.LocalDate

/** Evidence that a scheduled job ran for an accounting day (ADR-0342 D1). */
interface CloseJobRunPort {
    /** Upsert on `(businessDate, job)`: a re-run for the same day replaces the counts. */
    fun record(run: CloseJobRun): Uni<Unit>

    /** Every run with `businessDate` in `[from, to]`. */
    fun findBetween(from: LocalDate, to: LocalDate): Uni<List<CloseJobRun>>
}
