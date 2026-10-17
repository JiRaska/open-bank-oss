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
     * A 2xx status was PROVEN. The booking happened whether or not its body can be read - a caller
     * must treat this as booked and must not retry it.
     *
     * - [body] is the parsed response when it could be read;
     * - a 204 and an empty 200/201 are `Booked(status, body = null, bodyError = null)`: booked, and
     *   there is no id to be had;
     * - an unreadable body is `Booked(status, body = null, bodyError = <why>)`;
     * - a `WebApplicationException` that carries a 2xx status (a client that failed while reading the
     *   answer) is also `Booked(status, null, bodyError = <that exception>)`.
     */
    data class Booked<T>(val status: Int, val body: T?, val bodyError: Exception?) : TransactionCallOutcome<T>

    /** 409: a concurrent-state conflict. Whether it proves an earlier identical booking is the caller's call. */
    data object Conflict : TransactionCallOutcome<Nothing>

    /**
     * 400 or 422: transaction-service refused the request itself; retrying the same bytes cannot help.
     * [problem] is the (bounded) response text when it could be read, else null with [problemError] saying why.
     */
    data class Rejected(val status: Int, val problem: String?, val problemError: Exception? = null) :
        TransactionCallOutcome<Nothing>
}
