// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.pensionfund.application.port.PensionFundStore
import com.openbank.pensionfund.application.port.StoreChanges
import com.openbank.pensionfund.domain.model.Fund
import com.openbank.pensionfund.domain.model.FundStatus
import com.openbank.pensionfund.domain.model.NavFigures
import com.openbank.pensionfund.domain.model.NavPosition
import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
import com.openbank.pensionfund.domain.model.UnitTransaction
import com.openbank.pensionfund.domain.model.UnitTransactionType
import com.openbank.pensionfund.it.PostgresTestResource
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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Git-pact provider replay for pension-fund-service (#12425): tax-reporting-service's consumer
 * pact for `GET /api/v1/reporting/funds/{fundId}/period-figures`, replayed on every PR. A consumer
 * pact cannot catch a wrong request path — only this replay can (#2269).
 *
 * Replays AS tax-reporting's own client (`service-account-openbank-tax-reporting`, ROLE_API), the
 * identity the route admits in pension_fund_rest_ext.rego. The 401 interaction is replayed by
 * [PensionFundNegativeAuthProviderVerificationTest], which boots without an identity.
 *
 * Until tax-reporting's consumer pact is committed (the stacked tax-reporting PR), there is nothing
 * to replay and `@IgnoreNoPactsToVerify` keeps this class green rather than absent.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-tax-reporting", roles = ["ROLE_API"])
@Provider("openbank-pension-fund-service")
@PactFolder("../pacts")
@PactFilter("^(?!" + NEGATIVE_AUTH_STATE + "\$).*\$")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class PensionFundPactFolderProviderVerificationTest {

    companion object {
        /** Must match TaxReportingPensionFundPactConsumerTest (openbank-tax-reporting-service). */
        val FUND_ID: UUID = UUID.fromString("f0a7c1e2-0000-4000-8000-000000012425")
        const val FUND_STATE = "a pension fund with a published September 2026 NAV exists"
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var store: PensionFundStore

    @Inject
    lateinit var vertx: Vertx

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

    /** Reactive Panache needs a Vert.x context, which Pact-JVM's state callback thread lacks. */
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

    /** One fund, one published 30 September NAV struck on one position, one subscription priced at it. */
    @State(FUND_STATE)
    fun fundWithSeptemberNav() = runOnVertxContext {
        if (store.fund(FUND_ID) != null) return@runOnVertxContext
        val at = Instant.parse("2026-09-30T17:00:00Z")
        val fund = Fund(
            id = FUND_ID, name = "Pact Fund", isin = "CZ0001242500", lei = "315700ABCDEF12345678",
            depositaryReference = "DEP", custodyAccountReference = "CUST-PACT", currency = "CZK", riskClass = 3,
            mandatoryConservative = false, managementFeeRate = BigDecimal("0.008"), launchNavPerUnit = BigDecimal.ONE,
            status = FundStatus.ACTIVE, createdAt = at, updatedAt = at,
        )
        val navId = UUID.fromString("f0a7c1e2-0000-4000-8000-0000000a0930")
        val nav = NavRecord(
            id = navId, fundId = FUND_ID, valuationDate = LocalDate.parse("2026-09-30"),
            figures = NavFigures(
                grossAssets = BigDecimal("1000.00"),
                accruedManagementFee = BigDecimal("0.00"),
                otherLiabilities = BigDecimal("0.00"),
                netAssets = BigDecimal("1000.00"),
                unitsOutstanding = BigDecimal("1000.000000"),
                navPerUnit = BigDecimal("1.000000"),
            ),
            status = NavStatus.PUBLISHED, calculatedBy = "maker", calculatedAt = at, approvedBy = "checker",
            publishedAt = at, positionsRecorded = true,
        )
        val subscription = UnitTransaction(
            id = UUID.randomUUID(), orderId = null, contractId = UUID.randomUUID(), fundId = FUND_ID,
            type = UnitTransactionType.SUBSCRIBE, units = BigDecimal("1000.000000"), amount = BigDecimal("1000.00"),
            navId = navId, navPerUnit = BigDecimal("1.000000"), pricedAt = at,
        )
        store.commit(
            StoreChanges(
                funds = listOf(fund),
                navs = listOf(nav),
                transactions = listOf(subscription),
                navPositions = listOf(NavPosition(navId, "CZ-BOND-1", BigDecimal("10"), BigDecimal("100"))),
            ),
        )
    }
}

/** The state name every consumer of this provider uses for its missing-identity interaction. */
const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
