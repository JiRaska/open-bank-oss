// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.pid.application.port.out.PartyRepository
import com.openbank.pid.domain.model.AmlRiskScore
import com.openbank.pid.domain.model.ContactAttributes
import com.openbank.pid.domain.model.CoreAttributes
import com.openbank.pid.domain.model.KycAttributes
import com.openbank.pid.domain.model.KycLevel
import com.openbank.pid.domain.model.Party
import com.openbank.pid.domain.model.PartyStatus
import com.openbank.pid.domain.model.PartyType
import com.openbank.pid.domain.model.VerificationSource
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
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Git-pact provider verification for pid-service — the FIRST it has ever had (issue #2991).
 *
 * pid-service is the provider for one committed pact: delegation-service's
 * `GET /api/v1/parties/{id}`, the ADR-0232 D5 eligibility check that decides whether a party may
 * be either end of a delegation grant. `check-pact-provider-replay.py` requires that every
 * committed pact is replayed by a class that actually RUNS on a pull request, so this is
 * `@PactFolder`-sourced and carries no `@EnabledIf*` gate — a `@PactBroker` class would skip on
 * the PR lane (`PACT_BROKER_URL` is blank there; the broker has no public ingress, ADR-0056) and
 * the contract would be replayed only after the merge, which is what #2327 had to unwind fleet-wide.
 *
 * ## Why the replay is what protects this call, not the consumer pact
 *
 * delegation-service reads `kycAttributes.kycLevel` with an elvis to `"NONE"`. If pid-service
 * moved or renamed that nested object, the consumer would not fail — it would rank every grantee
 * at KYC level NONE and refuse every offer, silently. The Pact mock server cannot expose that,
 * because it serves whatever the pact says; only asking the real `PartyResource` for the real
 * `PartyResponse` can (#2269).
 *
 * `@TestSecurity` matches `PartyResource.getById`'s `@RolesAllowed(Roles.OPERATOR, Roles.ADMIN)`.
 * Note what that means for the contract: this replay proves the ROUTE and the SHAPE, and asserts
 * nothing about who may call it. The M2M authorization of delegation-service against pid-service
 * is a separate concern (ADR-0034) and a pact is the wrong instrument for it.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.pid.it.PostgresTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
@Provider("openbank-pid-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class PidPactFolderProviderVerificationTest {

    companion object {
        // Must match DelegationPartyEligibilityPactConsumerTest.PARTY_ID.
        private val PARTY_ID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
        private val SEEDED_AT: OffsetDateTime = OffsetDateTime.parse("2026-01-01T00:00:00Z")
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var partyRepository: PartyRepository

    @Inject
    lateinit var vertx: Vertx

    /**
     * Bridges a reactive-Panache block into Pact-JVM's synchronous `@State` callback. Pact-JVM
     * invokes `@State` methods by reflection on the JUnit test thread, which has no Vert.x
     * context, so a bare `runBlocking { partyRepository.save(...) }` throws `IllegalStateException:
     * No current Vertx context found`. Same shape as `ScaPactFolderProviderVerificationTest` and
     * `PartyPactFolderProviderVerificationTest`; a plain `vertx.runOnContext { runBlocking {} }` is
     * NOT sufficient (balance-service's class documents why).
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
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    /**
     * Seeded once and guarded by a `findById`: `PartyRepositoryImpl.save` calls `persist`, not
     * `merge`, and the id is application-assigned — so a second call for the same party would fail
     * on the primary key rather than upsert. The guard keeps this handler safe if a second
     * interaction against pid-service is ever added to the pact.
     */
    @State("an ACTIVE party with FULL KYC exists")
    fun stateActiveFullKycParty() = runOnVertxContext {
        if (partyRepository.findById(PARTY_ID) != null) return@runOnVertxContext
        partyRepository.save(
            Party(
                id = PARTY_ID,
                partyType = PartyType.NATURAL_PERSON,
                status = PartyStatus.ACTIVE,
                externalIds = emptyList(),
                coreAttributes = CoreAttributes(
                    givenName = "Pact",
                    familyName = "Verifier",
                    birthdate = LocalDate.of(1990, 1, 1),
                    birthNumberEncrypted = null,
                    gender = null,
                    birthplace = null,
                    nationalities = listOf("CZ"),
                    idDocuments = emptyList(),
                    verificationSource = VerificationSource.BANKID,
                    verifiedAt = SEEDED_AT,
                ),
                addressAttributes = null,
                contactAttributes = ContactAttributes(null, null, null, null),
                kycAttributes = KycAttributes(
                    kycLevel = KycLevel.FULL,
                    kycCompletedAt = SEEDED_AT,
                    kycExpiresAt = null,
                    amlRiskScore = AmlRiskScore.LOW,
                    pepFlag = false,
                    sanctionsFlag = false,
                    uboVerifiedAt = null,
                    lastAmlReviewAt = null,
                ),
                relationships = emptyList(),
                caseLifecycle = null,
                createdAt = SEEDED_AT,
                updatedAt = SEEDED_AT,
                version = 0,
            ),
        )
        Unit
    }
}
