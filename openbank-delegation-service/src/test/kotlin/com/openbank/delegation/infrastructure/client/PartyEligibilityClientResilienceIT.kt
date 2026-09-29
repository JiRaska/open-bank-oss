// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.delegation.infrastructure.client

import com.openbank.delegation.it.PostgresTestResource
import com.openbank.libs.resilience.ResilienceProfiles
import io.quarkus.test.Mock
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ResilientPartyEligibilityClient.eligibilityOf] adopted the ADR-0321 `read` profile
 * (`@ResilienceProfile(ResilienceProfiles.READ)` + the `ResilienceProfiles.Read` constants) in
 * place of its own literals. `ResilientPartyEligibilityClientTest` cannot prove any of that: it
 * constructs the class by hand (`ResilientPartyEligibilityClient(rest)`), which carries no
 * `@Retry`/`@Timeout`/`@CircuitBreaker` interceptor at all — SmallRye Fault Tolerance only wraps
 * the CDI proxy Quarkus builds around an `@ApplicationScoped` bean, exactly the trap #11014's own
 * review flagged ("the retry loop in the test applies the annotation's rule ... the SmallRye
 * interceptor itself needs a CDI container this plain library cannot boot"). This test injects
 * the real bean in a running Quarkus and replaces only [PidServiceRestClient] with a scripted
 * `@Mock`, so what is under test is the interceptor SmallRye actually wove in.
 *
 * Negative proof recorded for this PR: with `@Retry` removed from
 * `ResilientPartyEligibilityClient.eligibilityOf`, `a transient 5xx from pid-service is retried
 * through the real interceptor` failed — `ScriptedPidServiceRestClient` saw exactly 1 call instead
 * of 1 + ResilienceProfiles.Read.MAX_RETRIES, and the method threw immediately. Restored before
 * commit.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PartyEligibilityClientResilienceIT {

    @jakarta.inject.Inject
    lateinit var client: ResilientPartyEligibilityClient

    @jakarta.inject.Inject
    @RestClient
    lateinit var pid: ScriptedPidServiceRestClient

    @Test
    fun `a transient 5xx from pid-service is retried through the real interceptor`() {
        val partyId = UUID.randomUUID()
        pid.failEveryCallWith(Response.Status.SERVICE_UNAVAILABLE.statusCode)

        val failure = catchThrowable { runBlocking { client.eligibilityOf(partyId) } }

        assertThat(failure).isNotNull()
        // 1 initial attempt + ResilienceProfiles.Read.MAX_RETRIES retries.
        assertThat(pid.calls(partyId))
            .`as`(
                "the @Retry(maxRetries = ResilienceProfiles.Read.MAX_RETRIES) constant is what the interceptor honours",
            )
            .isEqualTo(1 + ResilienceProfiles.Read.MAX_RETRIES)
    }

    @Test
    fun `a healthy pid-service response is not retried`() {
        val partyId = UUID.randomUUID()
        pid.answerHealthy()

        val eligibility = runBlocking { client.eligibilityOf(partyId) }

        assertThat(eligibility.partyId).isEqualTo(partyId)
        assertThat(pid.calls(partyId)).isEqualTo(1)
    }
}

/**
 * Stands in for [PidServiceRestClient]. Every call is counted per party id so the test can assert
 * the interceptor's actual retry count rather than trusting the annotation text.
 */
@Mock
@ApplicationScoped
@RestClient
class ScriptedPidServiceRestClient : PidServiceRestClient {
    private val counts = mutableMapOf<UUID, AtomicInteger>()
    private var failWithStatus: Int? = null

    fun failEveryCallWith(status: Int) {
        failWithStatus = status
    }

    fun answerHealthy() {
        failWithStatus = null
    }

    fun calls(partyId: UUID): Int = counts[partyId]?.get() ?: 0

    override suspend fun getParty(id: UUID): PidPartyResponse {
        counts.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
        val status = failWithStatus
        if (status != null) {
            throw WebApplicationException(Response.status(status).entity("{\"error\":\"scripted $status\"}").build())
        }
        return PidPartyResponse(
            id = id,
            status = "ACTIVE",
            partyType = null,
            kycAttributes = PidKycAttributes("FULL"),
            coreAttributes = null,
        )
    }
}
