// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.pid.it.PostgresTestResource
import com.openbank.pid.it.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

/**
 * The observable distributed shape of an OpenID4VCI credential offer (ADR-0094).
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres write; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT of the offer row into
 * `eudi_credential_offer` happened *inside* the offer request's trace. An offer persisted
 * outside the request context (a retry on another thread, a detached session) still answers
 * 200 and hands back a well-formed pre-authorized code, but lands in traces of its own — the
 * wallet redemption that follows would reference an offer with no observable issuer.
 */
@QuarkusTest
@QuarkusTestResource(PidCredentialOfferTraceContractIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(EudiCredentialPersistenceIT.EudiIssuerKeyResource::class)
@TestProfile(TraceContractProfile::class)
class PidCredentialOfferTraceContractIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("party-events-out", "pid-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "eudi-it", roles = ["ROLE_OPERATOR"])
    fun `creating a credential offer keeps the request and its offer INSERT in one trace`() {
        Given {
            contentType("application/json")
            body("""{"subjectId":"CZ-TRACE-1","givenName":"Eva","familyName":"Durable","birthdate":"1988-08-08"}""")
        } When {
            post("/api/v1/parties/eudi/credential-offers")
        } Then {
            statusCode(200)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/parties/eudi/credential-offers")
            .requiresAttribute("POST /api/v1/parties/eudi/credential-offers", "http.response.status_code")
            .requiresAttribute("INSERT eudi_credential_offer", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/parties/eudi/credential-offers",
                "INSERT eudi_credential_offer",
            )
            .hasNoErrorSpan()
            .verifiedAs("pid-credential-offer")
    }
}
