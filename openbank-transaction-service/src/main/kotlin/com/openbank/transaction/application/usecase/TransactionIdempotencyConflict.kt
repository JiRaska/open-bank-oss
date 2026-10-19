// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.usecase

import io.vertx.pgclient.PgException
import org.hibernate.exception.ConstraintViolationException
import java.sql.SQLException

/** Only a global key collision may become a replay; other persistence failures must propagate. */
internal object TransactionIdempotencyConflict {
    const val CONSTRAINT = "pk_transaction_idempotency_keys"

    fun isConflict(error: Throwable): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        while (cause != null && seen.add(cause)) {
            val conflict = when (cause) {
                is ConstraintViolationException ->
                    cause.sqlException.sqlState == "23505" &&
                        cause.constraintName == CONSTRAINT

                is PgException -> cause.sqlState == "23505" && cause.constraint == CONSTRAINT

                // Hibernate Reactive wraps Vert.x errors as plain SQLExceptions. Match the exact
                // quoted constraint token, never a field name or an arbitrary uniqueness error.
                is SQLException ->
                    cause.sqlState == "23505" &&
                        cause.message?.contains("\"$CONSTRAINT\"") == true

                else -> false
            }
            if (conflict) return true
            cause = cause.cause
        }
        return false
    }
}
