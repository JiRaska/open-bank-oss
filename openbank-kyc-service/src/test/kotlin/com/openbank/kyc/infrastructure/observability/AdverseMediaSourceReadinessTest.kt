// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.observability

import com.openbank.kyc.application.port.out.AdverseMediaOutcome
import com.openbank.kyc.application.port.out.AdverseMediaScreeningPort
import com.openbank.kyc.application.port.out.AdverseMediaScreeningResult
import com.openbank.kyc.infrastructure.client.UnconfiguredAdverseMediaSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AdverseMediaSourceReadinessTest {

    private fun readiness(port: AdverseMediaScreeningPort, registry: MeterRegistry?): AdverseMediaSourceReadiness {
        val instance: Instance<MeterRegistry> = mockk(relaxed = true)
        every { instance.isResolvable } returns (registry != null)
        if (registry != null) every { instance.get() } returns registry
        return AdverseMediaSourceReadiness().also {
            it.port = port
            it.registryInstance = instance
        }
    }

    private fun configuredPort(): AdverseMediaScreeningPort = object : AdverseMediaScreeningPort {
        override val sourceId: String = "some-real-feed"
        override suspend fun screen(name: String, idempotencyKey: String) =
            AdverseMediaScreeningResult(AdverseMediaOutcome.NO_HIT, sourceId)
    }

    @Test
    fun `gauge reads 0 on the platform as shipped — no adverse-media source exists`() {
        val registry = SimpleMeterRegistry()
        readiness(UnconfiguredAdverseMediaSource(), registry).register()

        val gauge = registry.find("openbank_kyc_adverse_media_source_configured").gauge()
        assertThat(gauge).isNotNull()
        assertThat(gauge!!.value()).isEqualTo(0.0)
    }

    @Test
    fun `gauge reads 1 once a source backs the port`() {
        val registry = SimpleMeterRegistry()
        readiness(configuredPort(), registry).register()

        assertThat(registry.find("openbank_kyc_adverse_media_source_configured").gauge()!!.value()).isEqualTo(1.0)
    }

    @Test
    fun `registration is safe when no meter registry is resolvable`() {
        readiness(UnconfiguredAdverseMediaSource(), null).register()
    }
}
