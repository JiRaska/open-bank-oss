// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clients.transaction

/**
 * What a call to transaction-service PROVED, as classified by [TransactionClient].
 *
 * Anything not representable here (403, 5xx, 3xx, a call that failed without any status) is not an
 * outcome: it is thrown, because a caller that cannot tell what happened must retry, never decide.
 */
sealed interface TransactionCallOutcome<out T> {

    /**
     * A 2xx status was PROVEN. The booking happened whether or not its body can be read:
     * [body] is null when there is none (204) or it could not be read, and then [bodyError] says why
     * (null for a genuine empty body). A caller must treat this as booked and must not retry it.
     */
    data class Booked<T>(val status: Int, val body: T?, val bodyError: Throwable?) : TransactionCallOutcome<T>

    /** 409: a concurrent-state conflict. Whether it proves an earlier identical booking is the caller's call. */
    data object Conflict : TransactionCallOutcome<Nothing>

    /** 400 or 422: transaction-service refused the request itself; retrying the same bytes cannot help. */
    data class Rejected(val status: Int, val problem: String?) : TransactionCallOutcome<Nothing>
}
