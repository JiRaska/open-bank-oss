// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasEntry
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The CRS/FATCA TIN boundary over real HTTP (ADR-0340 B1): the in-code verified-identity guard,
 * the 404 for a party that never self-certified, and the TIN-carrying body for the one admitted
 * caller. The rego twin of this gate is `service-tax-reporting-party-tax-facts`
 * (party_rest_ext.rego) and the unit side is pinned in party_rest_ext_test.rego; this IT is the
 * third leg — what the running service actually serves.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_party_it")],
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PartyTaxReportingApiIT {

    companion object {
        const val EDGE = "service-account-openbank-edge"
        const val TAX_REPORTING = "service-account-openbank-tax-reporting"
        private var personId: UUID? = null

        val FOREIGN_RESIDENT = """
            {"purpose":["SAVINGS"],"incomeSources":["EMPLOYMENT"],
             "occupation":"EMPLOYED","expectedMonthlyTurnover":"UP_TO_30K","cashIntensive":false,
             "pep":{"isPep":false},"taxResidencies":["CZ","DE","US"],"tin":{"DE":"DE-123","US":"US-456"},
             "usPerson":true,"truthful":true}
        """.trimIndent()
    }

    private fun createPerson(): UUID {
        val body = """
            {"partyType":"INDIVIDUAL","legalName":"TIN Boundary Probe","tradingName":null,
             "dateOfBirth":"1990-01-01","nationality":"CZ","taxId":null,
             "email":"tin-boundary-${UUID.randomUUID()}@example.cz","phone":null,"address":null}
        """.trimIndent()
        val id = Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(body)
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getString("id")
        }
        return UUID.fromString(id)
    }

    private fun declareProfile(id: UUID) = Given {
        contentType("application/json")
        body(FOREIGN_RESIDENT)
    } When {
        put("/api/v1/parties/$id/aml-profile")
    } Then {
        statusCode(200)
    }

    private fun taxFacts(id: UUID, status: Int) = Given { this } When {
        get("/api/v1/reporting/parties/$id/tax-facts")
    } Then {
        statusCode(status)
    }

    @Test
    @Order(1)
    fun `an anonymous caller is refused before the route runs`() {
        taxFacts(UUID.randomUUID(), 401)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "u-view", roles = ["ROLE_VIEWER"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "viewer-subject"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "u-view"),
        ],
    )
    fun `a staff viewer is refused even though staff read the AML profile`() {
        taxFacts(UUID.randomUUID(), 403)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "service-account-openbank-mcp-service", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "mcp-sa-subject"),
            Claim(key = "azp", value = "openbank-mcp-service"),
            Claim(key = "preferred_username", value = "service-account-openbank-mcp-service"),
        ],
    )
    fun `another ROLE_API service account is refused`() {
        taxFacts(UUID.randomUUID(), 403)
    }

    @Test
    @Order(4)
    @TestSecurity(user = "not-matching-username", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "spoof-sa-subject"),
            Claim(key = "azp", value = "openbank-tax-reporting"),
            Claim(key = "preferred_username", value = "not-matching-username"),
        ],
    )
    fun `a token whose azp does not match its service-account username is refused`() {
        // #12448: the name alone must never open the boundary — only the verified binding does.
        taxFacts(UUID.randomUUID(), 403)
    }

    @Test
    @Order(5)
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = EDGE),
        ],
    )
    fun `the edge creates the probe person and declares a foreign-resident profile`() {
        val id = createPerson()
        personId = id
        declareProfile(id).body("version", equalTo(1))
            .body("crsStatus", equalTo("REPORTABLE"))
            .body("fatcaStatus", equalTo("US_PERSON"))
    }

    @Test
    @Order(6)
    @TestSecurity(user = TAX_REPORTING, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "tax-reporting-sa-subject"),
            Claim(key = "azp", value = "openbank-tax-reporting"),
            Claim(key = "preferred_username", value = TAX_REPORTING),
        ],
    )
    fun `the tax-reporting service account reads the residencies with their TINs`() {
        val id = requireNotNull(personId)
        taxFacts(id, 200)
            .body("partyId", equalTo(id.toString()))
            .body("holderName", equalTo("TIN Boundary Probe"))
            .body("crsStatus", equalTo("REPORTABLE"))
            .body("fatcaStatus", equalTo("US_PERSON"))
            .body(
                "taxResidencies",
                hasItem(hasEntry("country", "DE")),
            )
            .body(
                "taxResidencies",
                hasItem(allOf(hasEntry("country", "US"), hasEntry("tin", "US-456"))),
            )
    }
}
