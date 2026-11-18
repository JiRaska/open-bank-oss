// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.integration

import com.openbank.customeredge.infrastructure.webauthn.EnrollmentTicketService
import com.openbank.customeredge.it.RedpandaRedisTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The observable distributed shape of the WebAuthn registration challenge issue (ADR-0066 F2).
 *
 * A successful `register/begin` persists a fresh challenge to the Redis-backed [ChallengeStore]
 * before the HTTP caller is answered. The spans asserted here are the service's OWN — Quarkus'
 * server instrumentation produces the span for the real HTTP request, and the Redis client's
 * OpenTelemetry instrumentation produces the span for the real `SET`; [RecordingSpanExporter]
 * only adds an in-memory `SpanProcessor` so a contract can read what an OTLP collector would
 * receive. Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the SET that actually stored the challenge
 * happened *inside* the trace of the HTTP request that asked for it. A write that escaped the
 * request context — a fire-and-forget executor, a thread hop that dropped the context — still
 * stores the challenge and still answers 200, is invisible to every functional assertion this
 * module already had, and lands in a trace of its own. For a WebAuthn challenge that store write
 * IS the server-side half of replay protection: a challenge the edge cannot trace back to the
 * request that issued it is a ceremony an operator cannot audit.
 *
 * customer-edge has no database of its own (no Flyway migrations) — Redis is its durable write
 * surface, and the WebAuthn challenge append is the most security-relevant one. This module had
 * zero trace evidence before this test (the evidence matrix lists trace as a gap); it became
 * assertable only once the service carried the OpenTelemetry extension (before that, the
 * service emitted no spans at all — the same defect class as finrep-service).
 */
@QuarkusTest
@TestProfile(CustomerEdgeTraceContractIT.CustomerEdgeTraceProfile::class)
class CustomerEdgeTraceContractIT {

    class CustomerEdgeTraceProfile : TraceContractProfile() {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(RedpandaRedisTestResource::class.java),
        )

        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            // EnrollmentTicketService rejects every ticket without a configured secret.
            "openbank.webauthn.enrollment-ticket-secret" to "trace-contract-enrollment-secret",
            // Pin the sampler, not the default parentbased_always_on: with a parent-based sampler
            // the whole contract becomes a function of the incoming traceparent's sampled flag
            // (a flag the test does not control), and an unsampled parent legitimately yields
            // zero spans. The contract asserts span presence and context flow, not sampling
            // policy — sampling policy is the deployment's concern.
            "quarkus.otel.traces.sampler" to "always_on",
        )
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Inject
    lateinit var tickets: EnrollmentTicketService

    @Inject
    lateinit var redis: RedisDataSource

    @Test
    fun `issuing a WebAuthn registration challenge keeps the request and its SET in one trace`() {
        val partyId = UUID.randomUUID().toString()
        val ticket = tickets.issue(partyId)

        val challenge = Given {
            header("Authorization", "Bearer $ticket")
            // The resource is @Consumes(APPLICATION_JSON); an empty form-urlencoded request 415s.
            contentType("application/json")
        } When {
            post("/customer/v1/webauthn/register/begin")
        } Then {
            statusCode(200)
        } Extract {
            body().jsonPath().getString("challenge")
        }

        assertThat(challenge).describedAs("challenge in the register/begin response").isNotBlank()
        awaitChallenge(challenge)

        exporter.contract()
            .requiresSpan("POST /customer/v1/webauthn/register/begin")
            // The Redis client instrumentation names spans "<command> <db-index>" — valkey db 0
            // here; asserting the SET anchor (lowercase, with the db index) is what an OTLP
            // collector would receive for this exact write.
            .requiresSpan("set 0")
            .requiresSameTrace("POST /customer/v1/webauthn/register/begin", "set 0")
            .hasNoErrorSpan()
            .verifiedAs("customeredge-webauthn-challenge-issue")
    }

    private fun awaitChallenge(challenge: String) {
        val values = redis.value(String::class.java)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (values.get("edge:webauthn:challenge:$challenge") == null && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        assertThat(values.get("edge:webauthn:challenge:$challenge"))
            .describedAs("Redis challenge entry for the issued registration challenge")
            .isEqualTo("registration")
    }
}
