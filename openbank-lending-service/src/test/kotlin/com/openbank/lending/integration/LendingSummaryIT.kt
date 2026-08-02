// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import java.util.UUID

/**
 * The aggregate endpoints (issue #3294) against a real Postgres.
 *
 * WHY A CONTAINER AND NOT A MOCK
 * The value of these endpoints is a `GROUP BY` that runs. `LendingSummaryFoldTest` covers the
 * arithmetic on rows; nothing but a real database proves the HQL parses, that `min(createdAt)` and
 * `sum(requestedAmount)` come back in the types the fold expects, and that the enum-typed `status`
 * column groups at all. A repository mock would agree with any query string, including one that
 * does not compile — which is precisely the class of defect that only shows up in production.
 *
 * Driven through the REST endpoints on purpose: a direct CDI call into a `@WithSession` reactive
 * repository from the bare test thread fails with "No current Vertx context found" — only a real
 * HTTP request carries one. Same reasoning as `LendingOutboxWriteIT`.
 *
 * TWO CURRENCIES ON PURPOSE. A fixture with one currency would pass against an implementation that
 * sums CZK and EUR into a single meaningless figure, which is the whole reason money is returned
 * per currency.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@QuarkusTestResource(LendingSummaryIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class LendingSummaryIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("lending-events-out").toMutableMap()
            props["quarkus.kafka.devservices.enabled"] = "false"
            props["openbank.outbox.dispatch-enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    private fun applyFor(amount: String, currency: String) {
        val body = """
            {"partyId":"${UUID.randomUUID()}","requestedAmount":{"amount":"$amount","currency":{"code":"$currency"}},
            "nominalAnnualRate":0.05,"termPeriods":12,"firstDueDate":"${LocalDate.now().plusMonths(1)}"}
        """.trimIndent()
        Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/lending/applications")
        } Then {
            statusCode(201)
        }
    }

    @Test
    @Order(1)
    @TestSecurity(user = "summary-it-proposer", roles = ["ROLE_LENDING_OFFICER"])
    fun `1 - seed applications in two currencies`() {
        applyFor("10000.00", "EUR")
        applyFor("20000.00", "EUR")
        applyFor("500000.00", "CZK")
    }

    @Test
    @Order(2)
    @TestSecurity(user = "summary-it-reader", roles = ["ROLE_CREDIT_RISK"])
    fun `2 - the summary counts the whole book and keeps currencies apart`() {
        val response = Given {
            contentType("application/json")
        } When {
            get("/api/v1/lending/applications/summary")
        } Then {
            statusCode(200)
        } Extract {
            this
        }

        val states = response.jsonPath().getList<String>("status")
        assertThat(states).contains("SUBMITTED")

        val i = states.indexOf("SUBMITTED")
        assertThat(response.jsonPath().getLong("count[$i]")).isEqualTo(3L)

        // The whole point: two entries, not one summed figure.
        val currencies = response.jsonPath().getList<String>("requested[$i].currency")
        assertThat(currencies).containsExactlyInAnyOrder("CZK", "EUR")

        val eur = response.jsonPath().getList<Map<String, Any>>("requested[$i]")
            .first { it["currency"] == "EUR" }["amount"].toString().toBigDecimal()
        assertThat(eur).isEqualByComparingTo("30000.00")

        // An aggregate must not inherit the list cap; `oldestCreatedAt` is what a desk sorts by.
        assertThat(response.jsonPath().getString("oldestCreatedAt[$i]")).isNotBlank()
    }

    @Test
    @Order(3)
    @TestSecurity(user = "summary-it-reader", roles = ["ROLE_VIEWER"])
    fun `3 - the loan summary answers even with an empty book`() {
        // An empty book is a legitimate answer (200 + []), not an error and not a fabricated zero
        // row — the console distinguishes "nothing here" from "could not read".
        Given {
            contentType("application/json")
        } When {
            get("/api/v1/lending/loans/summary")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "summary-it-outsider", roles = ["ROLE_CUSTOMER"])
    fun `4 - a role outside the backoffice matrix is refused`() {
        // The aggregate exposes book-wide exposure, so it must not be reachable by anyone the list
        // endpoints would refuse.
        Given {
            contentType("application/json")
        } When {
            get("/api/v1/lending/applications/summary")
        } Then {
            statusCode(403)
        }
    }
}
