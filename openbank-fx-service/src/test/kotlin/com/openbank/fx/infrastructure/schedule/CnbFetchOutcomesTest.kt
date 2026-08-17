// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.schedule

import com.openbank.fx.application.port.`in`.CnbIngestionResult
import com.openbank.fx.domain.cnb.CnbFixingParser
import com.openbank.libs.observability.FeedFetchOutcome
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDate

/**
 * The four states a ČNB fetch can end in, and the ways they used to be indistinguishable.
 *
 * Before #4743 the scheduler had exactly two behaviours: it recorded a success, or it logged one
 * ERROR line. That collapsed a 404, an HTML error page, a timeout **and** a job that never ran into
 * one signal, and it put "the feed carried nothing we asked for" on the *success* side.
 */
class CnbFetchOutcomesTest {

    // ── the success side, where the invisible case lives ────────────────────────

    @Test
    fun `a run that stored rates is FETCHED`() {
        assertThat(CnbFetchOutcomes.of(result(ingested = 3, skipped = 0))).isEqualTo(FeedFetchOutcome.FETCHED)
    }

    @Test
    fun `an idempotent re-run is FETCHED, not EMPTY - the rates arrived, we already had them`() {
        assertThat(CnbFetchOutcomes.of(result(ingested = 0, skipped = 3)))
            .describedAs(
                "the daily job is idempotent per business day, so a second run of the same fixing " +
                    "stores nothing and is perfectly healthy; classifying it EMPTY would make the " +
                    "feed look dead every time the scheduler ran twice",
            )
            .isEqualTo(FeedFetchOutcome.FETCHED)
    }

    @Test
    fun `a run that touched no configured currency at all is EMPTY`() {
        assertThat(CnbFetchOutcomes.of(result(ingested = 0, skipped = 0)))
            .describedAs(
                "THE case with no prior signal: the feed answered 200, parsed cleanly, and carried " +
                    "none of openbank.cnb.currencies. Pre-#4743 this called recordSuccess() and was " +
                    "indistinguishable from a healthy ingestion in the metric and in the log.",
            )
            .isEqualTo(FeedFetchOutcome.EMPTY)
    }

    // ── the failure side ────────────────────────────────────────────────────────

    @Test
    fun `a 404 is HTTP_ERROR`() {
        val notFound = WebApplicationException(Response.Status.NOT_FOUND)

        assertThat(CnbFetchOutcomes.ofFailure(notFound))
            .describedAs("the motivating incident: the fixing URL was a 404 for 46 days (#2204)")
            .isEqualTo(FeedFetchOutcome.HTTP_ERROR)
    }

    @Test
    fun `a 500 is HTTP_ERROR`() {
        assertThat(CnbFetchOutcomes.ofFailure(WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR)))
            .isEqualTo(FeedFetchOutcome.HTTP_ERROR)
    }

    @Test
    fun `an open circuit breaker is UNREACHABLE, not a verdict about the feed`() {
        assertThat(CnbFetchOutcomes.ofFailure(CircuitBreakerOpenException("open")))
            .isEqualTo(FeedFetchOutcome.UNREACHABLE)
    }

    @Test
    fun `a timeout is UNREACHABLE`() {
        assertThat(CnbFetchOutcomes.ofFailure(TimeoutException("8s"))).isEqualTo(FeedFetchOutcome.UNREACHABLE)
        assertThat(CnbFetchOutcomes.ofFailure(SocketTimeoutException("read timed out")))
            .isEqualTo(FeedFetchOutcome.UNREACHABLE)
    }

    @Test
    fun `an unreachable host is UNREACHABLE`() {
        assertThat(CnbFetchOutcomes.ofFailure(IOException("connect: no route to host")))
            .describedAs(
                "apl.cnb.cz genuinely answers some network positions and not others, so " +
                    "'could not reach it' must never be reported as 'the feed is dead'",
            )
            .isEqualTo(FeedFetchOutcome.UNREACHABLE)
    }

    /**
     * Driven through the real [CnbFixingParser] rather than a hand-built exception: the classifier's
     * `IllegalArgumentException` branch is only correct as long as that is what the parser really
     * throws, and a hand-built exception would keep this test green after the parser changed its
     * failure type — the same vacuity as deriving both halves of a pact from one annotation.
     */
    @Test
    fun `the HTML page CNB serves as its own 404 is PARSE_ERROR`() {
        val htmlErrorPage = "<!DOCTYPE html><html><head><title>404</title></head><body>Nenalezeno</body></html>"

        val thrown = catchThrowable { CnbFixingParser.parse(htmlErrorPage) }

        assertThat(thrown)
            .describedAs("ČNB serves its 404 as a 58 KB HTML page, so one broken URL has two faces")
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(CnbFetchOutcomes.ofFailure(thrown)).isEqualTo(FeedFetchOutcome.PARSE_ERROR)
    }

    @Test
    fun `a feed with a valid header but no rate lines is PARSE_ERROR, never EMPTY`() {
        val headerOnly = "30.05.2026 #104\nzemě|měna|množství|kód|kurz"

        val thrown = catchThrowable { CnbFixingParser.parse(headerOnly) }

        assertThat(CnbFetchOutcomes.ofFailure(thrown))
            .describedAs(
                "an empty DOCUMENT and a document empty FOR US are different upstream behaviours: " +
                    "the parser refuses the first outright, so EMPTY is reserved for the second",
            )
            .isEqualTo(FeedFetchOutcome.PARSE_ERROR)
    }

    @Test
    fun `a wrapped cause is classified by the cause, not by the wrapper`() {
        val wrapped = RuntimeException("ingestion failed", WebApplicationException(Response.Status.NOT_FOUND))

        assertThat(CnbFetchOutcomes.ofFailure(wrapped))
            .describedAs("@Retry and the reactive pipeline both wrap; the outcome is a property of the cause")
            .isEqualTo(FeedFetchOutcome.HTTP_ERROR)
    }

    @Test
    fun `an unrecognised failure blames our own ingestion, not the third party`() {
        assertThat(CnbFetchOutcomes.ofFailure(IllegalStateException("no Vert.x context")))
            .describedAs(
                "a wrong attribution in a triage metric is worse than a vague one — an HR000068 is " +
                    "ours, and must not be counted as the ČNB being down",
            )
            .isEqualTo(FeedFetchOutcome.PARSE_ERROR)
    }

    @Test
    fun `a self-referential cause chain terminates`() {
        val looping = object : RuntimeException("loops") {
            override val cause: Throwable get() = this
        }

        assertThat(CnbFetchOutcomes.ofFailure(looping)).isEqualTo(FeedFetchOutcome.PARSE_ERROR)
    }

    private fun result(ingested: Int, skipped: Int) =
        CnbIngestionResult(LocalDate.of(2026, 5, 30), 104, ingested, skipped, emptyList())
}
