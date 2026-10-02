// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.error

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class DomainExceptionTest {

    private enum class WidgetErrorCode(override val category: ErrorCategory, override val title: String) : ErrorCode {
        WIDGET_NOT_FOUND(ErrorCategory.NOT_FOUND, "The widget does not exist"),
        WIDGET_REGISTRY_FAILED(ErrorCategory.UPSTREAM_FAILURE, "The widget registry answered unusably"),
        ;

        override val code: String get() = name
    }

    private class Malformed : ErrorCode {
        override val code = "widget.not-found"
        override val category = ErrorCategory.NOT_FOUND
        override val title = "x"
    }

    @Test
    fun `each subclass fixes its category and defaults to the matching platform code`() {
        val cases: List<Pair<DomainException, ErrorCategory>> = listOf(
            ValidationFailure("bad") to ErrorCategory.VALIDATION,
            AuthenticationRequired() to ErrorCategory.UNAUTHENTICATED,
            AccessDenied() to ErrorCategory.FORBIDDEN,
            NotFound("gone") to ErrorCategory.NOT_FOUND,
            Conflict("stale") to ErrorCategory.CONFLICT,
            RuleViolation("no") to ErrorCategory.RULE_VIOLATION,
            RateLimitExceeded() to ErrorCategory.RATE_LIMITED,
            UpstreamFailure("bad gateway", WidgetErrorCode.WIDGET_REGISTRY_FAILED) to ErrorCategory.UPSTREAM_FAILURE,
            Unavailable() to ErrorCategory.UNAVAILABLE,
        )
        cases.forEach { (exception, category) ->
            assertThat(exception.category).`as`(exception.javaClass.simpleName).isEqualTo(category)
            assertThat(exception.errorCode.category).`as`(exception.javaClass.simpleName).isEqualTo(category)
            assertThat(exception.isConsistent).`as`(exception.javaClass.simpleName).isTrue()
        }
        // Every category a caller can be answered with has a class; INTERNAL is what an UNTYPED failure is.
        assertThat(cases.map { it.second }).containsExactlyInAnyOrderElementsOf(
            ErrorCategory.entries - ErrorCategory.INTERNAL,
        )
    }

    @Test
    fun `no subclass is one of the JDK types the generic mappers claim`() {
        // #526: a DomainException that was also an IllegalStateException would be resolvable by two
        // mappers. And CancellationException IS an IllegalStateException, which is how a cancelled
        // coroutine is answered as a business-rule violation today.
        listOf(ValidationFailure("x"), NotFound("x"), Conflict("x"), RuleViolation("x")).forEach {
            assertThat(it).isNotInstanceOf(IllegalArgumentException::class.java)
            assertThat(it).isNotInstanceOf(IllegalStateException::class.java)
            assertThat(it).isNotInstanceOf(NoSuchElementException::class.java)
        }
        assertThat(java.util.concurrent.CancellationException("x")).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `the client message and the internal detail are separate`() {
        val cause = RuntimeException("connection reset")
        val e = NotFound("Widget not found", internalDetail = "no row in widget for id=42 tenant=7", cause = cause)

        assertThat(e.clientMessage).isEqualTo("Widget not found")
        assertThat(e.message).isEqualTo("no row in widget for id=42 tenant=7")
        assertThat(e.cause).isSameAs(cause)
        // With no internal detail the log line falls back to the client message, never to null.
        assertThat(NotFound("Widget not found").message).isEqualTo("Widget not found")
    }

    @Test
    fun `a domain code keeps its own spelling`() {
        val e = NotFound("Widget not found", WidgetErrorCode.WIDGET_NOT_FOUND)

        assertThat(e.errorCode.code).isEqualTo("WIDGET_NOT_FOUND")
        assertThat(e.isConsistent).isTrue()
    }

    @Test
    fun `a code from another category or a malformed one is inconsistent, and construction does not throw`() {
        assertThat(NotFound("x", PlatformErrorCode.CONFLICT).isConsistent).isFalse()
        assertThat(NotFound("x", Malformed()).isConsistent).isFalse()
    }

    @Test
    fun `retry-after and violations are carried`() {
        val limited = RateLimitExceeded(retryAfter = Duration.ofSeconds(30))
        val invalid = ValidationFailure("bad", listOf(Violation("amount", "must be positive", "POSITIVE")))

        assertThat(limited.retryAfter).isEqualTo(Duration.ofSeconds(30))
        assertThat(invalid.violations).containsExactly(Violation("amount", "must be positive", "POSITIVE"))
    }

    @Test
    fun `requireParam returns the value or throws a ValidationFailure naming the parameter`() {
        assertThat(requireParam("v", "date")).isEqualTo("v")

        assertThatThrownBy { requireParam<String>(null, "date") }
            .isInstanceOfSatisfying(ValidationFailure::class.java) {
                assertThat(it.clientMessage).isEqualTo("Parameter 'date' is required")
                assertThat(it.violations).containsExactly(Violation("date", "is required"))
                assertThat(it.errorCode).isEqualTo(PlatformErrorCode.VALIDATION_ERROR)
            }
    }

    @Test
    fun `requireValid throws only when the condition fails and does not build the message otherwise`() {
        var built = 0
        requireValid(true) {
            built++
            "unused"
        }
        assertThat(built).isZero()

        assertThatThrownBy { requireValid(false, field = "amount") { "amount must be positive" } }
            .isInstanceOfSatisfying(ValidationFailure::class.java) {
                assertThat(it.clientMessage).isEqualTo("amount must be positive")
                assertThat(it.violations).containsExactly(Violation("amount", "amount must be positive"))
            }
        assertThatThrownBy { requireValid(false) { "nope" } }
            .isInstanceOfSatisfying(ValidationFailure::class.java) { assertThat(it.violations).isEmpty() }
    }
}
