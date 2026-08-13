// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import com.openbank.libs.authz.PolicyDecisionException
import jakarta.ws.rs.WebApplicationException
import org.assertj.core.api.Assertions.assertThat
import org.jboss.logging.MDC
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.DateTimeException
import java.time.Instant

class CommonExceptionMappersTest {

    @AfterEach
    fun clearMdc() {
        MDC.remove("correlationId")
    }

    @Test
    fun `IllegalArgumentException maps to 400 VALIDATION_ERROR`() {
        val response = IllegalArgumentExceptionMapper().toResponse(IllegalArgumentException("bad amount"))
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(400)
        assertThat(body.code).isEqualTo(ErrorCode.VALIDATION_ERROR.code)
        assertThat(body.message).isEqualTo("bad amount")
    }

    @Test
    fun `IllegalArgumentException falls back to a generic message when none is set`() {
        val body = IllegalArgumentExceptionMapper().toResponse(IllegalArgumentException()).entity as ApiError
        assertThat(body.message).isEqualTo("Invalid request")
    }

    @Test
    fun `IllegalStateException maps to 422 BUSINESS_RULE_VIOLATION`() {
        val response = IllegalStateExceptionMapper().toResponse(IllegalStateException("account frozen"))
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(422)
        assertThat(body.code).isEqualTo("BUSINESS_RULE_VIOLATION")
        assertThat(body.message).isEqualTo("account frozen")
    }

    @Test
    fun `NoSuchElementException maps to 404 NOT_FOUND`() {
        val response = NoSuchElementExceptionMapper().toResponse(NoSuchElementException("account 123"))
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(404)
        assertThat(body.code).isEqualTo(ErrorCode.NOT_FOUND.code)
        assertThat(body.message).isEqualTo("account 123")
    }

    @Test
    fun `WebApplicationException maps known statuses to their ErrorCode`() {
        val mapper = WebApplicationExceptionMapper()

        val unauthorized = WebApplicationException("nope", 401)
        assertThat((mapper.toResponse(unauthorized).entity as ApiError).code)
            .isEqualTo(ErrorCode.UNAUTHORIZED.code)

        val forbidden = WebApplicationException("forbidden", 403)
        assertThat((mapper.toResponse(forbidden).entity as ApiError).code).isEqualTo(ErrorCode.FORBIDDEN.code)

        val conflict = WebApplicationException("conflict", 409)
        assertThat((mapper.toResponse(conflict).entity as ApiError).code).isEqualTo(ErrorCode.CONFLICT.code)
    }

    @Test
    fun `WebApplicationException falls back to HTTP_ prefixed code for unmapped statuses`() {
        val response = WebApplicationExceptionMapper().toResponse(WebApplicationException("teapot", 418))
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(418)
        assertThat(body.code).isEqualTo("HTTP_418")
    }

    @Test
    fun `GenericExceptionMapper never leaks the original exception message`() {
        val secret = IllegalStateException("SELECT * FROM parties WHERE ssn = '990101/1234'")
        val response = GenericExceptionMapper().toResponse(secret)
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(500)
        assertThat(body.code).isEqualTo(ErrorCode.INTERNAL_ERROR.code)
        assertThat(body.message).doesNotContain("SELECT").doesNotContain("ssn")
        assertThat(body.message).contains(body.traceId)
    }

    @Test
    fun `traceId reuses the MDC correlation id set by CorrelationIdRequestFilter when present`() {
        MDC.put("correlationId", "corr-from-request")

        val body = NoSuchElementExceptionMapper().toResponse(NoSuchElementException("x")).entity as ApiError

        assertThat(body.traceId).isEqualTo("corr-from-request")
    }

    @Test
    fun `traceId falls back to a fresh id when no correlation id is in MDC`() {
        val body = NoSuchElementExceptionMapper().toResponse(NoSuchElementException("x")).entity as ApiError
        assertThat(body.traceId).isNotBlank()
    }

    // ADR-0155 four-eyes mappers (issue #1394): formerly duplicated verbatim across 10+
    // services plus a divergent {"code","message"}-shaped copy in notification-service.
    @Test
    fun `SelfApprovalNotAllowedException maps to 403 FORBIDDEN with the exception message`() {
        val response = SelfApprovalNotAllowedMapper().toResponse(SelfApprovalNotAllowedException("maker-1"))
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(403)
        assertThat(body.status).isEqualTo(403)
        assertThat(body.code).isEqualTo(ErrorCode.FORBIDDEN.code)
        assertThat(body.message).contains("maker-1")
    }

    @Test
    fun `PolicyDecisionException maps to 503 POLICY_DECISION_POINT_UNAVAILABLE, not a 4xx`() {
        val response = PolicyDecisionExceptionMapper()
            .toResponse(PolicyDecisionException("policy decision point unavailable: OPA call failed: null"))
        val body = response.entity as ApiError

        // A PDP outage is an availability failure — 503, never a 4xx business/validation error (#1797).
        assertThat(response.status).isEqualTo(503)
        assertThat(body.status).isEqualTo(503)
        assertThat(body.code).isEqualTo(ErrorCode.POLICY_DECISION_POINT_UNAVAILABLE.code)
        assertThat(body.message).contains("policy decision point unavailable")
    }

    @Test
    fun `InvalidApprovalStateException maps to 409 CONFLICT with the exception message`() {
        val exception = InvalidApprovalStateException("appr-1", ApprovalStatus.PENDING, ApprovalStatus.EXECUTED)
        val response = InvalidApprovalStateMapper().toResponse(exception)
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(409)
        assertThat(body.status).isEqualTo(409)
        assertThat(body.code).isEqualTo(ErrorCode.CONFLICT.code)
        assertThat(body.message).contains("appr-1")
    }

    /**
     * #3874: `ApiError.timestamp` defaulted to [Instant.EPOCH] and no call site in the fleet passed
     * it, so every error body carried `1970-01-01T00:00:00Z` — syntactically valid, never wrong out
     * loud, and useless to the support path the envelope's own message points at
     * ("contact support with traceId=…").
     *
     * Asserts RECENCY, not non-nullness: a non-null assertion passes against `Instant.EPOCH`, which
     * is exactly how this survived. Every mapper is covered, because the defect was in the shared
     * default and one green mapper says nothing about the other nine.
     */
    @Test
    fun `every mapper stamps a timestamp from now, not the epoch`() {
        val before = Instant.now()
        val bodies = listOf(
            IllegalArgumentExceptionMapper().toResponse(IllegalArgumentException("x")).entity,
            IllegalStateExceptionMapper().toResponse(IllegalStateException("x")).entity,
            NoSuchElementExceptionMapper().toResponse(NoSuchElementException("x")).entity,
            DateTimeExceptionMapper().toResponse(DateTimeException("x")).entity,
            GenericExceptionMapper().toResponse(RuntimeException("x")).entity,
            SelfApprovalNotAllowedMapper().toResponse(SelfApprovalNotAllowedException("m-1")).entity,
            PolicyDecisionExceptionMapper().toResponse(PolicyDecisionException("pdp down")).entity,
            InvalidApprovalStateMapper()
                .toResponse(InvalidApprovalStateException("a-1", ApprovalStatus.PENDING, ApprovalStatus.EXECUTED))
                .entity,
            WebApplicationExceptionMapper().toResponse(WebApplicationException(409)).entity,
        ).map { it as ApiError }
        val after = Instant.now()

        assertThat(bodies).allSatisfy { body ->
            assertThat(body.timestamp).isBetween(before, after)
        }
    }
}
