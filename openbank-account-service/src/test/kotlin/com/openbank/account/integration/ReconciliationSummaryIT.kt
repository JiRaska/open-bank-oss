// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.Instant
import java.util.UUID

/**
 * Verifies account-service's [ReconciliationSummaryContract] implementation (ADR-0026, Phase 1): the
 * endpoint is registered from the inherited interface @Path, the role gate is enforced (never open), and
 * the JSON projection has the shape the analytics-sink parses.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.account.it.PostgresRedpandaRedisTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ReconciliationSummaryIT {

    private val path = "/api/v1/analytics/reconciliation-summary"

    companion object {
        private var accountId: String? = null
        private lateinit var openedAfter: Instant
    }

    @Test
    @Order(1)
    fun `is role-gated - anonymous is rejected`() {
        Given { this } When { get(path) } Then { statusCode(401) }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "svc", roles = ["ROLE_VIEWER"])
    fun `is role-gated - a disallowed role is forbidden`() {
        Given { this } When { get(path) } Then { statusCode(403) }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "analytics-sink", roles = ["ROLE_API"])
    fun `the service-to-service caller gets the summary projection`() {
        Given { this } When {
            get(path)
        } Then {
            statusCode(200)
            body("service", equalTo("openbank-account-service"))
            body("generatedAt", notNullValue())
            body("countsByType", notNullValue())
            body("aggregates", notNullValue())
        }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `opens an account for the incremental-window check`() {
        openedAfter = Instant.now().minusSeconds(60)
        val payload = """
            {
              "partyId": "${UUID.randomUUID()}",
              "productId": "${UUID.randomUUID()}",
              "accountType": "CURRENT",
              "currencyCode": "CZK",
              "legalName": "Reconciliation Window Test"
            }
        """.trimIndent()
        accountId = Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(payload)
        } When {
            post("/api/v1/accounts")
        } Then {
            statusCode(201)
        } Extract {
            path("id")
        }
    }

    /**
     * Regression for the `@PreUpdate`/entity-default EPOCH timestamps: rows used to be INSERTed with
     * `updated_at = 1970-01-01` (the column DEFAULT never applies — Hibernate writes the entity value),
     * so a created-but-never-updated account fell outside every incremental `since` window and was
     * invisible to warehouse reconciliation. The repository now stamps both audit columns from the
     * injected Clock.
     */
    @Test
    @Order(5)
    @TestSecurity(user = "analytics-sink", roles = ["ROLE_API"])
    fun `a freshly opened account falls inside the incremental since-window`() {
        Given {
            queryParam("since", openedAfter.toString())
        } When {
            get(path)
        } Then {
            statusCode(200)
            body("aggregates.aggregateId", hasItem(accountId))
            body("watermark", notNullValue())
        }
    }
}
