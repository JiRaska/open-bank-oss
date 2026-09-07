// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.persistence

import java.sql.SQLException

/**
 * Recognises the one database error that means "this notification fact is already recorded".
 *
 * Behaviour is unchanged from #8953, which fixed it; this only moves the predicate out of a private
 * method of [com.openbank.notification.application.NotificationConsumer] so a unit test can reach
 * it. That unreachability is not a style point — it is why the original defect survived review. The
 * check matched the pgjdbc `PSQLException`, which never appears here, so it could never fire; the
 * only test that could have caught it was an integration test, and that test was passing vacuously
 * because a duplicate V14 migration meant the unique index was never created.
 *
 * Hibernate Reactive adapts the Vert.x `PgException` into a plain [java.sql.SQLException]
 * (sqlState 23505, the server message naming the constraint) beneath its
 * `ConstraintViolationException`. Matching any other exception class here — pgjdbc's or the Vert.x
 * client's own — reintroduces the original defect in a new disguise.
 *
 * Narrow on purpose: it is not enough that SOME unique index was violated. Only this index means
 * "already recorded"; any other 23505 is a genuine fault and must keep failing loudly rather than
 * being silently acked as a duplicate, which would drop a notification without trace.
 */
object NotificationDeduplication {
    /** The partial unique index on `notifications(deduplication_key)`. */
    const val CONSTRAINT = "uq_notifications_deduplication_key"

    /** PostgreSQL `unique_violation`, as surfaced by the reactive stack. */
    const val SQLSTATE_UNIQUE_VIOLATION = "23505"

    /** True iff [error]'s cause chain carries the deduplication index violation. */
    fun isConflict(error: Throwable?): Boolean = generateSequence(error) { it.cause }
        .filterIsInstance<SQLException>()
        .any {
            it.sqlState == SQLSTATE_UNIQUE_VIOLATION &&
                it.message?.contains(CONSTRAINT) == true
        }
}
