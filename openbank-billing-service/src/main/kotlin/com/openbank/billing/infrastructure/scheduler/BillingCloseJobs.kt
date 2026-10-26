// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.scheduler

import com.openbank.libs.close.AccountingDayClosed
import jakarta.ws.rs.WebApplicationException

/** Names and fixed categories of billing's day-close evidence (ADR-0342 D1). Stable: persisted. */
object BillingCloseJobs {
    /** The one job billing reports on: the monthly cycle sweep. */
    const val CYCLE = "billing-cycle"

    /** The participant name billing-service reports under; must match the ledger's declaration. */
    const val PARTICIPANT = "billing"

    /**
     * The zone the cycle cron fires in and the reporter evaluates it in — the ledger's accounting
     * zone (`AccountingClock.BANK_ZONE`). Pinned on the `@Scheduled` trigger so a UTC pod cannot fire
     * "03:00 on the 1st" on a different accounting day from the one the reporter expects.
     */
    const val SCHEDULE_ZONE = "Europe/Prague"

    /**
     * Fixed refusal categories. A reason is stored in the ledger's participation history and shown on
     * the day-end page, so it carries a category and a count — never an account id, a fee id or an
     * exception message.
     */
    const val REASON_ACCOUNTS_FAILED = "account(s) failed to assess"
    const val REASON_SWEEP_ABORTED = "cycle sweep aborted before completing (account discovery failed)"
    const val REASON_FEES_NOT_POSTED = "fee posting(s) not booked (ledger refused the day or retries exhausted)"

    private const val HTTP_CONFLICT = 409

    /**
     * True when the ledger's closed-day 409 (ADR-0342 D4) is anywhere in [failure]'s cause chain.
     * The REST client surfaces it as a [WebApplicationException] carrying the ledger's response; the
     * fault-tolerance annotations rethrow the last attempt's exception unchanged.
     */
    fun isClosedDayRejection(failure: Throwable): Boolean = generateSequence(failure) { it.cause }
        .filterIsInstance<WebApplicationException>()
        .any { e ->
            val status = e.response?.status ?: return@any false
            status == HTTP_CONFLICT &&
                (
                    AccountingDayClosed.isClosedDayRejection(status, bodyOf(e)) ||
                        AccountingDayClosed.isClosedDayRejection(status, e.message)
                    )
        }

    private fun bodyOf(e: WebApplicationException): String? = runCatching {
        e.response.readEntity(String::class.java)
    }.getOrNull()
}
