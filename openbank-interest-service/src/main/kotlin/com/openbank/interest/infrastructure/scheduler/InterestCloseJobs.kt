// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.scheduler

/** Job names recorded in `interest_close_job_run` (ADR-0342 D1). Stable: they are persisted. */
object InterestCloseJobs {
    const val ACCRUAL = "accrual"
    const val CAPITALIZATION = "capitalization"

    /** The participant name interest-service reports under; must match the ledger's declaration. */
    const val PARTICIPANT = "interest"
}
