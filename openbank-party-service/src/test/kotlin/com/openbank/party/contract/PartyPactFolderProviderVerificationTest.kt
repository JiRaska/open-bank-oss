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
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
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
import io.quarkus.test.security.TestIdentityAssociation
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
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Git-pact provider verification for party-service — the half that actually runs before a merge
 * (issue #2327, gated by `check-pact-provider-replay.py` per #2338).
 *
 * ## Why this exists as a SECOND class rather than a switch on the first one
 *
 * party-service is the provider for four committed pacts, and its only verification class was
 * [PartyEventPactProviderVerificationTest] — `@PactBroker`-sourced and
 * `@EnabledIfSystemProperty(pactbroker.url)`-gated. On a pull request that property is empty
 * (`_service-ci.yml` puts the PR lane on `ubuntu-latest` and blanks `PACT_BROKER_URL` off
 * main-push, because the broker has no public ingress, ADR-0056), so it skipped and all four
 * contracts were replayed only AFTER the merge. A consumer pact cannot catch a wrong request path;
 * only the provider replay can (#2269).
 *
 * **Do not "fix" that by flipping the existing class to `@PactFolder`. That was tried and reverted.**
 * #371 switched it for local-dev ergonomics, and party-service then published no verification result
 * to the broker for any consumer — which is the only thing ADR-0092's `can-i-deploy` reads, so
 * party-service's sandbox auto-deploy hard-blocked for four days with no way to unblock from the
 * consumer side (#1166). The broker class must stay exactly as it is. This class is additive: git
 * source for the PR lane, broker source for the published result. Two `@Provider` classes collide
 * only when BOTH pull from the broker (each fetches every pact it holds); a folder class and a
 * broker class each load their own source, which is the pair openbank-ledger-service carries.
 *
 * ## Upkeep
 *
 * This class is a deliberate duplicate of the broker twin's body: same `@State` handlers, same
 * [PactVerifyProvider] producers, same seeded rows. A change to one belongs in the other, or the
 * same contract passes from git and fails from the broker (or the reverse). The duplication is the
 * conservative choice on purpose — factoring the shared body into a base class would mean editing
 * the class whose last structural change wedged deploys for four days, for a cosmetic gain.
 *
 * ## Both interaction kinds, one class
 *
 * Five HTTP interactions (onboarding-service ×4, vop-service ×1) and five message interactions
 * (account-service ×3, kyc-service ×2), so the target is chosen per interaction in
 * [configureTarget]. The [MessageTestTarget] is package-scoped: the default classpath-wide
 * ClassGraph scan throws on the JDK 25 toolchain.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.party.it.PostgresRedpandaTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_KYC"])
@Provider("openbank-party-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class PartyPactFolderProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var partyRepository: PartyRepository

    @Inject
    lateinit var vertx: Vertx

    @Inject
    lateinit var testIdentityAssociation: TestIdentityAssociation

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
        if (context == null) return
        // #8875: the anonymous VoP interaction must be replayed WITHOUT the class-level
        // @TestSecurity identity, or the provider never executes the 401 branch the contract
        // asserts. QuarkusSecurityTestExtension re-applies @TestSecurity before the next
        // invocation, so clearing it here cannot leak into the authenticated interactions.
        if (context.interaction.description.endsWith("no caller identity")) {
            testIdentityAssociation.setTestIdentity(null)
        }
        context.verifyInteraction()
    }

    /**
     * Serves the NEGATIVE interaction of the VoP pact: a party id the bank does not hold must
     * answer 404, not an empty party. The absence IS the state — nothing is seeded, and the id in
     * the pact is one no other state creates — but a handler still has to exist, because pact-jvm
     * fails on an unknown state string before it issues the request at all (#8889).
     */
    @State("no party exists for the id")
    fun noPartyForId() {
        // Deliberately empty. Asserting emptiness here would test the fixture, not the provider.
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
