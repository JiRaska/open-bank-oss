// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.Matchers
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.xml.PactXmlBuilder
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.openbank.libs.iso20022.ChargeBearer
import com.openbank.libs.iso20022.CreditTransferInstruction
import com.openbank.libs.iso20022.Pacs002Reader
import com.openbank.libs.iso20022.Pacs008Builder
import com.openbank.libs.iso20022.PaymentStatus
import com.openbank.libs.iso20022.SettlementMethod
import com.openbank.sepa.infrastructure.client.ClearingSimulatorClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * SEPA credit transfer pacs.008 submission to the in-repo clearing simulator (#8345).
 * The request uses the production ISO builder; the response is parsed by the production reader.
 * The literal expected path is independent of client annotations, so endpoint drift fails here.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-clearing-simulator", pactVersion = PactSpecVersion.V3)
class ClearingSimulatorPactConsumerTest {

    private val requestXml = Pacs008Builder().build(
        CreditTransferInstruction(
            messageId = "MSG-SCT-PACT-001",
            creationDateTime = OffsetDateTime.of(2026, 1, 20, 10, 15, 30, 0, ZoneOffset.UTC),
            interbankSettlementDate = OffsetDateTime.of(2026, 1, 20, 10, 15, 30, 0, ZoneOffset.UTC),
            endToEndId = "SCT-PACT-001",
            transactionId = null,
            amount = BigDecimal("12.34"),
            currency = "EUR",
            chargeBearer = ChargeBearer.SLEV,
            settlementMethod = SettlementMethod.CLRG,
            debtorName = "Alice Debtor",
            debtorIban = "DE89370400440532013000",
            debtorAgentBic = "GIBACZPX",
            creditorAgentBic = "DEUTDEFF",
            creditorName = "Bob Creditor",
            creditorIban = "GB33BUKB20201555555555",
            remittanceInfo = "Invoice 1",
        ),
    )

    @Pact(consumer = "openbank-sepa-payment", provider = "openbank-clearing-simulator")
    fun submitCreditTransferPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the clearing simulator is available")
        .uponReceiving("POST SEPA credit transfer pacs.008 credit transfer")
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/xml"))
        .body(requestXml, "application/xml")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/xml"))
        .body(
            PactXmlBuilder("Document").build { root ->
                root.setAttributes(mapOf("xmlns" to "urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10"))
                root.appendElement("FIToFIPmtStsRpt", emptyMap<String, Any?>()) { rpt ->
                    rpt.appendElement("GrpHdr", emptyMap<String, Any?>()) { grpHdr ->
                        // Both are simulator-derived, not literal echoes of the request: MsgId is
                        // "SIM-STS-" + the submitted endToEndId (ClearingSimulatorService.clear),
                        // and CreDtTm is Instant.now(clock).toString() — variable fractional-second
                        // digit count, not a fixed-width offset — so both need a matcher, not an
                        // exact value; a stricter fixed-format timestamp matcher failed real
                        // provider verification on this (Instant.toString() isn't ISO_OFFSET_DATE_TIME).
                        grpHdr.appendElement(
                            "MsgId",
                            emptyMap<String, Any?>(),
                            Matchers.regexp("SIM-STS-SCT-PACT-001", "SIM-STS-SCT-PACT-001"),
                        )
                        grpHdr.appendElement(
                            "CreDtTm",
                            emptyMap<String, Any?>(),
                            Matchers.regexp(
                                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z",
                                "2026-01-20T10:15:30.123456Z",
                            ),
                        )
                    }
                    rpt.appendElement("TxInfAndSts", emptyMap<String, Any?>()) { tx ->
                        tx.appendElement("OrgnlEndToEndId", emptyMap<String, Any?>(), "SCT-PACT-001")
                        tx.appendElement("TxSts", emptyMap<String, Any?>(), "ACSC")
                    }
                }
            },
        )
        .toPact()

    @Pact(consumer = "openbank-sepa-payment", provider = "openbank-clearing-simulator")
    fun unauthenticatedTransferPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("POST SEPA credit transfer pacs.008 without M2M identity is refused")
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/xml"))
        .body(requestXml, "application/xml")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "submitCreditTransferPact")
    fun `a settled pacs_002 binds through the rail reader`(mockServer: MockServer) {
        assertThat(clientPath()).isEqualTo(EXPECTED_PATH)
        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/xml")
            .body(requestXml)
            .post(clientPath())
            .then()
            .statusCode(200)
            .extract().body().asString()

        val status = Pacs002Reader().read(body)
        assertThat(status.originalEndToEndId).isEqualTo("SCT-PACT-001")
        assertThat(status.status).isEqualTo(PaymentStatus.ACSC)
    }

    @Test
    @PactTestFor(pactMethod = "unauthenticatedTransferPact")
    fun `a submission without M2M identity is refused`(mockServer: MockServer) {
        assertThat(clientPath()).isEqualTo(EXPECTED_PATH)
        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/xml")
            .body(requestXml)
            .post(clientPath())
            .then()
            .statusCode(401)
    }

    private fun clientPath(): String {
        val type = ClearingSimulatorClient::class.java
        val base = type.getAnnotation(Path::class.java)?.value.orEmpty()
        val method = type.declaredMethods.single { it.name == "submitCreditTransfer" }
        return base + method.getAnnotation(Path::class.java).value
    }

    private companion object {
        const val EXPECTED_PATH = "/api/v1/clearing/credit-transfers"
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
    }
}
