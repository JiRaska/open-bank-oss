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

    /**
     * The zone every interest cron is evaluated in. Quarkus fires a cron in the JVM default zone
     * unless `timeZone` says otherwise, while the close reporter decides which days a cron fires on
     * with the ledger's accounting zone (`AccountingClock.BANK_ZONE`, Europe/Prague). Pinning the
     * `@Scheduled` triggers to the same zone keeps the two from disagreeing about the day (a pod
     * running in UTC would fire "01:00" an hour or two away from the Prague 01:00 the reporter
     * assumes). A test asserts this equals the accounting zone and is on every annotation.
     */
    const val SCHEDULE_ZONE = "Europe/Prague"
}
