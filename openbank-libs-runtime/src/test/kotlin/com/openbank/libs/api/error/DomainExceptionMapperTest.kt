// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.libs.domain.error.AccessDenied
import com.openbank.libs.domain.error.AuthenticationRequired
import com.openbank.libs.domain.error.Conflict
import com.openbank.libs.domain.error.DomainException
import com.openbank.libs.domain.error.ErrorCategory
import com.openbank.libs.domain.error.NotFound
import com.openbank.libs.domain.error.PlatformErrorCode
import com.openbank.libs.domain.error.RateLimitExceeded
import com.openbank.libs.domain.error.RuleViolation
import com.openbank.libs.domain.error.Unavailable
import com.openbank.libs.domain.error.UpstreamFailure
import com.openbank.libs.domain.error.ValidationFailure
import com.openbank.libs.domain.error.Violation
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import com.openbank.libs.domain.error.ErrorCode as DomainErrorCode

class DomainExceptionMapperTest {

    private enum class WidgetErrorCode(override val category: ErrorCategory, override val title: String) :
        DomainErrorCode {
        WIDGET_NOT_FOUND(ErrorCategory.NOT_FOUND, "The widget does not exist"),
        WIDGET_REGISTRY_FAILED(ErrorCategory.UPSTREAM_FAILURE, "The widget registry answered unusably"),
        ;

        override val code: String get() = name
    }

    private class Malformed : DomainErrorCode {
        override val code = "widget.not-found"
        override val category = ErrorCategory.NOT_FOUND
        override val title = "leaked title"
    }

    private val registry = SimpleMeterRegistry()
    private val mapper = DomainExceptionMapper()

    @BeforeEach
    fun addRegistry() {
        Metrics.addRegistry(registry)
    }

    @AfterEach
    fun removeRegistry() {
        Metrics.removeRegistry(registry)
    }

    private fun body(e: DomainException) = mapper.toResponse(e).entity as ProblemDetail

    @Test
    fun `every category maps to its status`() {
        val expected: List<Pair<DomainException, Int>> = listOf(
            ValidationFailure("x") to 400,
            AuthenticationRequired() to 401,
            AccessDenied() to 403,
            NotFound("x") to 404,
            Conflict("x") to 409,
            RuleViolation("x") to 422,
            RateLimitExceeded() to 429,
            UpstreamFailure("x", WidgetErrorCode.WIDGET_REGISTRY_FAILED) to 502,
            Unavailable() to 503,
        )
        expected.forEach { (exception, status) ->
            val response = mapper.toResponse(exception)
            assertThat(response.status).`as`(exception.javaClass.simpleName).isEqualTo(status)
            assertThat((response.entity as ProblemDetail).status).isEqualTo(status)
        }
        assertThat(ErrorCategory.INTERNAL.httpStatus()).isEqualTo(500)
    }

    @Test
    fun `the body carries the RFC 9457 members, the extensions and the ApiError members`() {
        val before = Instant.now()
        val problem = body(NotFound("Widget w-1 not found", WidgetErrorCode.WIDGET_NOT_FOUND))

        assertThat(problem.type).isEqualTo("urn:openbank:error:widget-not-found")
        assertThat(problem.title).isEqualTo("The widget does not exist")
        assertThat(problem.status).isEqualTo(404)
        assertThat(problem.detail).isEqualTo("Widget w-1 not found")
        assertThat(problem.instance).isEqualTo("urn:openbank:error-occurrence:${problem.correlationId}")
        assertThat(problem.code).isEqualTo("WIDGET_NOT_FOUND")
        assertThat(problem.correlationId).isNotBlank()
        assertThat(problem.retryable).isFalse()
        // The ApiError members, under their existing names and with the same values.
        assertThat(problem.traceId).isEqualTo(problem.correlationId)
        assertThat(problem.message).isEqualTo(problem.detail)
        assertThat(problem.timestamp).isBetween(before, Instant.now())
        assertThat(problem.violations).isNull()
        assertThat(problem.details).isNull()
    }

    @Test
    fun `the serialised document is a superset of the ApiError envelope`() {
        val json = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        val tree = json.readTree(json.writeValueAsString(body(ValidationFailure("bad", listOf(Violation("a", "b"))))))

        val apiErrorFields = listOf("traceId", "status", "code", "message", "timestamp", "details")
        val problemFields = listOf("type", "title", "detail", "instance", "correlationId", "retryable", "violations")
        assertThat(tree.fieldNames().asSequence().toList()).containsAll(apiErrorFields + problemFields)
        // A violation without a code must not render `"code": null`.
        assertThat(tree["violations"][0].has("code")).isFalse()
        assertThat(tree["details"][0]["field"].asText()).isEqualTo("a")
    }

