// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.persistence

import io.vertx.pgclient.PgException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The predicate this pins is the whole deduplication guard: get it wrong in the permissive
 * direction and unrelated faults are silently acked as duplicates; get it wrong in the strict
 * direction — which is what shipped — and duplicates are nacked and redelivered forever.
 *
 * The defect that made this file necessary is the first case below: the guard was written against
 * the JDBC driver's exception while the service persists reactively, so it could never fire. No
 * unit test could have caught it, because there was none.
 */
class NotificationDeduplicationTest {
    private fun pg(code: String, message: String, detail: String? = null) = PgException(message, "ERROR", code, detail)

    @Test
    fun `the deduplication index violation is recognised`() {
        assertThat(
            NotificationDeduplication.isConflict(
                pg(
                    "23505",
                    "duplicate key value violates unique constraint \"${NotificationDeduplication.CONSTRAINT}\"",
                ),
            ),
        ).isTrue()
    }

    @Test
    fun `it is recognised through a wrapping cause chain`() {
        val wrapped = RuntimeException(
            "persist failed",
            pg("23505", "duplicate key value violates unique constraint \"${NotificationDeduplication.CONSTRAINT}\""),
        )
        assertThat(NotificationDeduplication.isConflict(wrapped)).isTrue()
    }

    @Test
    fun `the constraint name is also read from detail`() {
        assertThat(
            NotificationDeduplication.isConflict(
                pg(
                    "23505",
                    "duplicate key value",
                    "Key (deduplication_key)=(x) violates ${NotificationDeduplication.CONSTRAINT}",
                ),
            ),
        ).isTrue()
    }

    @Test
    fun `a different unique index is NOT a duplicate fact`() {
        // Must keep failing loudly. Acking someone else's constraint violation as "already
        // recorded" would drop a notification and leave no trace of why.
        assertThat(
            NotificationDeduplication.isConflict(
                pg("23505", "duplicate key value violates unique constraint \"uq_notifications_reference\""),
            ),
        ).isFalse()
    }

    @Test
    fun `a non-unique-violation sqlstate is not a duplicate`() {
        assertThat(
            NotificationDeduplication.isConflict(
                pg("23503", "insert violates foreign key constraint \"${NotificationDeduplication.CONSTRAINT}\""),
            ),
        ).isFalse()
    }

    @Test
    fun `a JDBC-shaped failure is not matched — this service persists reactively`() {
        // The original defect, pinned: the guard matched org.postgresql.util.PSQLException, which
        // never appears in a reactive Panache failure chain.
        val jdbcShaped = java.sql.SQLException(
            "duplicate key value violates unique constraint \"${NotificationDeduplication.CONSTRAINT}\"",
            "23505",
        )
        assertThat(NotificationDeduplication.isConflict(jdbcShaped)).isFalse()
    }

    @Test
    fun `null and unrelated failures are not duplicates`() {
        assertThat(NotificationDeduplication.isConflict(null)).isFalse()
        assertThat(NotificationDeduplication.isConflict(IllegalStateException("boom"))).isFalse()
    }
}
