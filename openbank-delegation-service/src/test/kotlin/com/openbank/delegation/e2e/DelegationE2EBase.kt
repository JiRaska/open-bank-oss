// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.e2e

import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.sql.DriverManager
import java.util.UUID

/**
 * Shared harness for the ADR-0232 delegation E2E suites: the profile-local port stubs (see
 * [E2EStubs]), the JDBC readers that assert durable state directly, and the HTTP builders both
 * halves of the journey use. Splitting the journeys across two concrete classes keeps each one
 * inside the complexity budget; the harness lives here exactly once.
 */
abstract class DelegationE2EBase {

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    @jakarta.inject.Inject
    lateinit var scaStub: E2EScaChallengeClient

    @jakarta.inject.Inject
    lateinit var eligibilityStub: E2EPartyEligibilityClient

    @jakarta.inject.Inject
    lateinit var authorityStub: E2EGrantorAuthorityClient

    @jakarta.inject.Inject
    lateinit var ownershipStub: E2EResourceOwnershipClient

    // ----------------------------------------------------------------------------------------------
    // HTTP builders
    // ----------------------------------------------------------------------------------------------

    @Suppress("LongParameterList")
    protected fun offerSpec(
        grantor: UUID,
        grantee: UUID,
        resourceId: UUID,
        scaSessionId: UUID,
        capabilities: List<String> = EXECUTION_CAPABILITIES,
        limitClause: String = EXECUTION_LIMITS_CLAUSE,
        requestId: String? = null,
        customer: UUID? = grantor,
    ): RequestSpecification {
        var spec = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(offerBody(grantor, grantee, resourceId, scaSessionId, capabilities, limitClause))
        if (customer != null) {
            spec = spec
                .header("X-Customer-Party-Id", customer.toString())
                .header("X-Customer-Actor-Party-Id", customer.toString())
        }
        if (requestId != null) {
            spec = spec.header("X-Request-ID", requestId)
        }
        return spec
    }

    private fun offerBody(
        grantor: UUID,
        grantee: UUID,
        resourceId: UUID,
        scaSessionId: UUID,
        capabilities: List<String>,
        limitClause: String,
    ): String {
        val caps = capabilities.joinToString(",") { "\"$it\"" }
        return """
            {
              "grantorPartyId": "$grantor",
              "granteePartyId": "$grantee",
              "resourceType": "ACCOUNT",
              "resourceId": "$resourceId",
              "capabilities": [$caps],
              "approvalPolicy": "SOLO",
              "grantScaSessionId": "$scaSessionId"$limitClause
            }
        """.trimIndent()
    }

    protected fun previewBody(grantor: UUID, grantee: UUID, resourceId: UUID): String = """
        {
          "grantorPartyId": "$grantor",
          "granteePartyId": "$grantee",
          "resourceType": "ACCOUNT",
          "resourceId": "$resourceId",
          "capabilities": ["ACCOUNT_INITIATE_PAYMENT"],
          "approvalPolicy": "SOLO",
          "dailyLimit": {"amount": 5000, "currency": "CZK"},
          "monthlyLimit": {"amount": 50000, "currency": "CZK"}
        }
    """.trimIndent()

    protected fun readOnlyCapabilities(): List<String> = listOf("ACCOUNT_VIEW_DETAILS")

    protected fun offerAndGetId(
        grantor: UUID,
        grantee: UUID,
        resourceId: UUID,
        capabilities: List<String> = readOnlyCapabilities(),
        limitClause: String = "",
    ): UUID {
        val sca = UUID.randomUUID()
        scaStub.seed(sca, grantor, SCA_PURPOSE_GRANT)
        val offered = offerSpec(grantor, grantee, resourceId, sca, capabilities, limitClause)
            .post("/api/v1/delegations")
            .then()
            .statusCode(201)
            .extract()
        return UUID.fromString(offered.jsonPath().getString("id"))
    }

