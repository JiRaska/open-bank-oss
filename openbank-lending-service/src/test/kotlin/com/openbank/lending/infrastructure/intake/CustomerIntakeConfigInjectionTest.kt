// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.intake

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Does configuration actually reach [CustomerIntakeConfig]?
 *
 * Measured in the sandbox on 2026-08-02: the pod carried `LENDING_INTAKE_ENABLED=true` and the
 * service still answered
 *
 *     403 {"error":"customer self-service intake is disabled"}
 *
 * so `config.enabled` was false while the environment said true. Nothing in the existing suite could
 * see it: `CustomerIntakeResourceTest` constructs `CustomerIntakeConfig(...)` by hand, which tests
 * the refusal logic and says nothing about whether CDI ever populates the bean.
 *
 * The suspicion is Kotlin default parameter values on a CDI bean constructor: they generate a
 * synthetic constructor, and if Arc instantiates through it the `@ConfigProperty` annotations are
 * never consulted and every field silently takes its Kotlin default. That failure mode is invisible
 * — the bean exists, injection "succeeds", and the endpoint simply behaves as if it were switched
 * off, which is indistinguishable from someone having switched it off on purpose.
 *
 * This test asserts against values that are NOT the Kotlin defaults, so it can only pass if the
 * configuration was really applied. Asserting `enabled == true` alone would be weak — `true` is not
 * the default, but a single boolean could flip for other reasons; the string, the Optional and the
 * BigDecimal together pin down that the whole bean was populated from config rather than from the
 * constructor's fallbacks.
 */
@QuarkusTest
@TestProfile(CustomerIntakeConfigInjectionTest.Profile::class)
class CustomerIntakeConfigInjectionTest {

    class Profile : QuarkusTestProfile {
        // Literals, not computed values: a QuarkusTestProfile loads in a DIFFERENT classloader from
        // the test class, so anything derived here would be produced twice and the two copies need
        // not agree.
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "lending.intake.enabled" to "true",
            "lending.intake.caller-principal" to "service-account-openbank-edge",
            "lending.intake.jurisdiction" to "SK",
            "lending.intake.product-type" to "CONSUMER_CREDIT",
            "lending.intake.currency" to "EUR",
            "lending.intake.nominal-annual-rate" to "0.123",
            "lending.intake.min-amount" to "77",
            "lending.intake.max-term-months" to "84",
        )
    }

    @Inject
    lateinit var config: CustomerIntakeConfig

    @Test
    fun `every field comes from configuration, not from the Kotlin constructor defaults`() {
        // enabled: Kotlin default false
        assertThat(config.enabled).describedAs("lending.intake.enabled").isTrue()
        // callerPrincipal: Kotlin default Optional.empty — a blank one refuses every caller, so this
        // field being unpopulated would lock the endpoint shut even with `enabled` true.
        assertThat(config.callerPrincipal).contains("service-account-openbank-edge")
        // jurisdiction/currency: Kotlin defaults CZ/CZK. Deliberately overridden to SK/EUR here so a
        // pass cannot come from the default happening to equal the deployed value — which is exactly
        // what hid this for jurisdiction and productType in production.
        assertThat(config.jurisdiction).isEqualTo("SK")
        assertThat(config.currency).isEqualTo("EUR")
        // nominalAnnualRate: Kotlin default Optional.empty; an empty one refuses as "no configured price".
        assertThat(config.nominalAnnualRate).hasValueSatisfying {
            assertThat(it).isEqualByComparingTo(BigDecimal("0.123"))
        }
        // Numeric bounds: Kotlin defaults 5000 and 120.
        assertThat(config.minAmount).isEqualByComparingTo(BigDecimal("77"))
        assertThat(config.maxTermMonths).isEqualTo(84)
    }
}
