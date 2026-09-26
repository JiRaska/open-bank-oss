// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.integration

import com.openbank.interest.application.port.out.LedgerPostingRejectedException
import com.openbank.interest.infrastructure.client.JournalResponse
import com.openbank.interest.infrastructure.client.LedgerCallGuard
import com.openbank.interest.infrastructure.client.LedgerRestClient
import com.openbank.interest.infrastructure.client.PostJournalRequest
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.Mock
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The REAL [LedgerCallGuard] — its SmallRye Fault Tolerance interceptors are what is under test, so
 * this has to be a CDI bean in a running Quarkus, not a hand-constructed instance (which would carry
 * no `@Retry`/`@CircuitBreaker` at all and pass against any annotation).
 *
 * #10404: one closed-day 409 was retried 3 times, opened the breaker, and re-opened it on every
 * half-open probe, so every sweep afterwards reported only "circuit breaker is open" — and any other
 * capitalization's journal posted in that window failed with it too.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_interest_it")],
)
class LedgerCallGuardIT {

    @Inject
    lateinit var guard: LedgerCallGuard

    @Inject
    @RestClient
    lateinit var ledger: ScriptedLedgerClient

    @Test
    fun `a deterministic 4xx is not retried and never opens the breaker, while a 5xx still does both`() {
        // 1. Ten refusals in a row — twice the breaker's request-volume threshold, all failures if
        //    counted. Each must reach the ledger exactly ONCE and come back as the refusal itself.
        repeat(10) { i ->
            val key = "rejected-$i"
            ledger.answer(key, Response.Status.CONFLICT.statusCode)
            val failure = post(key)
            assertThat(failure)
                .`as`("attempt $i surfaces the ledger's refusal, never CircuitBreakerOpenException")
                .isInstanceOf(LedgerPostingRejectedException::class.java)
            assertThat((failure as LedgerPostingRejectedException).status).isEqualTo(409)
            assertThat(ledger.calls(key)).`as`("a refusal is not retried").isEqualTo(1)
        }

        // 2. ...so an unrelated, valid journal right after still goes through.
        assertThat(post("healthy-after-refusals")).`as`("the breaker stayed closed").isNull()

        // 3. Negative control: a transient 503 IS retried (1 + 3) and DOES open the breaker. Without
        //    this, the test above would pass against a guard with no resilience at all.
        ledger.answer("outage", Response.Status.SERVICE_UNAVAILABLE.statusCode)
        assertThat(post("outage")).isNotInstanceOf(LedgerPostingRejectedException::class.java)
        // Retried — and cut short once the breaker's rolling window (5 calls, 50 %) trips mid-retry,
        // which is the breaker doing its job: 3 attempts reach the ledger, the 4th is refused.
        assertThat(ledger.calls("outage")).`as`("a transient failure is retried").isGreaterThan(1)
        assertThat(post("blocked-by-open-breaker"))
            .`as`("and the breaker opens on a genuinely failing ledger")
            .isInstanceOf(CircuitBreakerOpenException::class.java)
        assertThat(ledger.calls("blocked-by-open-breaker")).isZero()
    }

    private fun post(key: String): Throwable? = catchThrowable {
        guard.postJournal(request(key)).await().atMost(Duration.ofSeconds(30))
    }

    private fun request(key: String) = PostJournalRequest(
        idempotencyKey = key,
        transactionId = UUID.randomUUID(),
        entryDate = "2026-09-21",
        valueDate = "2026-09-01",
        description = null,
        lines = emptyList(),
        createdBy = UUID.randomUUID(),
    )
}

/**
 * Stands in for the HTTP client beneath the guard. Answers each idempotency key with a scripted
 * status (default 201) and counts how many times the guard actually called it.
 */
@Mock
@ApplicationScoped
@RestClient
class ScriptedLedgerClient : LedgerRestClient {
    private val statuses = ConcurrentHashMap<String, Int>()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun answer(key: String, status: Int) {
        statuses[key] = status
    }

    fun calls(key: String): Int = counts[key]?.get() ?: 0

    override fun postJournal(request: PostJournalRequest): Uni<JournalResponse> {
        counts.computeIfAbsent(request.idempotencyKey) { AtomicInteger() }.incrementAndGet()
        val status = statuses[request.idempotencyKey]
            ?: return Uni.createFrom().item(JournalResponse(UUID.randomUUID(), request.transactionId, "POSTED"))
        return Uni.createFrom().failure(
            WebApplicationException(Response.status(status).entity("{\"error\":\"scripted $status\"}").build()),
        )
    }
}
