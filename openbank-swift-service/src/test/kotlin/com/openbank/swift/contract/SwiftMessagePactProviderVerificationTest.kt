// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.contract

import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Provider-side MESSAGE contract verification for `swift.message.status-changed` events
 * produced by swift-service and consumed by transaction-service (ADR-0092, ADR-0108).
 *
 * Does NOT boot Quarkus — message providers return raw JSON from [PactVerifyProvider]
 * methods, so this is a pure unit-level verification (no DB, no Kafka).
 */
@Provider("openbank-swift-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class SwiftMessagePactProviderVerificationTest {

    private val objectMapper = ObjectMapper().apply { findAndRegisterModules() }

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = MessageTestTarget()
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPact(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("swift-service has processed an MT103 and submitted to scheme gateway")
    fun stateSwiftMessageProcessed() {
        // No state setup needed: message producer methods return deterministic payloads.
    }

    @PactVerifyProvider("a swift.message.status-changed event with status SETTLED")
    fun produceSwiftStatusChanged(): String {
        val message = SwiftMessage(
            id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            idempotencyKey = "PACT-VERIFY-001",
            messageType = SwiftMessageType.MT103,
            senderBic = "GIBACZPX",
            receiverBic = "DEUTDEFF",
            transactionReference = "saga-ref-001",
            relatedReference = null,
            valueDate = "20260101",
            currency = "EUR",
            amountMinorUnits = 100_000L, // 1000.00 EUR
            orderingCustomerAccount = null,
            orderingCustomerAccountId = null,
            orderingCustomerName = null,
            beneficiaryAccount = "DE89370400440532013000",
            beneficiaryName = "Counterparty GmbH",
            remittanceInfo = "PACT contract verification",
            chargeCode = "SHA",
            priority = SwiftPriority.NORMAL,
            status = SwiftStatus.COMPLETED,
            rawMt = null,
            ackReceivedAt = null,
            rejectionReason = null,
            createdAt = Instant.parse("2026-01-01T10:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T10:05:00Z"),
        )
        return objectMapper.writeValueAsString(
            mapOf(
                "swiftMessageId" to message.id.toString(),
                "paymentSagaRef" to message.transactionReference,
                "status" to message.status.name,
                "messageType" to message.messageType.name,
                "amount" to BigDecimal(message.amountMinorUnits).movePointLeft(2),
                "currency" to message.currency,
                "occurredAt" to message.updatedAt.toString(),
            ),
        )
    }
}
