// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.persistence

/** Shared Postgres-exception classification for code that must tell a lost race from a real fault. */
object PostgresConflicts {

    /** True if [e] (or any cause) is a Postgres unique-violation (SQLState 23505) — a lost race. */
    fun isUniqueViolation(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            val msg = cur.message.orEmpty()
            val byMessage = "23505" in msg || "duplicate key value" in msg
            if ((cur as? java.sql.SQLException)?.sqlState == "23505" ||
                cur is org.hibernate.exception.ConstraintViolationException ||
                byMessage
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }
}
