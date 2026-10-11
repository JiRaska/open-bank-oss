// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.sca.application.port.out.ScaChallengeRepository
import com.openbank.sca.domain.model.DynamicLinkingData
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.ScaMethod
import com.openbank.sca.domain.model.ScaPurpose
import com.openbank.sca.domain.model.ScaStatus
import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Provider-side verification for the SCA challenge GET contract published by consent-service
 * (ADR-0063 P2 Batch B). Seeds a PENDING CONSENT_GRANT challenge so that
 * GET /api/v1/sca/challenges/{id} returns 200 with the expected shape.
 *
 * The challenge UUID must match ConsentScaChallengePactConsumerTest exactly.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.sca.it.PostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-sca-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class ScaPactProviderVerificationTest {

    companion object {
        const val PENSION_OPERATION_STATE = "a COMPLETED APPROVAL SCA challenge bound to a pension operation exists"
        val PENSION_CHALLENGE_ID: UUID = UUID.fromString("7e5a0001-0000-4000-8000-000000000335")
        val PENSION_PARTY_ID: UUID = UUID.fromString("7e5a0002-0000-4000-8000-000000000335")
        const val PENSION_PAYLOAD_SHA256 = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val PENSION_APPROVAL_REQUEST_ID = "pension-exit:$PENSION_PAYLOAD_SHA256"
        val PENSION_IDENTITY: QuarkusSecurityIdentity = QuarkusSecurityIdentity.builder()
            .setPrincipal(QuarkusPrincipal("service-account-openbank-pension"))
            .addRole("ROLE_API")
            .build()
        private val CHALLENGE_ID = UUID.fromString("99999999-9999-9999-9999-999999999999")
        private val PARTY_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        private val DELEGATION_CHALLENGE_ID = UUID.fromString("d1e2f3a4-b5c6-4d7e-8f90-1a2b3c4d5e6f")
        private val DELEGATION_PARTY_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")

        // Must match SavingsWithdrawScaPactConsumerTest (openbank-account-service).
        private val SAVINGS_CHALLENGE_ID = UUID.fromString("5a5a5a5a-5a5a-4a5a-8a5a-5a5a5a5a5a5a")
        private val SAVINGS_PARTY_ID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var testIdentityAssociation: TestIdentityAssociation

    @Inject
    lateinit var challengeRepo: ScaChallengeRepository

    @Inject
    lateinit var vertx: Vertx

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    /**
     * Bridges a reactive-Panache block into Pact-JVM's synchronous `@State` callback. Pact-JVM
     * invokes `@State` methods directly via reflection on the JUnit test thread, which has no
     * Vert.x context — `Panache.withTransaction`/`withSession` (used by [challengeRepo]) requires
     * one, so a bare `runBlocking { challengeRepo.save(...) }` throws `IllegalStateException: No
     * current Vertx context found`. Confirmed live: this silently broke every
     * consent-service<->sca-service pact verification (result 2026-07-14T11:28:06Z) without ever
     * being noticed, because the failure only actually blocks a deploy once `can-i-deploy` is
     * reached. Same fix as balance-service's `BalancePactProviderVerificationTest` (which
     * documents why a plain `vertx.runOnContext { runBlocking { ... } }` is NOT sufficient).
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

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        // The missing-identity interactions expect 401. Class-level @TestSecurity otherwise
        // authenticates every broker replay, including those, and would answer as if signed in.
        if (context != null && context.interaction.providerStates.any { it.name == NEGATIVE_AUTH_STATE }) {
            testIdentityAssociation.setTestIdentity(null)
        }
        if (context != null && context.interaction.providerStates.any { it.name == PENSION_OPERATION_STATE }) {
            testIdentityAssociation.setTestIdentity(PENSION_IDENTITY)
        }
        context?.verifyInteraction()
    }

    @State(NEGATIVE_AUTH_STATE)
    fun stateNoValidM2mIdentity() {
        // verifyPacts clears the authenticated test identity for this interaction.
    }

    @State("a PENDING SCA challenge exists")
    fun statePendingChallengeExists() = runOnVertxContext {
        challengeRepo.save(
            ScaChallenge(
                id = CHALLENGE_ID,
                version = challengeRepo.findById(CHALLENGE_ID)?.version ?: 0,
                partyId = PARTY_ID,
                purpose = ScaPurpose.CONSENT_GRANT,
                method = ScaMethod.PUSH_NOTIFICATION,
                status = ScaStatus.PENDING,
                expiresAt = OffsetDateTime.now().plusMinutes(5),
                createdAt = OffsetDateTime.now(),
            ),
        )
        Unit
    }

    /**
     * The ADR-0232 D4 delegation ceremony's grantor half (issue #2991). Distinct from the consent
     * challenge above in every field the consumer gates on: purpose `DELEGATION_GRANT` (so a
     * consent or payment challenge can never be spent to mint a grant) and status `COMPLETED`
     * (delegation-service refuses anything else), with `consumedAt` null so the pact's second
     * interaction — the compare-and-consume that makes the ceremony single-use — has something
     * left to spend. The fixture reads the current version before `save`, so re-running it resets
     * `consumedAt` and the two interactions do not have to care which order they run in.
     *
     * `dynamicLinkingData` is deliberately null: a delegation challenge links to no operation, and
     * `ScaService.consume` authorises an unlinked challenge exactly when the consume states none —
     * which is why the consumer's request body carries only `partyId`.
     */
    /**
     * State for account-service's `SavingsWithdrawScaPactConsumerTest` (#8345): the owner-approval
     * leg of a savings withdrawal proposal reads the challenge, checks its purpose and party, and
     * consumes it. Seeded COMPLETED with `consumedAt = null`, the same shape the delegation state
     * uses, so the consume interaction has something to spend.
     */
    @State("a COMPLETED SAVINGS_WITHDRAW_APPROVAL SCA challenge exists")
    fun stateCompletedSavingsWithdrawChallengeExists() = runOnVertxContext {
        challengeRepo.save(
            ScaChallenge(
                id = SAVINGS_CHALLENGE_ID,
                version = challengeRepo.findById(SAVINGS_CHALLENGE_ID)?.version ?: 0,
                partyId = SAVINGS_PARTY_ID,
                purpose = ScaPurpose.SAVINGS_WITHDRAW_APPROVAL,
                method = ScaMethod.PUSH_NOTIFICATION,
                status = ScaStatus.COMPLETED,
                expiresAt = OffsetDateTime.now().plusMinutes(5),
                completedAt = OffsetDateTime.now(),
                consumedAt = null,
                createdAt = OffsetDateTime.now(),
            ),
        )
        Unit
    }

    @State("a COMPLETED DELEGATION_GRANT SCA challenge exists")
    fun stateCompletedDelegationGrantChallengeExists() = runOnVertxContext {
        challengeRepo.save(
            ScaChallenge(
                id = DELEGATION_CHALLENGE_ID,
                version = challengeRepo.findById(DELEGATION_CHALLENGE_ID)?.version ?: 0,
                partyId = DELEGATION_PARTY_ID,
                purpose = ScaPurpose.DELEGATION_GRANT,
                method = ScaMethod.PUSH_NOTIFICATION,
                status = ScaStatus.COMPLETED,
                expiresAt = OffsetDateTime.now().plusMinutes(5),
                completedAt = OffsetDateTime.now(),
                consumedAt = null,
                createdAt = OffsetDateTime.now(),
            ),
        )
        Unit
    }

    /**
     * ADR-0335 / #12385 item 3: pension-service's positive consume. Seeded COMPLETED, unconsumed,
     * APPROVAL, with a device-signed `pension-exit:` id, so the consume succeeds ONLY for the
     * pension principal — [verifyPacts] switches the replay identity to it for this state, exactly
     * as production would present it. Constants must match the pension consumer pact.
     */
    @State(PENSION_OPERATION_STATE)
    fun stateCompletedPensionApprovalExists() = runOnVertxContext {
        challengeRepo.save(
            ScaChallenge(
                id = PENSION_CHALLENGE_ID,
                version = challengeRepo.findById(PENSION_CHALLENGE_ID)?.version ?: 0,
                partyId = PENSION_PARTY_ID,
                purpose = ScaPurpose.APPROVAL,
                method = ScaMethod.PUSH_NOTIFICATION,
                status = ScaStatus.COMPLETED,
                expiresAt = OffsetDateTime.now().plusMinutes(5),
                completedAt = OffsetDateTime.now(),
                consumedAt = null,
                dynamicLinkingData = DynamicLinkingData(
                    null,
                    null,
                    null,
                    null,
                    null,
                    approvalRequestId = PENSION_APPROVAL_REQUEST_ID,
                    payloadSha256 = PENSION_PAYLOAD_SHA256,
                ),
                createdAt = OffsetDateTime.now(),
            ),
        )
        Unit
    }
}
