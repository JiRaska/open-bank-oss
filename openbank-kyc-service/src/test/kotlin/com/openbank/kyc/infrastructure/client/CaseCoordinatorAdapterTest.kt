// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.application.port.out.CaseOpenOutcome
import com.openbank.kyc.domain.model.ScreeningTriggerFailure
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.util.Optional
import java.util.UUID

/**
 * HTTP-status taxonomy of the ADR-0256 contract, per outcome. The resilience annotations are
 * inert in a plain JVM (no Quarkus interceptor), so these tests exercise the classification;
 * retry/circuit-breaker behaviour is the wrapper's, mirrored from SanctionsScreeningAdapter.
 */
class CaseCoordinatorAdapterTest {

    private val client = mockk<CaseCoordinatorClient>()
    private val partyId = UUID.randomUUID()

    private fun adapter(url: Optional<String> = Optional.of("http://coordinator:8146")) =
        CaseCoordinatorAdapter(client, url, ObjectMapper()).also { it.self = it }

    private fun respond(status: Int, body: String? = null) {
        val response = mockk<Response>()
        every { response.status } returns status
        if (body != null) every { response.readEntity(String::class.java) } returns body
        every { client.openCase(any()) } returns Uni.createFrom().item(response)
    }

    @Test
    fun `201 with a caseId is Opened`(): Unit = runBlocking {
        respond(201, """{"caseId":"case-123"}""")
        val outcome = adapter().openAmlAlertCase(partyId)
        assertThat(outcome).isEqualTo(CaseOpenOutcome.Opened("case-123"))
    }

    @Test
    fun `201 without a caseId is a deterministic rejection`(): Unit = runBlocking {
        respond(201, """{}""")
        val outcome = adapter().openAmlAlertCase(partyId)
        val rejected = assertInstanceOf<CaseOpenOutcome.Rejected>(outcome)
        assertThat(rejected.reason).isEqualTo(ScreeningTriggerFailure.COORDINATOR_REJECTED)
    }

    @Test
    fun `409 is AlreadyExists — idempotent success`(): Unit = runBlocking {
        respond(409, """{"error":"a case for this class and subject already runs"}""")
        assertThat(adapter().openAmlAlertCase(partyId)).isEqualTo(CaseOpenOutcome.AlreadyExists)
    }

    @Test
    fun `429 and 503 are retryable`(): Unit = runBlocking {
        respond(429)
        assertInstanceOf<CaseOpenOutcome.Retryable>(adapter().openAmlAlertCase(partyId))

        respond(503)
        assertInstanceOf<CaseOpenOutcome.Retryable>(adapter().openAmlAlertCase(partyId))
    }

    @Test
    fun `a 500 is retryable but a 4xx is a deterministic rejection`(): Unit = runBlocking {
        respond(500)
        assertInstanceOf<CaseOpenOutcome.Retryable>(adapter().openAmlAlertCase(partyId))

        respond(403)
        val rejected = assertInstanceOf<CaseOpenOutcome.Rejected>(adapter().openAmlAlertCase(partyId))
        assertThat(rejected.reason).isEqualTo(ScreeningTriggerFailure.COORDINATOR_REJECTED)
    }

    @Test
    fun `a missing base url fails visible as NOT_CONFIGURED, never silently skipped`(): Unit = runBlocking {
        val outcome = adapter(Optional.empty()).openAmlAlertCase(partyId)
        val rejected = assertInstanceOf<CaseOpenOutcome.Rejected>(outcome)
        assertThat(rejected.reason).isEqualTo(ScreeningTriggerFailure.NOT_CONFIGURED)
    }

    @Test
    fun `a transport fault is retryable`(): Unit = runBlocking {
        every { client.openCase(any()) } returns Uni.createFrom().failure(RuntimeException("connection refused"))
        assertInstanceOf<CaseOpenOutcome.Retryable>(adapter().openAmlAlertCase(partyId))
    }
}
