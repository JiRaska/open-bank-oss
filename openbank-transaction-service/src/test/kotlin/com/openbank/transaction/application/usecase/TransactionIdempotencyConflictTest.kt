// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.usecase

import io.vertx.pgclient.PgException
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import java.sql.SQLException

class TransactionIdempotencyConflictTest {
    private val constraint = TransactionIdempotencyConflict.CONSTRAINT

    @Test
    fun `recognizes exact reactive and JDBC constraint forms`() {
        val jdbc = SQLException("duplicate key violates unique constraint \"$constraint\"", "23505")
        assertThat(TransactionIdempotencyConflict.isConflict(RuntimeException(jdbc))).isTrue()
        assertThat(
            TransactionIdempotencyConflict.isConflict(
                ConstraintViolationException("duplicate", jdbc, constraint),
            ),
        ).isTrue()
        assertThat(
            TransactionIdempotencyConflict.isConflict(
                PgException(
                    "duplicate", "ERROR", "23505", null, null, null, null, null, null,
                    null, null, null, null, null, null, null, constraint,
                ),
            ),
        ).isTrue()
    }

    @Test
    fun `does not turn unrelated errors or partial constraint names into a replay`() {
        listOf(
            SQLException("idempotency failed", "23505"),
            SQLException("constraint \"${constraint}_other\"", "23505"),
            SQLException("constraint \"$constraint\"", "23503"),
            IllegalStateException("$constraint"),
        ).forEach { assertThat(TransactionIdempotencyConflict.isConflict(it)).isFalse() }
    }
}
