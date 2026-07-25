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
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.sca.application.port.out.ScaChallengeRepository
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.ScaMethod
import com.openbank.sca.domain.model.ScaPurpose
import com.openbank.sca.domain.model.ScaStatus
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
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Git-pact provider verification for sca-service — the half that actually runs before a merge
 * (issue #2327, gated by `check-pact-provider-replay.py` per #2338). Last provider in that sweep
 * that was unblocked; only the two swift pacts remain, and those are stuck behind #2319.
 *
 * sca-service is the provider for one committed pact: consent-service's
 * `GET /api/v1/sca/challenges/{id}`, which consent-service reads before activating a consent. Its
 * only verification class was [ScaPactProviderVerificationTest] — `@PactBroker`-sourced and
 * `@EnabledIfSystemProperty(pactbroker.url)`-gated. On a pull request that property is empty
 * (`_service-ci.yml` puts the PR lane on `ubuntu-latest` and blanks `PACT_BROKER_URL` off
 * main-push, because the broker has no public ingress, ADR-0056), so it skipped and the contract
 * was replayed only AFTER the merge. A consumer pact cannot catch a wrong request path; only the
 * provider replay can (#2269).
 *
 * ## Additive, not a replacement
 *
 * The broker twin stays as it is: its published verification result is the only thing ADR-0092's
 * `can-i-deploy` reads, and the same swap elsewhere in the fleet blocked deploys for days
 * (party-service #371/#1166, account-service #372/#1166). Git source for the PR lane, broker
 * source for the published result — the pair openbank-ledger-service carries. Two `@Provider`
 * classes collide only when BOTH pull from the broker, since each then fetches every pact it holds.
 *
 * ## What this replay does and does not prove
 *
 * It proves the route exists, answers 200, and returns `id`/`partyId` matching the UUID regex and
 * `purpose`/`status` as strings. It does NOT pin the VALUES of `purpose` and `status`, which are
 * `type` matchers — so a challenge answering with any status verifies green. That is safe here and
 * worth stating why, rather than leaving the next reader to work it out: `ConsentService`
 * .activateConsent fails CLOSED on it (`if (scaChallenge.status != "COMPLETED") throw
 * ConsentScaNotCompletedException`), and `ScaStatus` really does carry `COMPLETED`, so an
 * unexpected status denies activation rather than granting it. The matcher is still weaker than
 * the field deserves; tracked with the other instances on #2425.
 *
 * ## Upkeep
 *
 * A deliberate duplicate of the broker twin's body: same `@State` handler and seeded challenge row.
 * A change to one belongs in the other, or the same contract passes from git and fails from the
 * broker (or the reverse).
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.sca.it.PostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_SERVICE", "ROLE_OPERATOR"])
@Provider("openbank-sca-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class ScaPactFolderProviderVerificationTest {

    companion object {
        private val CHALLENGE_ID = UUID.fromString("99999999-9999-9999-9999-999999999999")
        private val PARTY_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

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
        context?.verifyInteraction()
    }

    @State("a PENDING SCA challenge exists")
    fun statePendingChallengeExists() = runOnVertxContext {
        challengeRepo.save(
            ScaChallenge(
                id = CHALLENGE_ID,
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
}
