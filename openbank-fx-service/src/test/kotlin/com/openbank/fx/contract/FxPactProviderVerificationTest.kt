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
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Provider-side verification for the FX rate contract published by transaction-service
 * (ADR-0063 P2 Batch B). Seeds an EUR/CZK SPOT rate so that GET /api/v1/fx/rates/EUR/CZK
 * returns 200 with the expected shape. The `@TestSecurity` satisfies the OIDC check on the
 * rates endpoint (service role required per ADR-0018).
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.fx.it.PostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_SERVICE"])
@Provider("openbank-fx-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class FxPactProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var rateRepo: FxRateRepository

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

    @State("an EUR/CZK rate exists")
    fun stateEurCzkRateExists(): Unit = runBlocking {
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
}
