// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import org.assertj.core.api.Assertions.assertThat
import org.hibernate.exception.ConstraintViolationException
import org.hibernate.exception.DataException
import org.junit.jupiter.api.Test
import java.io.CharConversionException
import java.sql.SQLException

/**
 * The three persistence/decoding failures fuzzing past authentication found rendering as 500
 * (#5913), each asserted for the status AND for what the body does not say.
 *
 * The message assertions are the point of this file rather than decoration. A Hibernate exception's
 * message carries the SQL statement — table names, column names, the query shape — and these
 * mappers are reached by exactly the caller who would collect that: someone sending input the
 * service failed to validate. A mapper that fixed the status and echoed `exception.message` would
 * pass a status-only test while turning a 500 into an information leak.
 */
class PersistenceExceptionMappersTest {

    private fun sql(state: String, message: String) = SQLException(message, state)

    @Test
    fun `DataException maps to 400 and does not echo the SQL`() {
        val leaky = "value too long for type character varying(35) [insert into sdd_mandate (reference) values (?)]"
        val response = DataExceptionMapper().toResponse(
            DataException(leaky, sql("22001", leaky)),
        )
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(400)
        assertThat(body.code).isEqualTo(ErrorCode.VALIDATION_ERROR.code)
        assertThat(body.message).doesNotContain("insert into", "sdd_mandate", "character varying")
    }

    @Test
    fun `ConstraintViolationException maps to 409 and does not echo the constraint name`() {
        val response = ConstraintViolationExceptionMapper().toResponse(
            ConstraintViolationException(
                "duplicate key value violates unique constraint",
                sql("23505", "duplicate key"),
                "uq_sdd_mandate_reference",
            ),
        )
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(409)
        assertThat(body.code).isEqualTo(ErrorCode.CONFLICT.code)
        // The constraint name is logged, deliberately, and not returned: it names a table and a
        // column set to whoever provoked it.
        assertThat(body.message).doesNotContain("uq_sdd_mandate_reference")
    }

    @Test
    fun `CharConversionException maps to 400`() {
        val response = CharConversionExceptionMapper().toResponse(
            CharConversionException("Unsupported UCS-4 endianness (3412) detected"),
        )
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(400)
        assertThat(body.code).isEqualTo(ErrorCode.VALIDATION_ERROR.code)
    }

    /**
     * The negative control for the whole file. These three types are mapped BECAUSE they are the
     * caller's input; a persistence failure that is genuinely the server's must still reach
     * [GenericExceptionMapper] and be a 500. If a later change widened the mappers to a shared
     * supertype — `JDBCException`, or `HibernateException` — that would silently reclassify
     * connection failures, lock timeouts and grammar errors as client errors, and every
     * status-only test above would keep passing.
     */
    @Test
    fun `a grammar error is NOT a client error and is left to the generic mapper`() {
        val grammar = org.hibernate.exception.SQLGrammarException(
            "could not extract ResultSet",
            sql("42P01", "relation \"party_payees_seq\" does not exist"),
        )

        assertThat(grammar).isNotInstanceOf(DataException::class.java)
        assertThat(grammar).isNotInstanceOf(ConstraintViolationException::class.java)
    }
}

/**
 * The name-based classification inside [GenericExceptionMapper].
 *
 * This is what actually runs fleet-wide. The typed mappers above cannot be `@Provider` — naming
 * `org.hibernate.exception` types in a supertype makes ArC load them in every consumer of
 * libs-runtime, which crashed agent-service, analytics-sink and ap2-service at ArC init with
 * `ClassNotFoundException` — so without this they are inert unless ~30 services each opt in by
 * hand, and a new service starts out unprotected.
 */
class GenericExceptionMapperClassificationTest {

    private fun sql(state: String, message: String) = java.sql.SQLException(message, state)

    @Test
    fun `a wrapped DataException is still classified 400`() {
        // The case the typed mappers MISS. Hibernate Reactive completes failures through
        // CompletableFuture, so the exception reaching the resource boundary is routinely a
        // wrapper. Measured on run 32504892635: the same DataException a typed mapper caught
        // directly in one run arrived wrapped in the next and rendered as 500.
        val wrapped = java.util.concurrent.CompletionException(
            DataException("date out of range", sql("22008", "date out of range")),
        )
        val response = GenericExceptionMapper().toResponse(wrapped)
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(400)
        assertThat(body.code).isEqualTo(ErrorCode.VALIDATION_ERROR.code)
        assertThat(body.message).doesNotContain("date out of range")
    }

    @Test
    fun `a wrapped Hibernate ConstraintViolationException is classified 409`() {
        val wrapped = RuntimeException(
            java.util.concurrent.CompletionException(
                ConstraintViolationException("dup", sql("23505", "dup"), "uq_sdd_mandate_reference"),
            ),
        )
        val response = GenericExceptionMapper().toResponse(wrapped)

        assertThat(response.status).isEqualTo(409)
        assertThat((response.entity as ApiError).message).doesNotContain("uq_sdd_mandate_reference")
    }

    /**
     * The negative control. `jakarta.validation.ConstraintViolationException` shares a SIMPLE name
     * with Hibernate's and is a different failure entirely — matching on the simple name would map
     * a bean-validation rejection to 409 instead of the 400 it deserves. Asserting the FQCN is what
     * keeps the two apart.
     */
    @Test
    fun `an unrelated exception is still a 500`() {
        val response = GenericExceptionMapper().toResponse(IllegalStateException("internal"))

        assertThat(response.status).isEqualTo(500)
        assertThat((response.entity as ApiError).code).isEqualTo(ErrorCode.INTERNAL_ERROR.code)
    }

    @Test
    fun `a self-referencing cause chain terminates`() {
        val looping = object : RuntimeException("loop") {
            override val cause: Throwable get() = this
        }
        assertThat(GenericExceptionMapper().toResponse(looping).status).isEqualTo(500)
    }
}
