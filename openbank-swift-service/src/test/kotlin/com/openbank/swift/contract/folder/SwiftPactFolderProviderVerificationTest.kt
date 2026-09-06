// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.contract.folder

import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Git-pact provider verification for swift-service — the last pact in the #2327 sweep, unblocked by
 * removing the CI exclusions on this module's two consumer tests (#2319).
 *
 * swift-service is the provider for `pacts/openbank-transaction-service-openbank-swift-service.json`,
 * the `swift.message.status-changed` message contract. Its only verification class was
 * `SwiftMessagePactProviderVerificationTest` — `@PactBroker`-sourced and
 * `@EnabledIfSystemProperty(pactbroker.url)`-gated, so it never ran on a pull request and the
 * contract was replayed only after the merge, against whatever the broker happened to hold.
 *
 * ## Why this pact needed unblocking first, and what that surfaced
 *
 * The consumer test was excluded from CI, so its pact was never regenerated, and the drift gate
 * declared the pact out of scope for exactly that reason. Under those two facts the committed pact
 * and the test that produces it were free to disagree — and they did: the test asked for a `SETTLED`
 * status that `SwiftStatus` has never contained (`PENDING`/`VALIDATED`/`SENT`/`ACKNOWLEDGED`/
 * `REJECTED`/`FAILED`/`COMPLETED`), while the committed pact and the provider's own
 * `@PactVerifyProvider` description both said `COMPLETED`. Corrected to `COMPLETED`, regeneration
 * is now a no-op, which is the evidence that the committed pact was right and the test had drifted.
 *
 * ## Additive, not a replacement
 *
 * The broker twin stays as it is: its published verification result is the only thing ADR-0092's
 * `can-i-deploy` reads, and dropping that elsewhere in the fleet blocked deploys for days
 * (party-service #371/#1166, account-service #372/#1166). Git source for the PR lane, broker source
 * for the published result. Two `@Provider` classes collide only when BOTH pull from the broker.
 *
 * ## Upkeep
 *
 * A deliberate duplicate of the broker twin's body: same `@State` handler and same
 * [PactVerifyProvider] producer, whose description must keep matching the pact's interaction
 * description. A change to one belongs in the other.
 */
@Provider("openbank-swift-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class SwiftPactFolderProviderVerificationTest {

    private val objectMapper = ObjectMapper().apply { findAndRegisterModules() }

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        // Scope the ClassGraph scan to this package. The no-arg MessageTestTarget() scans the
        // whole test classpath, and on swift-service that throws ClassGraphException ("Uncaught
        // exception during scan"), failing verification with mismatches:[] (a harness crash, not a
        // contract mismatch) — which kept the transaction<->swift edge red after #1938 re-enabled
        // this class (#1348). Every working sibling (account/party/kyc/transaction) scopes the scan.
        // Scope the scan to THIS class's own leaf package, which now holds exactly one
        // @PactVerifyProvider for each description.
        //
        // Scoping to the parent `com.openbank.swift.contract` was not enough, and the way it failed
        // is worth keeping. ClassGraph includes SUB-packages, and the broker twin declared the same
        // `@PactVerifyProvider` description in that same package — so a scan found TWO candidates
        // and which one it resolved to was determined by nothing in the code. Measured: a run
        // resolved to the twin and failed with
        //   "Could not load method: ...SwiftMessagePactProviderVerificationTest.produceSwiftStatusChanged"
        // which surfaces as a bare AssertionError out of verifyInteraction() with no mismatch
        // detail — the shape this test failed with in CI (#8916).
        //
        // Identical bodies do NOT make that safe: they make the OUTPUT insensitive to the choice,
        // not the LOADABILITY. Whichever class is picked has to be loadable at that moment, and
        // @EnabledIfSystemProperty on the twin gates whether its tests RUN, never whether
        // ClassGraph sees or loads it.
        context?.target = MessageTestTarget(listOf("com.openbank.swift.contract.folder"))
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

    @PactVerifyProvider("a swift.message.status-changed event with status COMPLETED")
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
