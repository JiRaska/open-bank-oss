// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * [PostgresConflicts.isUniqueViolation] is what tells a lost seed race from a real DB fault in
 * [DocumentTemplateSeeder.onStart] — a false positive there swallows a genuine boot failure, and a
 * false negative crashloops a pod on a harmless concurrent first boot.
 */
class PostgresConflictsTest {

    @Test
    fun `SQLState 23505 on the exception itself is a unique violation`() {
        assertThat(PostgresConflicts.isUniqueViolation(SQLException("nope", "23505"))).isTrue()
    }

    @Test
    fun `a different SQLState with no telltale message is NOT a unique violation`() {
        // 23503 = foreign-key violation: a real fault the seeder must rethrow, not swallow.
        assertThat(PostgresConflicts.isUniqueViolation(SQLException("fk fails", "23503"))).isFalse()
    }

    @Test
    fun `the cause chain is walked, not just the top exception`() {
        val wrapped = RuntimeException("persist failed", IllegalStateException("boom", SQLException("x", "23505")))
        assertThat(PostgresConflicts.isUniqueViolation(wrapped)).isTrue()
    }

    @Test
    fun `a nested duplicate key MESSAGE is recognised even with no SQLException in the chain`() {
        val wrapped = RuntimeException(
            "flush",
            RuntimeException("ERROR: duplicate key value violates unique constraint \"documents_pkey\""),
        )
        assertThat(PostgresConflicts.isUniqueViolation(wrapped)).isTrue()
    }

    @Test
    fun `an unrelated failure anywhere in the chain is not a unique violation`() {
        val wrapped = RuntimeException("outer", IllegalStateException("connection refused"))
        assertThat(PostgresConflicts.isUniqueViolation(wrapped)).isFalse()
    }

    @Test
    fun `a null message does not throw and does not match`() {
        assertThat(PostgresConflicts.isUniqueViolation(RuntimeException())).isFalse()
    }
}
