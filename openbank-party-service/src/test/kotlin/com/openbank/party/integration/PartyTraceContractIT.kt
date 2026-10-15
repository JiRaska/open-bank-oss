// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.party.it.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * The observable distributed shape of the AML declaration below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT of the new
 * `party_aml_profiles` version, the UPDATE of the derived `parties` columns, and the INSERT of
 * PARTY_UPDATED into `party_outbox` happened *inside* the request's trace. The AML profile is
 * what PEP/FATCA/CRS screening reads downstream — a declaration that answers 200 while its
 * event escapes the request trace (or its derived columns land in a trace of their own) is a
 * fact the bank acted on with no observable causal link to the announcement of it.
 *
 * The party is seeded via JDBC rather than POST /api/v1/parties so the recorded window
 * contains exactly the one request under contract: party creation ALSO writes a
 * PARTY_CREATED row to `party_outbox`, and a same-named outbox INSERT from a second request's
 * trace would make `requiresSameTrace` compare across requests, which is not the claim.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_party_it")],
)
@TestProfile(TraceContractProfile::class)
class PartyTraceContractIT {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = EDGE),
        ],
    )
    fun `declaring an AML profile keeps its writes inside the request trace`() {
        val partyId = UUID.randomUUID()
        seedParty(partyId)

        Given {
            contentType("application/json")
            body(AML_PROFILE)
        } When {
            put("/api/v1/parties/$partyId/aml-profile")
        } Then {
            statusCode(200)
        }

        exporter.contract()
            .requiresSpan("PUT /api/v1/parties/{id}/aml-profile")
            .requiresAttribute("PUT /api/v1/parties/{id}/aml-profile", "http.response.status_code")
            .requiresAttribute("INSERT party_aml_profiles", "db.sql.table")
            .requiresAttribute("UPDATE parties", "db.sql.table")
            .requiresAttribute("INSERT party_outbox", "db.sql.table")
            .requiresSameTrace(
                "PUT /api/v1/parties/{id}/aml-profile",
                "INSERT party_aml_profiles",
                "UPDATE parties",
                "INSERT party_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("party-declare-aml-profile")
    }

    private fun seedParty(partyId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """insert into parties (party_id, party_type, legal_name, email)
                    values (?, 'INDIVIDUAL', 'Trace Probe', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, partyId)
                statement.setString(2, "trace-${UUID.randomUUID()}@example.cz")
                statement.executeUpdate()
            }
            if (!connection.autoCommit) connection.commit()
        }
    }

    companion object {
        private const val EDGE = "service-account-openbank-edge"

        private val AML_PROFILE = """
            {"purpose":["EVERYDAY_BANKING"],"incomeSources":["EMPLOYMENT"],"occupation":"EMPLOYED",
             "expectedMonthlyTurnover":"UP_TO_30K","cashIntensive":false,
             "pep":{"isPep":false},"taxResidencies":["CZ"],"usPerson":false,"truthful":true}
        """.trimIndent()
    }
}
