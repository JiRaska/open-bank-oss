// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.persistence

import io.vertx.pgclient.PgException

/**
 * Recognises the one database error that means "this notification fact is already recorded".
 *
 * PostgreSQL error classification lives at the persistence boundary rather than in the consumer,
 * the same way product-catalog's `PostgresConflicts` does, so it can be tested without booting a
 * database — which is the whole reason the previous version's defect survived: it lived inside a
 * private method of an application class and only an integration test could reach it.
 */
object NotificationDeduplication {
    /** The partial unique index on `notifications(deduplication_key)`; see V15. */
    const val CONSTRAINT = "uq_notifications_deduplication_key"

    /** PostgreSQL `unique_violation`. */
    const val UNIQUE_VIOLATION = "23505"

    /**
     * True iff [error]'s cause chain carries the deduplication index violation.
     *
     * Matched on `io.vertx.pgclient.PgException`, not `org.postgresql.util.PSQLException`. This
     * service persists through REACTIVE Panache, whose driver is the Vert.x PG client; the JDBC
     * driver's exception class never appears in that chain, so a check written against it can
     * never match. The duplicate was then re-thrown instead of skipped, the message nacked, and
     * redelivery retried it forever — the opposite of what a deduplication guard is for.
     *
     * Deliberately narrow: it is not enough that SOME unique index was violated. Only this index
     * means "already recorded"; any other 23505 is a genuine fault and must keep failing loudly
     * rather than being silently acked as a duplicate.
     *
     * PgException exposes no constraint accessor, so the name is matched in the fields Postgres
     * puts it in — party-service's PgUniqueConstraintMapper reads `detail` the same way.
     */
    fun isConflict(error: Throwable?): Boolean = generateSequence(error) { it.cause }
        .filterIsInstance<PgException>()
        .any { pg ->
            pg.sqlState == UNIQUE_VIOLATION &&
                sequenceOf(pg.errorMessage, pg.detail, pg.message)
                    .any { it?.contains(CONSTRAINT) == true }
        }
}
