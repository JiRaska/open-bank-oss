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
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
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
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Git-pact provider verification for delegation-service — the replay that runs BEFORE a merge
 * (issue #2991; the rule is `check-pact-provider-replay.py`, #2338).
 *
 * delegation-service is the provider of the `openbank.delegation.events` message contract that
 * account-service and card-issuance build their enforcement projections from (ADR-0232 D3). Before
 * this class the service had no pacts at all in either direction, so its deploys carried no
 * contract gate whatsoever — nothing compared what `DelegationEvents.kt` emits with what the two
 * projections read.
 *
 * ## What makes the replay, not the consumer pacts, the load-bearing half here
 *
 * Both consumers parse the payload with `objectMapper.readTree` + `node.path(...)`, so a field
 * this service renames does not break them loudly — it defaults. `capabilities` silently becomes
 * the empty set, `resourceType` becomes `""` and the event is filtered out entirely. A consumer
 * pact alone cannot catch that, because the pact mock hands the consumer whatever the pact says.
 * Only replaying the CONSUMER's expectations against the REAL event types can, and that is what
 * the [PactVerifyProvider] methods below do: they serialize the genuine [DelegationActivated] and
 * [DelegationRevoked] data classes, so renaming `granteePartyId` in `DelegationEvents.kt` turns
 * this red on the PR that does it.
 *
 * ## No Quarkus, no Testcontainer
 *
 * Every pact naming delegation-service as provider is message-only, so a [MessageTestTarget] is
 * the whole target: this is a plain JVM test. If an HTTP consumer contract against
 * delegation-service is ever added, this needs party-service's per-interaction target dispatch
 * (`PartyPactFolderProviderVerificationTest`).
 *
 * ## `@PactFolder` only, deliberately — and what that costs
 *
 * There is no `@PactBroker` twin yet, because delegation-service is not in the broker's consumer
 * graph. Note the consequence rather than assuming it away: `can-i-deploy` (ADR-0092) reads the
 * broker and nothing else, so a `@PactFolder`-only provider publishes no verification result and
 * can block its CONSUMERS' deploys once their pacts reach the broker — the failure mode #3239
 * documents. If account-service or card-issuance ever start publishing these pacts to the broker,
 * add the broker twin (`@PactBroker` + `@EnabledIfSystemProperty(pactbroker.url)`) alongside this
 * class; do NOT convert this one, which is the mistake #372/#1166 made and reverted twice.
 *
 * ## Upkeep
 *
 * The serializer below mirrors the production ObjectMapper (`DelegationRepositoryImpl` writes the
 * outbox payload with the CDI-injected one): JavaTimeModule with `WRITE_DATES_AS_TIMESTAMPS`
 * disabled, which is Quarkus's default and the reason `validFrom` reaches Kafka as an ISO-8601
 * string the consumers can `OffsetDateTime.parse`. Do not let the two configurations drift — a
 * numeric timestamp would be a silent, total projection outage that this replay would then pass.
 */
@Provider("openbank-delegation-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class DelegationEventPactFolderProviderVerificationTest {

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
