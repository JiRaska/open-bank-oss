// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.integration

import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.openbank.sepa.application.port.out.SchemeGatewayPort
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * ADR-0104 D3 cross-service IT: drives the REAL [SchemeGatewayPort] (the CDI-wired
 * [com.openbank.sepa.infrastructure.client.SchemeGatewayAdapter]) over real HTTP against a stubbed
 * clearing simulator. Proves the wire contract the mocked unit test cannot: the adapter's
 * REST-client builds a real `pacs.008`, negotiates `application/xml`, attaches the oidc-client
 * bearer, and parses the genuine `pacs.002` the stub returns — advancing the verdict end to end.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.sepa.it.PostgresRedisTestResource::class)
@QuarkusTestResource(ClearingSimulatorWireMockResource::class)
class SchemeGatewaySimulatorIT {

    @Inject
    lateinit var schemeGateway: SchemeGatewayPort

    private fun payment() = SepaPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "it-${UUID.randomUUID()}",
        type = SepaPaymentType.SCT,
        status = SepaPaymentStatus.VALIDATED,
        debtorAccountId = UUID.randomUUID(),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice Debtor",
        creditorIban = "FR1420041010050500013M02606",
        creditorName = "Bob Creditor",
        creditorBic = "BNPAFRPPXXX",
        amount = BigDecimal("12.34"),
        currency = "EUR",
        remittanceInfo = "Invoice IT-1",
        endToEndId = "E2E-IT-0001",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = Instant.now(),
        completedAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    fun `submits a real pacs_008 over HTTP and accepts the ACSC response`() {
        ClearingSimulatorWireMockResource.stubSettle()

        val outcome = runBlocking { schemeGateway.submit(payment()) }

        assertThat(outcome.accepted).isTrue()
        assertThat(outcome.reasonCode).isNull()
        // Prove a genuine pacs.008 actually went on the wire to the simulator.
        ClearingSimulatorWireMockResource.server.verify(
            postRequestedFor(urlEqualTo(ClearingSimulatorWireMockResource.CREDIT_TRANSFERS_PATH))
                .withRequestBody(containing("urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"))
                .withRequestBody(containing("FR1420041010050500013M02606")),
        )
    }

    @Test
    fun `maps an RJCT response from the scheme to a rejected outcome`() {
        ClearingSimulatorWireMockResource.stubReject("AC04")

        val outcome = runBlocking { schemeGateway.submit(payment()) }

        assertThat(outcome.accepted).isFalse()
        assertThat(outcome.reasonCode).isEqualTo("AC04")
    }
}
