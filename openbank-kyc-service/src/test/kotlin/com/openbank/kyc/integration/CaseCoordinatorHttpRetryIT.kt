// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.kyc.application.port.out.CaseOpenOutcome
import com.openbank.kyc.domain.model.ScreeningTriggerFailure
import com.openbank.kyc.infrastructure.client.CaseCoordinatorAdapter
import com.openbank.kyc.it.KycKafkaTestResource
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Actual REST client and injected CDI self proxy: retry counts cannot be proved with plain mocks. */
@QuarkusTest
@TestProfile(CaseCoordinatorHttpRetryIT.IsolatedRetryProfile::class)
@QuarkusTestResource(CaseCoordinatorHttpRetryIT.Stub::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(KycKafkaTestResource::class, restrictToAnnotatedClass = true)
class CaseCoordinatorHttpRetryIT {
    class IsolatedRetryProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "false",
            "openbank.outbox.dispatch-enabled" to "false",
            // Isolate the retry taxonomy from cross-case circuit-breaker state; @Retry stays enabled.
            "com.openbank.kyc.infrastructure.client.CaseCoordinatorAdapter/openWithResilience/CircuitBreaker/enabled" to
                "false",
        )
    }

    class Stub : QuarkusTestResourceLifecycleManager {
        private var server: HttpServer? = null

        override fun start(): Map<String, String> {
            val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            http.createContext("/api/v1/case-coordinator/cases") { exchange ->
                requests.add(String(exchange.requestBody.readAllBytes()))
                val body = if (status == 201) {
                    """{"caseId":"synthetic-case"}"""
                } else {
                    """{"error":"synthetic-private-provider-body"}"""
                }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            http.start()
            server = http
            val url = "http://127.0.0.1:${http.address.port}"
            return mapOf(
                "quarkus.rest-client.case-coordinator.url" to url,
                "openbank.kyc.case-coordinator.url" to url,
            )
        }

        override fun stop() {
            server?.stop(0)
        }

        companion object {
            val requests = CopyOnWriteArrayList<String>()

            @Volatile var status = 201
        }
    }

    @Inject
    lateinit var adapter: CaseCoordinatorAdapter

    private val partyId = UUID.fromString("00000000-0000-0000-0000-000000000025")

    @Test
    fun `201 opens a case with one actual request`() {
        val outcome = call(201)
        assertThat(outcome).isEqualTo(CaseOpenOutcome.Opened("synthetic-case"))
        assertRequests(1)
    }

    @Test
    fun `409 duplicate is AlreadyExists without retry`() {
        assertThat(call(409)).isEqualTo(CaseOpenOutcome.AlreadyExists)
        assertRequests(1)
    }

    @Test
    fun `400 is rejected without retry`() {
        assertRejected(call(400), 400)
        assertRequests(1)
    }

    @Test
    fun `403 is rejected without retry`() {
        assertRejected(call(403), 403)
        assertRequests(1)
    }

    @Test
    fun `429 uses the existing three actual attempts then returns bounded retryable detail`() {
        assertThat(call(429)).isEqualTo(CaseOpenOutcome.Retryable("HTTP 429"))
        assertRequests(3)
    }

    @Test
    fun `503 uses the existing three actual attempts then returns bounded retryable detail`() {
        assertThat(call(503)).isEqualTo(CaseOpenOutcome.Retryable("HTTP 503"))
        assertRequests(3)
    }

    private fun call(status: Int): CaseOpenOutcome {
        Stub.requests.clear()
        Stub.status = status
        val outcome = runBlocking { adapter.openAmlAlertCase(partyId) }
        println(
            "COORDINATOR_HTTP_CONTROL status=$status attempts=${Stub.requests.size} " +
                "outcome=${outcome.javaClass.simpleName}",
        )
        return outcome
    }

    private fun assertRejected(outcome: CaseOpenOutcome, status: Int) {
        assertThat(outcome).isEqualTo(
            CaseOpenOutcome.Rejected(ScreeningTriggerFailure.COORDINATOR_REJECTED, "HTTP $status"),
        )
    }

    private fun assertRequests(count: Int) {
        assertThat(Stub.requests).hasSize(count)
        for (request in Stub.requests) {
            val body = jacksonObjectMapper().readTree(request)
            assertThat(body.path("subjectRef").asText()).isEqualTo(partyId.toString())
            assertThat(body.path("caseClass").asText()).isEqualTo("AML_ALERT")
            assertThat(body.path("openedBy").asText()).isEqualTo("kyc-service")
            assertThat(body.path("dispositionTarget").asText()).isEqualTo("kyc-rescreen")
        }
    }
}