    protected fun activate(id: UUID, grantee: UUID) {
        val acceptSca = UUID.randomUUID()
        scaStub.seed(acceptSca, grantee, SCA_PURPOSE_ACCEPT)
        assertThat(acceptCall(id, grantee, grantee, acceptSca).statusCode()).isEqualTo(200)
    }

    protected fun acceptCall(
        id: UUID,
        caller: UUID,
        granteeParam: UUID,
        scaSessionId: UUID?,
    ): ExtractableResponse<Response> {
        var spec = RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", caller.toString())
            .queryParam("granteePartyId", granteeParam.toString())
        if (scaSessionId != null) {
            spec = spec.queryParam("scaSessionId", scaSessionId.toString())
        }
        return spec.post("/api/v1/delegations/$id/accept").then().extract()
    }

    protected fun check(
        grantee: UUID,
        resourceId: UUID,
        capability: String,
        amount: Int?,
        currency: String?,
    ): Boolean = checkDenied(grantee, resourceId, capability, amount, currency)
        .jsonPath()
        .getBoolean("granted")

    protected fun checkDenied(
        grantee: UUID,
        resourceId: UUID,
        capability: String,
        amount: Int?,
        currency: String?,
    ): ExtractableResponse<Response> {
        val amountClause = if (amount == null) {
            ""
        } else {
            ",\"amount\":{\"amount\":$amount,\"currency\":\"$currency\"}"
        }
        val body = """
            {"granteePartyId":"$grantee","resourceType":"ACCOUNT","resourceId":"$resourceId",
             "capability":"$capability"$amountClause}
        """.trimIndent()
        return RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", grantee.toString())
            .body(body)
            .post("/api/v1/delegations/check")
            .then()
            .statusCode(200)
            .extract()
    }

    // ----------------------------------------------------------------------------------------------
    // JDBC readers — durable state the API does not expose back
    // ----------------------------------------------------------------------------------------------

    protected fun grantCountFor(grantor: UUID, grantee: UUID): Long =
        DriverManager.getConnection(jdbcUrl, JDBC_USER, JDBC_PASSWORD).use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM delegation_grants WHERE grantor_party_id = ? AND grantee_party_id = ?",
            ).use { statement ->
                statement.setObject(1, grantor)
                statement.setObject(2, grantee)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }

    protected fun outboxEventsFor(aggregateId: UUID, eventType: String): Int =
        DriverManager.getConnection(jdbcUrl, JDBC_USER, JDBC_PASSWORD).use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM delegation_outbox WHERE aggregate_id = ? AND event_type = ?",
            ).use { statement ->
                statement.setObject(1, aggregateId)
                statement.setObject(2, eventType)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }

    companion object {
        // Mirror of DelegationService's private companion ceremony purposes; kept in sync with
        // SCA_PURPOSE_GRANT / SCA_PURPOSE_ACCEPT there.
        const val SCA_PURPOSE_GRANT = "DELEGATION_GRANT"
        const val SCA_PURPOSE_ACCEPT = "DELEGATION_ACCEPT"

        /** The money-moving capability and its mandatory cumulative ceilings (ADR-0249 D5). */
        val EXECUTION_CAPABILITIES = listOf("ACCOUNT_INITIATE_PAYMENT")
        const val EXECUTION_LIMITS_CLAUSE = """,
              "dailyLimit": {"amount": 5000, "currency": "CZK"},
              "monthlyLimit": {"amount": 50000, "currency": "CZK"}"""

        const val EDGE_PRINCIPAL = "service-account-openbank-edge"
        const val EDGE_SUB = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"
        const val OPERATOR_PRINCIPAL = "service-account-openbank-services"
        const val JDBC_USER = "openbank"
        const val JDBC_PASSWORD = "openbank_secret"
        const val LIMIT_1000_CZK = """,
              "perTransactionLimit": {"amount": 1000, "currency": "CZK"}"""
    }
}
