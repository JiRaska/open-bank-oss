// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.contract

import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.party.application.port.out.PartyRepository
import com.openbank.party.domain.model.AmlStatus
import com.openbank.party.domain.model.KycStatus
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.PartyType
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Provider-side verification for party-domain contracts (ADR-0063 P1+P2 → ADR-0092, extended by
 * issue #468's onboarding->party/kyc/sca edge with the REST `PUT /{id}/kyc-status` interaction).
 * Boots Quarkus (needed for the HTTP interaction; the message ones don't use it) and picks a
 * per-interaction target the same way `TransactionPactProviderVerificationTest` does — a single
 * `@Provider` class must verify every pact the broker returns, so splitting HTTP and message into
 * two classes collides (this repo's own "one @Provider test per provider" rule). `@TestSecurity`
 * matches the kyc-status endpoint's `@RolesAllowed("ROLE_ADMIN", "ROLE_KYC")`.
 *
 * `@PactBroker` (not `@PactFolder`) — matches every other provider-verification test in this repo
 * (`TransactionPactProviderVerificationTest`, `BalancePactProviderVerificationTest`, etc.).
 * 2026-07-07 (#371) had switched this specific class to `@PactFolder("../pacts")` for local-dev
 * ergonomics (ADR-0063: no broker secret needed to run it locally) — but that meant party-service
 * CI never published a verification result to the broker again, for ANY consumer. kyc-service's
 * and onboarding-service's consumer contracts (added 2026-07-12, five days later) therefore never
 * got a single verification recorded — not stale, never-run — which silently wedged ADR-0092's
 * `can-i-deploy` gate: party-service's sandbox auto-deploy hard-blocked for 4 days straight with
 * no way to unblock from the consumer side (there's nothing to re-run there; they don't verify
 * party-service, they only publish their own pacts). Reverting to `@PactBroker` restores the one
 * thing `can-i-deploy` actually reads. `@EnabledIfSystemProperty` keeps it a no-op locally without
 * a broker URL, same as every sibling class — verify a change with `compileTestKotlin` locally;
 * the real verification only runs in CI (`pactbroker.url` set there).
 *
 * Two more gotchas hit while landing this revert (#1166), worth knowing before touching this
 * class again: (1) `services-ci.yml`'s `-Dpact.verifier.publishResults=true` is only set on a
 * genuine `push` to `main` — a manual `workflow_dispatch` run compiles/runs this test fine but
 * does NOT publish, so it looks green without ever unblocking `can-i-deploy`. (2) under heavy
 * concurrent CI load, `gh run rerun`-ing an old `push`-triggered run enough times/hours later can
 * make `auto-deploy.yml`'s "Detect changed services" step (`git diff HEAD~1 HEAD`) misfire and
 * rebuild an unrelated service instead — if a rerun's job list doesn't match the PR's actual
 * files, don't trust it; trigger a fresh `push` (even a trivial one touching this file) instead
 * of continuing to rerun a stale run object.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.party.it.PostgresRedpandaTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_KYC"])
@Provider("openbank-party-service")
@PactBroker(enablePendingPacts = "true")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class PartyEventPactProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var partyRepository: PartyRepository

    @Inject
    lateinit var vertx: Vertx

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    /**
     * Bridges a reactive-Panache block into Pact-JVM's synchronous `@State` callback — same fix as
     * `BalancePactProviderVerificationTest.runOnVertxContext`: pact-jvm invokes `@State` methods
     * directly via reflection on the JUnit test thread, which has no Vert.x context, so a bare
     * `runBlocking { partyRepository.save(...) }` throws `IllegalStateException: No current Vertx
     * context found`.
     */
    private fun runOnVertxContext(block: suspend () -> Unit) {
        val future = CompletableFuture<Unit>()
        val duplicated = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(duplicated, true)
        val dispatcher = Executor { command -> duplicated.runOnContext { command.run() } }.asCoroutineDispatcher()
        CoroutineScope(dispatcher).launch {
            try {
                block()
                future.complete(Unit)
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        future.get(10, TimeUnit.SECONDS)
    }

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        if (context == null) return
        // Package-scoped scan: the default classpath-wide ClassGraph scan throws on the JDK 25 toolchain.
        context.target = if (context.interaction.isAsynchronousMessage()) {
            MessageTestTarget(listOf("com.openbank.party.contract"))
        } else {
            HttpTestTarget("localhost", testPort.toInt())
        }
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        // context is null on the @IgnoreNoPactsToVerify dummy invocation — skip gracefully.
        context?.verifyInteraction()
    }

    @State("a party has been created")
    fun partyHasBeenCreated() {
        // No setup: the message is produced deterministically by the @PactVerifyProvider method below.
    }

    @PactVerifyProvider("a PARTY_CREATED event")
    fun producePartyCreatedEvent(): String {
        // Mirrors PartyEvents.created(party, at): the flat envelope, with
        // enums serialized to their names (partyType -> "INDIVIDUAL", etc.).
        val event = linkedMapOf(
            "eventType" to "PARTY_CREATED",
            "partyId" to UUID.randomUUID(),
            "partyType" to PartyType.INDIVIDUAL,
            "status" to PartyStatus.PENDING_KYC,
            "kycStatus" to KycStatus.NOT_STARTED,
            "legalName" to "Jane Smith",
            "email" to "jane.smith@example.com",
            "occurredAt" to Instant.now(),
        )
        return objectMapper.writeValueAsString(event)
    }

    @State("a party has been updated")
    fun partyHasBeenUpdated() {
        // No setup: produced deterministically by the @PactVerifyProvider method below.
    }

    @PactVerifyProvider("a PARTY_UPDATED event")
    fun producePartyUpdatedEvent(): String {
        val event = linkedMapOf(
            "eventType" to "PARTY_UPDATED",
            "partyId" to UUID.randomUUID(),
            "partyType" to PartyType.INDIVIDUAL,
            "status" to PartyStatus.ACTIVE,
            "kycStatus" to KycStatus.APPROVED,
            "legalName" to "Jane Smith",
            "email" to "jane.smith@example.com",
            "occurredAt" to Instant.now(),
        )
        return objectMapper.writeValueAsString(event)
    }

    @State("a party KYC status has changed")
    fun partyKycStatusHasChanged() {
        // No setup: produced deterministically by the @PactVerifyProvider method below.
    }

    @PactVerifyProvider("a KYC_STATUS_CHANGED event")
    fun produceKycStatusChangedEvent(): String {
        val event = linkedMapOf(
            "eventType" to "KYC_STATUS_CHANGED",
            "partyId" to UUID.randomUUID(),
            "partyType" to PartyType.INDIVIDUAL,
            "status" to PartyStatus.ACTIVE,
            "kycStatus" to KycStatus.APPROVED,
            "legalName" to "Jane Smith",
            "email" to "jane.smith@example.com",
            "occurredAt" to Instant.now(),
        )
        return objectMapper.writeValueAsString(event)
    }

    @State("a party has been erased")
    fun partyHasBeenErased() {
        // No setup: produced deterministically by the @PactVerifyProvider method below.
    }

    @PactVerifyProvider("a PARTY_ERASED event")
    fun producePartyErasedEvent(): String {
        // Mirrors PartyEvents.erased(id, at): the separate, narrower envelope
        // (no partyType/status/kycStatus/legalName/email — those are gone by the time GDPR
        // Art. 17 erasure runs).
        val event = linkedMapOf(
            "eventType" to "PARTY_ERASED",
            "partyId" to UUID.randomUUID(),
            "erasedAt" to Instant.now(),
        )
        return objectMapper.writeValueAsString(event)
    }

    @State("a party exists and can be suspended for KYC expiry")
    fun partyExistsAndCanBeSuspended() = runOnVertxContext {
        // pact-jvm 4.7.3 invokes each @State SETUP callback twice per interaction (visible for
        // EVERY state in this class, not just this one — harmless for the no-op states above, but
        // this one does a real insert with a fixed id, so the second call must be a no-op or it
        // hits the parties_party_id_key unique constraint).
        if (partyRepository.findById(FIXED_PARTY_ID) != null) return@runOnVertxContext
        // Seeds the exact party id PartyServicePactConsumerTest's request path embeds
        // (onboarding-service, issue #468) so PUT /{id}/kyc-status hits a real row.
        partyRepository.save(
            Party(
                id = FIXED_PARTY_ID,
                partyType = PartyType.INDIVIDUAL,
                status = PartyStatus.PENDING_KYC,
                legalName = "Pact Verify Party",
                tradingName = null,
                dateOfBirth = null,
                nationality = null,
                taxId = null,
                registrationNumber = null,
                email = "pact-verify-party@example.com",
                phone = null,
                address = null,
                kycStatus = KycStatus.IN_PROGRESS,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                amlStatus = AmlStatus.NOT_SCREENED,
            ),
        )
    }

    /**
     * State for vop-service's `PartyNameLookupPactConsumerTest` (issue #2255): hop 2 of the ADR-0171
     * §4 VoP name resolution reads `legalName`/`tradingName` off `GET /api/v1/parties/{id}`, and the
     * adapter falls back to `tradingName` when `legalName` is blank — so both fields must be present
     * and non-blank on the seeded row, unlike [FIXED_PARTY_ID] above which has a null trading name.
     *
     * Seeded as a COMPANY: a party carrying both names is by construction a legal entity, and it
     * keeps this row from colliding with the individual the KYC-expiry state above inserts.
     */
    @State("a party exists with both a legal name and a trading name")
    fun partyExistsWithLegalAndTradingName() = runOnVertxContext {
        // Idempotent for the same reason as the state above: pact-jvm 4.7.3 invokes each @State
        // setup callback twice per interaction, and this one inserts with a fixed id.
        if (partyRepository.findById(VOP_NAME_PARTY_ID) != null) return@runOnVertxContext
        partyRepository.save(
            Party(
                id = VOP_NAME_PARTY_ID,
                partyType = PartyType.COMPANY,
                status = PartyStatus.ACTIVE,
                legalName = "Pact Verify Trading Company a.s.",
                tradingName = "PactVerify",
                dateOfBirth = null,
                nationality = null,
                taxId = null,
                registrationNumber = null,
                email = "pact-verify-vop@example.com",
                phone = null,
                address = null,
                kycStatus = KycStatus.APPROVED,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                amlStatus = AmlStatus.NOT_SCREENED,
            ),
        )
    }

    companion object {
        private val FIXED_PARTY_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        /** Must equal `PartyNameLookupPactConsumerTest.PACT_PARTY_ID` (openbank-vop-service). */
        private val VOP_NAME_PARTY_ID = UUID.fromString("b1b1b1b1-c2c2-4d4d-8e8e-f9f9f9f9f9f9")
    }

    /**
     * The negative state: deliberately seeds NOTHING — see the account-service twin. An unknown
     * party id must answer 404, not a 200 with empty names, which VoP would read as a real "no
     * name held" for the payee.
     */
    @State("no party exists with the unknown id")
    fun noPartyWithUnknownId() {
        // Intentionally empty.
    }
}
