// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

/** PostgreSQL error classification stays at the persistence boundary, never in application code. */
object PostgresConflicts {
    fun isUniqueViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            val unique = when {
                (current as? java.sql.SQLException)?.sqlState == UNIQUE_VIOLATION -> true
                (current as? org.hibernate.exception.ConstraintViolationException)
                    ?.sqlException?.sqlState == UNIQUE_VIOLATION -> true
                (current as? io.vertx.pgclient.PgException)?.sqlState == UNIQUE_VIOLATION -> true
                SQLSTATE_23505.containsMatchIn(message) -> true
                else -> false
            }
            if (unique) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private const val UNIQUE_VIOLATION = "23505"
    private val SQLSTATE_23505 = Regex("(?i)sqlstate(?:\\s*[:=]\\s*|\\s+)[(]?$UNIQUE_VIOLATION[)]?")
}
