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
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Broker-side provider verification for pid-service — the published-result counterpart to
 * [PidPactFolderProviderVerificationTest].
 *
 * ## Why this exists, and why it had to land in the SAME PR as the pact
 *
 * `can-i-deploy` (ADR-0092) reads the broker and nothing else. A `@PactFolder` class replays the
 * committed pact from disk and never contacts the broker, so a provider that has only one has
 * **never published a version on main** — and every consumer of it then reads as unverified,
 * permanently. That is not a delay that clears itself.
 *
 * Without this class, merging the delegation pacts would have made `openbank-delegation-service`
 * undeployable the moment it started publishing: `can-i-deploy` would answer "no verified pact
 * between openbank-delegation-service and the version of openbank-pid-service currently in
 * sandbox", and label it `PENDING_BUILD` — a name that reads like a transient state and is not
 * one. The same shape took fx-service out across four deploy attempts on 2026-08-02 and is what
 * #3239 added the aml/sanctions twins for. delegation-service reached sandbox for the first time
 * the night this landed; shipping the consumer pact without this class would have taken that away.
 *
 * ## Why a second `@Provider("openbank-pid-service")` class is safe
 *
 * The collision `rules.yaml`/ADR-0029 D3 warns about is two BROKER-sourced classes for one
 * provider — each fetches every pact the broker holds — or an HTTP and a MESSAGE target fighting
 * over the same `@BeforeEach`. This is the sanctioned pair CLAUDE.md documents: a `@PactFolder`
 * class for the PR lane plus a `@PactBroker` class gated on `pactbroker.url`, exactly as
 * openbank-ledger-service and (since #3239) aml-service and sanctions-service carry. pid-service
 * has no message-consumer contracts and both classes use [HttpTestTarget] exclusively, so
 * verifying the same interaction from two sources is at worst redundant, never colliding.
 *
 * Gated on `pactbroker.url`: skipped locally and on the PR lane, where `_service-ci.yml` blanks
 * `PACT_BROKER_URL` (the broker has no public ingress, ADR-0056). It runs on main-push, where
 * `PUBLISH_RESULTS=true` — that run is what creates the main version `can-i-deploy` looks for.
 *
 * ## Every state the git-pact twin handles is handled here too
 *
 * The broker serves EVERY consumer's pact for this provider, not just the one committed under
 * `pacts/`. A missing state fails with `MissingStateChangeMethod` and publishes a FAILURE, which
 * blocks an otherwise healthy pair — strictly worse than publishing nothing, and it would turn
 * this class into the problem it exists to remove. Today that is one state, mirrored verbatim
 * below; anything added to [PidPactFolderProviderVerificationTest] belongs here in the same commit.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.pid.it.PostgresTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
@Provider("openbank-pid-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class PidPactBrokerProviderVerificationTest {

    companion object {
        // Must match DelegationPartyEligibilityPactConsumerTest.PARTY_ID and the git-pact twin.
        private val PARTY_ID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
        private val SEEDED_AT: OffsetDateTime = OffsetDateTime.parse("2026-01-01T00:00:00Z")
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var partyRepository: PartyRepository

    @Inject
    lateinit var vertx: Vertx

    /** See the git-pact twin: Pact-JVM calls `@State` by reflection on a thread with no Vert.x context. */
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
     * Mirrors [PidPactFolderProviderVerificationTest.stateActiveFullKycParty] verbatim, including
     * the `findById` guard: `PartyRepositoryImpl.save` calls `persist`, not `merge`, on an
     * application-assigned id, so a second call for the same party fails on the primary key rather
     * than upserting. The broker can hand this class several consumers' interactions naming the
     * same state, so that guard is load-bearing here in a way it is not on the single-pact side.
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
