// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.intake

import com.openbank.lending.infrastructure.compliance.OriginationConfig
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The single-default case of the CustomerIntakeConfig defect.
 *
 * [CustomerIntakeConfigInjectionTest] proved that a bean whose constructor parameters ALL carry
 * Kotlin defaults never receives its configuration. It did not prove anything about a bean with
 * ONE defaulted parameter, and the two are not obviously the same: a default anywhere is enough to
 * generate the synthetic constructor, but whether Arc then prefers it is a question for the runtime,
 * not for reasoning.
 *
 * It matters because the same shape guards two flags elsewhere in the fleet that are false by
 * default and dangerous when believed:
 * `openbank.ml.require-signature` (fraud model) and `mcp.obo.enabled`. A flag that cannot be turned
 * on is worse than one that is off, because the operator who turns it on stops looking.
 */
@QuarkusTest
@TestProfile(OriginationConfigInjectionTest.Profile::class)
class OriginationConfigInjectionTest {

    class Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("lending.origination.auto-approve" to "true")
    }

    @Inject
    lateinit var config: OriginationConfig

    @Test
    fun `a single Kotlin default does not stop configuration reaching the bean`() {
        assertThat(config.autoApprove)
            .describedAs("lending.origination.auto-approve set to true in the profile")
            .isTrue()
    }
}
