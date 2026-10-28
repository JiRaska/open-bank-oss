// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clients.transaction

/**
 * What a call to transaction-service PROVED, as classified by [TransactionClient].
 *
 * Anything not representable here (401, 403, 404, 3xx, 5xx, and a call that failed without any
 * status) is not an outcome: it is thrown, because a caller that cannot tell what happened must
 * retry, never decide.
 */
sealed interface TransactionCallOutcome<out T> {

    /**
     * HTTP 2xx, with an optional decoded body or its read error. This generic result alone does
     * NOT prove a booking: [TransactionClient.initiate] and [TransactionClient.reverse] validate
     * readable COMPLETED status before exposing it. [TransactionClient.list] is a read and does
     * not apply that write-completion rule; page entries may have any transaction status.
     */
    data class HttpSuccess<T>(val status: Int, val body: T?, val bodyError: Exception?) : TransactionCallOutcome<T>

    /** 409: a concurrent-state conflict. Whether it proves an earlier identical booking is the caller's call. */
    data object Conflict : TransactionCallOutcome<Nothing>

    /**
     * 400 or 422: transaction-service refused the request itself; retrying the same bytes cannot help.
     * [problem] is the (bounded) response text when it could be read, else null with [problemError] saying why.
     */
    data class Rejected(val status: Int, val problem: String?, val problemError: Exception? = null) :
        TransactionCallOutcome<Nothing>
}
