// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.delegation.domain.event.DelegationActivated
import com.openbank.delegation.domain.event.DelegationRevoked
import com.openbank.delegation.domain.event.EventMoney
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationResourceType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Broker-sourced twin of [DelegationEventPactFolderProviderVerificationTest] — the half that
 * PUBLISHES a verification result, which is the only thing `can-i-deploy` can see (ADR-0092).
 *
 * ## Why this exists now
 *
 * The `@PactFolder` class predicted this exactly: "if account-service or card-issuance ever start
 * publishing these pacts to the broker, add the broker twin alongside this class". They do, and
 * the consequence arrived as a hard deploy block. `can-i-deploy` refused the whole delegation set
 * with
 *
 *     There is no verified pact between the latest version of openbank-account-service with tag
 *     main and the latest version of openbank-delegation-service with tag main
 *
 * and classified it as "a real contract break, not an ordering problem" — because from the
 * broker's side that is indistinguishable from one. The pacts ARE verified, on every PR, by the
 * folder class; a `@PactFolder` replay simply cannot tell the broker so. Verified-and-unpublished
 * and never-verified look identical to the gate, and the gate is right to refuse both.
 *
 * ## Alongside, never instead
 *
 * Converting the folder class into this one is the mistake #372/#1166 made and reverted twice. The
 * PR lane blanks `PACT_BROKER_URL` (the broker has no public ingress, ADR-0056), so this class is
 * `@EnabledIfSystemProperty`-skipped there and the contract would be replayed only after a merge —
 * exactly the fleet-wide hole #2327 had to unwind. The folder class is what makes a breaking change
 * red BEFORE merge; this one is what makes the deploy possible after it. Both are required and
 * `check-pact-provider-replay.py` counts only the folder class as coverage.
 *
 * ## Upkeep: the states must not drift
 *
 * Every `@State` and `@PactVerifyProvider` below mirrors the folder class verbatim. A state the
 * broker replay is missing fails with `MissingStateChangeMethod` and publishes a **FAILURE**, which
 * blocks an otherwise-healthy pair — strictly worse than publishing nothing. When you add an
 * interaction to one class, add it to both in the same commit.
 */
@Provider("openbank-delegation-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class DelegationEventPactBrokerProviderVerificationTest {

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private companion object {
        val GRANT_ID: UUID = UUID.fromString("aaaa1111-bbbb-4ccc-8ddd-eeeeffff0000")
        val GRANTOR: UUID = UUID.fromString("aaaa2222-bbbb-4ccc-8ddd-eeeeffff0001")
        val GRANTEE: UUID = UUID.fromString("aaaa3333-bbbb-4ccc-8ddd-eeeeffff0002")
        val RESOURCE: UUID = UUID.fromString("aaaa4444-bbbb-4ccc-8ddd-eeeeffff0003")
        val VALID_FROM: OffsetDateTime = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        val OCCURRED_AT: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }

    @BeforeEach
    fun setTarget(context: PactVerificationContext?) {
        // Package-scoped scan: the default classpath-wide ClassGraph scan throws on the JDK 25+
        // toolchain (same reason as account-service's and party-service's classes).
        context?.target = MessageTestTarget(listOf("com.openbank.delegation.contract"))
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("an ACCOUNT-scoped delegation grant has been activated")
    fun accountGrantActivated() = Unit

    @PactVerifyProvider("a DelegationActivated event for an account")
    fun produceAccountActivated(): String = objectMapper.writeValueAsString(
        DelegationActivated(
            aggregateId = GRANT_ID,
            lifecycleRevision = 1,
            grantorPartyId = GRANTOR,
            granteePartyId = GRANTEE,
            resourceType = DelegationResourceType.ACCOUNT,
            resourceId = RESOURCE,
            capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
            validFrom = VALID_FROM,
            validTo = null,
            perTransactionLimit = EventMoney(BigDecimal("1500.00"), "CZK"),
            occurredAt = OCCURRED_AT,
        ),
    )

    @State("an ACCOUNT-scoped delegation grant has been revoked")
    fun accountGrantRevoked() = Unit

    @PactVerifyProvider("a DelegationRevoked event for an account")
    fun produceAccountRevoked(): String = objectMapper.writeValueAsString(
        DelegationRevoked(
            aggregateId = GRANT_ID,
            lifecycleRevision = 2,
            grantorPartyId = GRANTOR,
            granteePartyId = GRANTEE,
            resourceType = DelegationResourceType.ACCOUNT,
            resourceId = RESOURCE,
            capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
            reason = "grantor revoked",
            occurredAt = OCCURRED_AT,
        ),
    )

    @State("a CARD-scoped delegation grant has been activated")
    fun cardGrantActivated() = Unit

    @PactVerifyProvider("a DelegationActivated event for a card")
    fun produceCardActivated(): String = objectMapper.writeValueAsString(
        DelegationActivated(
            aggregateId = GRANT_ID,
            lifecycleRevision = 1,
            grantorPartyId = GRANTOR,
            granteePartyId = GRANTEE,
            resourceType = DelegationResourceType.CARD,
            resourceId = RESOURCE,
            capabilities = setOf(DelegationCapability.CARD_VIEW),
            validFrom = VALID_FROM,
            validTo = null,
            perTransactionLimit = null,
            occurredAt = OCCURRED_AT,
        ),
    )

    @State("a CARD-scoped delegation grant has been revoked")
    fun cardGrantRevoked() = Unit

    @PactVerifyProvider("a DelegationRevoked event for a card")
    fun produceCardRevoked(): String = objectMapper.writeValueAsString(
        DelegationRevoked(
            aggregateId = GRANT_ID,
            lifecycleRevision = 2,
            grantorPartyId = GRANTOR,
            granteePartyId = GRANTEE,
            resourceType = DelegationResourceType.CARD,
            resourceId = RESOURCE,
            capabilities = setOf(DelegationCapability.CARD_VIEW),
            reason = "grantor revoked",
            occurredAt = OCCURRED_AT,
        ),
    )
}
