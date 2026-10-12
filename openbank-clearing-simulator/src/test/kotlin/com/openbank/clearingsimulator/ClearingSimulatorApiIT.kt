// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearingsimulator

import com.openbank.libs.iso20022.ChargeBearer
import com.openbank.libs.iso20022.CreditTransferInstruction
import com.openbank.libs.iso20022.Pacs008Builder
import com.openbank.libs.iso20022.SettlementMethod
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Boot smoke + functional IT (ADR-0104 D2). Proves the service boots, serves health/info, and that
 * the clearing endpoints round-trip a real pacs.008 into a pacs.002. Guards the
 * released-but-never-booted defect class (config/CDI bugs a unit test cannot catch).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ClearingSimulatorApiIT {

    private fun pacs008Xml(amount: String): String = Pacs008Builder().build(
        CreditTransferInstruction(
            messageId = "OB-MSG-0001",
            creationDateTime = OffsetDateTime.of(2026, 6, 22, 10, 15, 30, 0, ZoneOffset.UTC),
            interbankSettlementDate = OffsetDateTime.of(2026, 6, 22, 10, 15, 30, 0, ZoneOffset.UTC),
            endToEndId = "E2E-0001",
            transactionId = "TX-0001",
            amount = BigDecimal(amount),
            currency = "EUR",
            chargeBearer = ChargeBearer.SLEV,
            settlementMethod = SettlementMethod.CLRG,
            debtorName = "Alice Debtor",
            debtorIban = "DE89370400440532013000",
            debtorAgentBic = "COBADEFFXXX",
            creditorAgentBic = "BNPAFRPPXXX",
            creditorName = "Bob Creditor",
            creditorIban = "FR1420041010050500013M02606",
            remittanceInfo = "Invoice 2026-0042",
        ),
    )

    @Test
    fun `health ready is UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    fun `info reports the service name`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) })
            .extract().body().asString()
        assertThat(body).contains("openbank-clearing-simulator")
    }

    @Test
    @TestSecurity(user = "rail", roles = ["ROLE_API"])
    fun `submitting a pacs_008 returns an ACSC pacs_002`() {
        val body = (
            Given {
                contentType("application/xml")
                body(pacs008Xml("12.34"))
            } When {
                post("/api/v1/clearing/credit-transfers")
            } Then {
                statusCode(200)
            }
            ).extract().body().asString()

        assertThat(body).contains("urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10")
        assertThat(body).contains("<TxSts>ACSC</TxSts>")
    }

    @Test
    fun `submitting without authentication is rejected`() {
        Given {
            contentType("application/xml")
            body(pacs008Xml("12.34"))
        } When {
            post("/api/v1/clearing/credit-transfers")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "rail", roles = ["ROLE_API"])
    fun `lost response replays exact original verdict and changed request conflicts`() {
        val path = "/api/v1/clearing/receipted-credit-transfers/SEPA_INSTANT/lost-response-1"
        val first = Given {
            contentType("application/xml")
            body(pacs008Xml("12.34"))
        }
            .When { post(path) }.Then { statusCode(200) }.extract().body().asString()

        // Treat the original response as lost. The second HTTP call must recover exactly the
        // first committed XML, not recalculate a new report timestamp or verdict.
        val replay = Given {
            contentType("application/xml")
            body(pacs008Xml("12.34"))
        }
            .When { post(path) }.Then { statusCode(200) }.extract().body().asString()
        assertThat(replay).isEqualTo(first)

        Given {
            contentType("application/xml")
            body(pacs008Xml("99.99"))
        }
            .When { post(path) }.Then { statusCode(409) }

        val otherRail = Given {
            contentType("application/xml")
            body(pacs008Xml("99.99"))
        }
            .When { post(path.replace("SEPA_INSTANT", "SWIFT")) }
            .Then { statusCode(200) }.extract().body().asString()
        assertThat(otherRail).isNotEqualTo(first)
    }

    @Test
    @TestSecurity(user = "rail", roles = ["ROLE_API"])
    fun `concurrent same identity has one byte identical response`() {
        val path = "/api/v1/clearing/receipted-credit-transfers/SEPA_INSTANT/concurrent-1"
        Executors.newFixedThreadPool(4).use { pool ->
            val calls = (1..8).map {
                Callable {
                    Given {
                        contentType("application/xml")
                        body(pacs008Xml("12.34"))
                    }
                        .When { post(path) }.Then { statusCode(200) }.extract().body().asString()
                }
            }
            val responses = pool.invokeAll(calls).map { it.get() }
            assertThat(responses.toSet()).hasSize(1)
        }
    }
}