    @Test
    fun `the internal detail and the cause never reach the body`() {
        val secret = "SELECT * FROM widget WHERE tenant=7 -- policy rule r42 denied"
        val problem = body(
            AccessDenied(internalDetail = secret, cause = IllegalStateException("jdbc:postgresql://db.internal")),
        )
        val rendered = ObjectMapper().registerKotlinModule().registerModule(
            JavaTimeModule(),
        ).writeValueAsString(problem)

        assertThat(problem.detail).isEqualTo("Access denied")
        assertThat(rendered).doesNotContain("SELECT", "r42", "jdbc", "IllegalStateException")
    }

    @Test
    fun `violations are rendered in both vocabularies`() {
        val problem = body(ValidationFailure("bad", listOf(Violation("amount", "must be positive", "POSITIVE"))))

        assertThat(problem.violations).containsExactly(ProblemViolation("amount", "must be positive", "POSITIVE"))
        assertThat(problem.details).containsExactly(FieldError("amount", "must be positive"))
    }

    @Test
    fun `retry-after becomes a header, and retryable follows the code`() {
        val limited = mapper.toResponse(RateLimitExceeded(retryAfter = Duration.ofSeconds(30)))
        val subSecond = mapper.toResponse(Unavailable(retryAfter = Duration.ofMillis(200)))
        val inProgress = mapper.toResponse(Conflict("busy", PlatformErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS))

        assertThat(limited.getHeaderString(HttpHeaders.RETRY_AFTER)).isEqualTo("30")
        assertThat((limited.entity as ProblemDetail).retryable).isTrue()
        // Retry-After is whole seconds; a sub-second wait must not round down to "retry now".
        assertThat(subSecond.getHeaderString(HttpHeaders.RETRY_AFTER)).isEqualTo("1")
        assertThat(inProgress.getHeaderString(HttpHeaders.RETRY_AFTER)).isNull()
        assertThat((inProgress.entity as ProblemDetail).retryable).isTrue()
        assertThat(body(Conflict("stale")).retryable).isFalse()
    }

    @Test
    fun `a code from the wrong category is answered as an internal error and carries nothing over`() {
        val response = mapper.toResponse(
            NotFound("should not be shown", PlatformErrorCode.CONFLICT, internalDetail = "also hidden"),
        )
        val problem = response.entity as ProblemDetail

        assertThat(response.status).isEqualTo(500)
        assertThat(problem.code).isEqualTo("INTERNAL_ERROR")
        assertThat(problem.detail).isEqualTo(PlatformErrorCode.INTERNAL_ERROR.title)
        assertThat(problem.message).doesNotContain("should not be shown")
    }

    @Test
    fun `a malformed code is answered as an internal error and never becomes a label`() {
        val response = mapper.toResponse(NotFound("x", Malformed()))
        val problem = response.entity as ProblemDetail

        assertThat(response.status).isEqualTo(500)
        assertThat(problem.code).isEqualTo("INTERNAL_ERROR")
        assertThat(problem.title).doesNotContain("leaked")
        assertThat(registry.find(ApiErrorMetrics.ERRORS).tag("code", "widget.not-found").counter()).isNull()
        assertThat(registry.get(ApiErrorMetrics.ERRORS).tag("code", "INTERNAL_ERROR").counter().count()).isEqualTo(1.0)
    }

    @Test
    fun `the media type is problem+json only when the request accepts it`() {
        fun accepting(vararg types: String) = DomainExceptionMapper().also {
            it.headers = mockk { every { acceptableMediaTypes } returns types.map(MediaType::valueOf) }
        }

        assertThat(mapper.toResponse(NotFound("x")).mediaType.toString()).isEqualTo("application/json")
        assertThat(accepting("application/json").toResponse(NotFound("x")).mediaType.toString())
            .isEqualTo("application/json")
        assertThat(accepting("*/*").toResponse(NotFound("x")).mediaType.toString()).isEqualTo("application/json")
        assertThat(
            accepting("application/json", "application/problem+json").toResponse(NotFound("x")).mediaType.toString(),
        )
            .isEqualTo("application/problem+json")
    }

    @Test
    fun `reading Accept outside a request scope falls back to json`() {
        val outside = DomainExceptionMapper().also {
            it.headers = mockk { every { acceptableMediaTypes } throws IllegalStateException("no request scope") }
        }

        assertThat(outside.toResponse(NotFound("x")).mediaType.toString()).isEqualTo("application/json")
    }

    @Test
    fun `each rendered error is counted by code, category and status`() {
        mapper.toResponse(NotFound("x", WidgetErrorCode.WIDGET_NOT_FOUND))
        mapper.toResponse(NotFound("y", WidgetErrorCode.WIDGET_NOT_FOUND))

        val counter = registry.get(ApiErrorMetrics.ERRORS)
            .tag("code", "WIDGET_NOT_FOUND").tag("category", "NOT_FOUND").tag("status", "404").counter()
        assertThat(counter.count()).isEqualTo(2.0)
    }
}
