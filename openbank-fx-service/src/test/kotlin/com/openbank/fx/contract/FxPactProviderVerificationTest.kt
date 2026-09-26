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
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.fx.application.port.out.FxRateRepository
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.domain.model.RateType
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
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
 * Provider-side verification for the FX rate contract published by transaction-service
 * (ADR-0063 P2 Batch B). Seeds an EUR/CZK SPOT rate so that GET /api/v1/fx/rates/EUR/CZK
 * returns 200 with the expected shape. `@TestSecurity` must grant a role the endpoint's
 * `@RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")` actually
 * accepts — the M2M role is not in that list. Since #2442 that role is `ROLE_API`, granted to
 * `service-account-openbank-services` in both realms; before it, the name in use here was
 * `ROLE_SERVICE`, which no realm declared at all. Either way this endpoint does not admit the
 * M2M principal, so the test must present an operator-grade role: the real M2M caller
 * (transaction-service, via the shared `openbank-services` client) authenticates as
 * `service-account-openbank-services`, which also carries `ROLE_OPERATOR` in the dev realm —
 * that's the role this test presents, or every
 * verification 403s before reaching the resource method (confirmed live: verification result
 * #2247, 2026-07-11, and broken since this test was added on 2026-07-02 without ever being
 * noticed locally, since it's `@EnabledIfSystemProperty(pactbroker.url)`-skipped without a
 * broker).
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_fx_it")],
)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_OPERATOR"])
@Provider("openbank-fx-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class FxPactProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var rateRepo: FxRateRepository

    @Inject
    lateinit var vertx: Vertx

    /**
     * The class-level `@TestSecurity` authenticates every request, which would turn a consumer's
     * 401/403 interaction green-by-accident into a mismatch — or, worse, hide a provider that
     * stopped enforcing authz. For [NO_IDENTITY_STATE] the test identity is cleared in
     * `verifyPacts`, so the request arrives anonymous, exactly as the consumer encoded it.
     */
    @Inject
    lateinit var testIdentity: TestIdentityAssociation

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
        if (context?.interaction?.providerStates?.any { it.name == NO_IDENTITY_STATE } == true) {
            testIdentity.setTestIdentity(null)
        }
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

    /**
     * The negative-auth case (ADR-0279 #3): a request with no identity must be refused, not
     * silently authorised. No committed consumer pact currently exercises this provider state —
     * the handler exists so the boundary is pinned into the contract per the fleet-wide convention
     * ([com.openbank.lending.contract.LoanBookPactState.NO_IDENTITY]) and so a future consumer
     * interaction encoding it verifies immediately without a provider-side change.
     */
    @State(NO_IDENTITY_STATE)
    fun noIdentity() {
        // Intentionally empty: the state IS the absence of an identity (see verifyPacts).
    }

    private companion object {
        const val NO_IDENTITY_STATE = "no valid identity is presented"
    }
}
