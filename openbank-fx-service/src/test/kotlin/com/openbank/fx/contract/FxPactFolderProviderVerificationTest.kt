// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.fx.application.port.out.FxRateRepository
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.domain.model.RateType
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
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Git-pact provider verification for fx-service — the half that actually runs before a merge
 * (issue #2327, gated by `check-pact-provider-replay.py` per #2338).
 *
 * fx-service is the provider for one committed pact: transaction-service's
 * `GET /api/v1/fx/rates/EUR/CZK`, the rate a cross-currency transaction converts at. Its only
 * verification class was [FxPactProviderVerificationTest] — `@PactBroker`-sourced and
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
 * It proves the route exists, answers 200, and returns the four fields with the right types. It
 * does NOT pin the rate values (`$.askRate`/`$.bidRate` are `decimal` matchers, correctly — rates
 * move) and it does not pin `$.baseCurrency`/`$.quoteCurrency`, which are `type` matchers: a
 * response echoing the wrong currency pair would verify green. That is a property of the consumer's
 * contract, not of this class; see the matcher-quality thread on #2425.
 *
 * ## Upkeep
 *
 * A deliberate duplicate of the broker twin's body: same `@State` handler and seeded rate row. A
 * change to one belongs in the other, or the same contract passes from git and fails from the
 * broker (or the reverse).
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.fx.it.PostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_OPERATOR"])
@Provider("openbank-fx-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class FxPactFolderProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var rateRepo: FxRateRepository

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
     * Vert.x context — `Panache.withTransaction`/`withSession` (used by [rateRepo]) requires one,
     * so a bare `runBlocking { rateRepo.save(...) }` throws `IllegalStateException: No current
     * Vertx context found`. Same class of bug found live in sca-service's
     * `ScaPactProviderVerificationTest` (blocking consent-service's deploy); this file has the
     * identical pattern. Same fix as balance-service's `BalancePactProviderVerificationTest`
     * (which documents why a plain `vertx.runOnContext { runBlocking { ... } }` is NOT sufficient).
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

    @State("an EUR/CZK rate exists")
    fun stateEurCzkRateExists() = runOnVertxContext {
        // pact-jvm 4.7.3 invokes each @State SETUP callback twice per interaction (same behavior
        // documented in party-service's PartyEventPactProviderVerificationTest) — without this
        // guard, a second run.save() would insert a second, duplicate EUR/CZK SPOT row (no unique
        // constraint on the pair/type/source/validFrom, so it wouldn't even fail loudly).
        if (rateRepo.findLatest("EUR", "CZK", RateType.SPOT) != null) return@runOnVertxContext
        val now = Instant.now()
        rateRepo.save(
            FxRate(
                id = UUID.randomUUID(),
                baseCurrency = "EUR",
                quoteCurrency = "CZK",
                bidRate = BigDecimal("24.80"),
                askRate = BigDecimal("25.20"),
                rateType = RateType.SPOT,
                source = RateSource.CNB,
                validFrom = now.minusSeconds(3600),
                validTo = now.plusSeconds(86400),
                createdAt = now,
            ),
        )
        Unit
    }

    /**
     * #3921: the fixing ledger's revaluation asks for by business day. The window must CONTAIN the
     * start of 2026-05-27 in the ČNB publication zone, because that is the instant
     * `CnbRateIngestionService.getCnbRate(asOf)` compares against — seeding "some CNB row" would
     * verify a query this interaction is not about.
     */
    @State("an EUR/CZK CNB fixing is in effect on 2026-05-27")
    fun stateEurCzkCnbFixingAsOf() = runOnVertxContext {
        val validFrom = LocalDate.of(2026, 5, 27).atStartOfDay(ZoneId.of("Europe/Prague")).toInstant()
        // Same double-invocation guard as the sibling state above: pact-jvm 4.7.3 runs each SETUP
        // callback twice per interaction and there is no unique constraint to make a duplicate loud.
        if (rateRepo.findBySourceAndValidFrom("EUR", "CZK", RateSource.CNB, validFrom) != null) {
            return@runOnVertxContext
        }
        rateRepo.save(
            FxRate(
                id = UUID.randomUUID(),
                baseCurrency = "EUR",
                quoteCurrency = "CZK",
                bidRate = BigDecimal("24.90"),
                askRate = BigDecimal("24.90"),
                rateType = RateType.INDICATIVE,
                source = RateSource.CNB,
                validFrom = validFrom,
                // Three days, matching CnbRateIngestionService.CNB_VALIDITY_DAYS — the window that
                // carries a Friday fixing across a weekend.
                validTo = validFrom.plus(Duration.ofDays(3)),
                createdAt = Instant.now(),
            ),
        )
        Unit
    }
}
