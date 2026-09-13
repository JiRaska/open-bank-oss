// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * Pins the deduplication predicate, which has no other unit-level cover.
 *
 * Both directions matter and neither is theoretical. Too permissive and an unrelated fault is acked
 * as a duplicate, dropping a notification with no trace. Too strict — what #8334 shipped — and every
 * duplicate is nacked and redelivered forever.
 *
 * **What these tests do NOT prove.** They construct the exception, so they pin the type the code
 * expects rather than the type Hibernate Reactive actually produces. That is exactly the gap that
 * let the original defect through: a unit test written against `PSQLException` would have passed
 * just as confidently. The type below is the one #8953 established against the running stack, and
 * `NotificationConsumerIT` — which drives a real database — remains the check that keeps it honest.
 * These tests protect the surrounding logic, not the premise.
 */
class NotificationDeduplicationTest {
    private fun sqlException(state: String, message: String) = SQLException(message, state)

    private fun dedupViolation() = sqlException(
        NotificationDeduplication.SQLSTATE_UNIQUE_VIOLATION,
        "duplicate key value violates unique constraint \"${NotificationDeduplication.CONSTRAINT}\"",
    )

    @Test
    fun `the deduplication index violation is recognised`() {
        assertThat(NotificationDeduplication.isConflict(dedupViolation())).isTrue()
    }

    @Test
    fun `it is recognised through a wrapping cause chain`() {
        // Hibernate wraps the driver's exception; the predicate walks causes for that reason.
        val wrapped = RuntimeException("could not execute statement", dedupViolation())
        assertThat(NotificationDeduplication.isConflict(wrapped)).isTrue()
    }

    @Test
    fun `a different unique index is NOT a duplicate fact`() {
        // Must keep failing loudly. Acking someone else's constraint violation as "already
        // recorded" drops a notification and leaves nothing to explain why.
        assertThat(
            NotificationDeduplication.isConflict(
                sqlException(
                    NotificationDeduplication.SQLSTATE_UNIQUE_VIOLATION,
                    "duplicate key value violates unique constraint \"uq_notifications_reference\"",
                ),
            ),
        ).isFalse()
    }

    @Test
    fun `another sqlstate naming the same constraint is not a duplicate`() {
        // 23503 is a foreign-key violation. The constraint name alone must not be enough.
        assertThat(
            NotificationDeduplication.isConflict(
                sqlException(
                    "23503",
                    "insert violates foreign key constraint \"${NotificationDeduplication.CONSTRAINT}\"",
                ),
            ),
        ).isFalse()
    }

    @Test
    fun `a unique violation with no message is not a duplicate`() {
        assertThat(
            NotificationDeduplication.isConflict(
                SQLException(null, NotificationDeduplication.SQLSTATE_UNIQUE_VIOLATION),
            ),
        ).isFalse()
    }

    @Test
    fun `null and unrelated failures are not duplicates`() {
        assertThat(NotificationDeduplication.isConflict(null)).isFalse()
        assertThat(NotificationDeduplication.isConflict(IllegalStateException("boom"))).isFalse()
    }
}
