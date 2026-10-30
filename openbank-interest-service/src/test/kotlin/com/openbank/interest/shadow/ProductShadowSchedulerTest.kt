// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.shadow

import com.openbank.interest.application.usecase.ProductShadowService
import com.openbank.interest.infrastructure.scheduler.ProductShadowScheduler
import com.openbank.interest.infrastructure.shadow.IrVerificationMode
import com.openbank.interest.infrastructure.shadow.ProductIrModeGuard
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** ADR-0237 liveness and the ADR-0352 §9 shadow-only guard for the interest shadow. */
class ProductShadowSchedulerTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = DomainMetrics().apply {
        registryInstance = mockk<Instance<MeterRegistry>>().also {
            every { it.isResolvable } returns true
            every { it.get() } returns registry
        }
    }
    private val service = mockk<ProductShadowService>().also {
        coEvery { it.intake(any(), any()) } returns 0
        coEvery { it.runOnce(any()) } returns 0
    }

    private fun successRecorded() = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, ProductShadowScheduler.WORKFLOW_NAME).gauge()?.value()

    @Test
    fun `a disabled shadow registers no liveness and does nothing`() {
        val scheduler = ProductShadowScheduler(service, metrics, false, 10, 2, 35)
        assertThat(runBlocking { scheduler.runOnce() }).isZero()
        assertThat(successRecorded()).isNull()
        coVerify(exactly = 0) { service.intake(any(), any()) }
    }

    @Test
    fun `an enabled shadow registers liveness at boot and records success after an empty run`() {
        val scheduler = ProductShadowScheduler(service, metrics, true, 10, 2, 35)
        assertThat(successRecorded()).isEqualTo(0.0)
        runBlocking { scheduler.runOnce() }
        assertThat(successRecorded()).isEqualTo(1.0)
        coVerify { service.intake(35, 20) }
    }

    @Test
    fun `any decision mode other than shadow, and an unknown verification mode, fail the boot`() {
        assertThatThrownBy { ProductIrModeGuard(IrVerificationMode.DISABLED, "ir") }
            .hasMessageContaining("decision-mode=ir is not available")
        assertThatThrownBy { ProductIrModeGuard("plaintext", "shadow") }
            .hasMessageContaining("must be one of")
        ProductIrModeGuard(IrVerificationMode.DISABLED, "shadow")
    }
}
