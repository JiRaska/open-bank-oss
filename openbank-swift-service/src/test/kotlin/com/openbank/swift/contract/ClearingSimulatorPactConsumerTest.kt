// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.contract

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
import com.openbank.libs.iso20022.Pacs008Builder
import com.openbank.libs.iso20022.SettlementMethod
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Consumer-driven contract for swift-service's inbound-to-clearing submission
 * ([com.openbank.swift.infrastructure.client.SchemeGatewayAdapter.submit], ADR-0104 D2/D4,
 * issue #468 edge 4 — "clearing inbound"). Unlike edges 1-3, this is an HTTP contract with an XML
 * body, not JSON, and unlike the issue's own "message pact" framing, the transport is synchronous
 * REST, not Kafka: `ClearingSimulatorClient.submitCreditTransfer` is a plain `@RegisterRestClient`
 * `POST`. swift-service is the CONSUMER (it depends on the simulator's request/response shape);
 * `openbank-clearing-simulator` is the PROVIDER — a real internal service (a non-production
 * counterparty simulator, not the actual SWIFT/EBA network), so provider verification is exactly
 * as meaningful here as it was for ledger-service in edges 2-3.
 *
 * The request is built via the real [Pacs008Builder] (same production code
 * [com.openbank.swift.infrastructure.client.SchemeGatewayAdapter] uses) with fixed/deterministic
 * field values — faithful to production shape without hand-crafting XML that could silently drift
 * from what the builder actually emits. The response can't be exact-matched: the simulator stamps
 * `CreDtTm` with the live clock (`ClearingSimulatorService.clear`'s `now` parameter), so
 * `CreDtTm` uses a timestamp matcher via [PactXmlBuilder] — this is the first XML/matcher pact in
 * the repo (every prior contract is JSON).
 *
 * Found and fixed alongside this contract: [SchemeGatewayAdapter]'s `instruction()` mapping
 * defaulted `debtorName`/`debtorIban` to `""` when a SWIFT message's `orderingCustomerName`/
 * `orderingCustomerAccount` were null (both legitimately nullable) — an empty IBAN/name violates
 * the pacs.008 XSD, and the resulting `check()` failure threw a raw `IllegalStateException` before
 * the class's own fail-closed `try`/`catch`, not the documented `SchemeGatewayUnavailableException`.
 * That's a domain-mapping bug a wire-contract test wouldn't itself exercise (this contract uses a
 * normal, fully-populated instruction); it's regression-guarded separately in
 * `SchemeGatewayAdapterTest`.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-clearing-simulator", pactVersion = PactSpecVersion.V3)
class ClearingSimulatorPactConsumerTest {

    private val requestXml = Pacs008Builder().build(
        CreditTransferInstruction(
            messageId = "SWIFT-TRX-PACT-001",
            creationDateTime = OffsetDateTime.of(2026, 1, 20, 10, 15, 30, 0, ZoneOffset.UTC),
            interbankSettlementDate = OffsetDateTime.of(2026, 1, 20, 10, 15, 30, 0, ZoneOffset.UTC),
            endToEndId = "TRX-PACT-001",
            transactionId = null,
            amount = BigDecimal("12.34"),
            currency = "EUR",
            chargeBearer = ChargeBearer.SHAR,
            settlementMethod = SettlementMethod.COVE,
            debtorName = "Alice Debtor",
            debtorIban = "DE89370400440532013000",
            debtorAgentBic = "GIBACZPX",
            creditorAgentBic = "DEUTDEFF",
            creditorName = "Bob Creditor",
            creditorIban = "GB33BUKB20201555555555",
            remittanceInfo = "Invoice 1",
        ),
    )

    @Pact(consumer = "openbank-swift-service", provider = "openbank-clearing-simulator")
    fun submitCreditTransferPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the clearing simulator is available")
        .uponReceiving("POST a pacs.008 credit transfer converted from an MT103")
        .path("/api/v1/clearing/credit-transfers")
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
                            Matchers.regexp("SIM-STS-.+", "SIM-STS-TRX-PACT-001"),
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
                        tx.appendElement("OrgnlEndToEndId", emptyMap<String, Any?>(), "TRX-PACT-001")
                        tx.appendElement("TxSts", emptyMap<String, Any?>(), "ACSC")
                    }
                }
            },
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "submitCreditTransferPact")
    fun `submitCreditTransfer returns an ACSC pacs_002 for a well-formed pacs_008`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/xml")
            .body(requestXml)
            .post("/api/v1/clearing/credit-transfers")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertThat(body).contains("urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10")
        assertThat(body).contains("<TxSts>ACSC</TxSts>")
        assertThat(body).contains("<OrgnlEndToEndId>TRX-PACT-001</OrgnlEndToEndId>")
    }
}
