// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.openbank.audit.application.port.out.AnchorSigner
import com.openbank.audit.infrastructure.persistence.AuditAnchorRepository
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.infrastructure.persistence.ChainHead
import com.openbank.audit.infrastructure.persistence.ChainVerification
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.runtime.StartupEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AuditAnchorServiceLivenessTest {

    private val auditRepository = mockk<AuditRepository>()
    private val anchorRepository = mockk<AuditAnchorRepository>()
    private val signer = mockk<AnchorSigner>()
    private val metrics = mockk<DomainMetrics>()
    private val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)

    private fun service(enabled: Boolean, signingRequired: Boolean = false) = AuditAnchorService(
        auditRepo = auditRepository,
        anchorRepo = anchorRepository,
        signer = signer,
        clock = Clock.fixed(Instant.parse("2026-08-16T05:00:00Z"), ZoneOffset.UTC),
        enabled = enabled,
        signingRequired = signingRequired,
        domainMetrics = metrics,
    )

    @Test
    fun `registers heartbeat and records success after completed capture`(): Unit = runBlocking {
        val service = service(enabled = true)
        every { metrics.registerWorkflowLiveness(any(), any()) } returns liveness
        coEvery { auditRepository.chainHead() } returns null

        service.registerLiveness(StartupEvent())
        service.captureScheduled()

        verify(exactly = 1) { metrics.registerWorkflowLiveness("audit-anchor-capture", any()) }
        verify(exactly = 1) { liveness.recordSuccess() }
    }

    @Test
    fun `caught capture failure records no liveness success`(): Unit = runBlocking {
        val service = service(enabled = true)
        every { metrics.registerWorkflowLiveness(any(), any()) } returns liveness
        coEvery { auditRepository.chainHead() } throws IllegalStateException("db down")

        service.registerLiveness(StartupEvent())
        service.captureScheduled()

        verify(exactly = 0) { liveness.recordSuccess() }
    }

    @Test
    fun `disabled capture records no liveness success`(): Unit = runBlocking {
        val service = service(enabled = false)
        every { metrics.registerWorkflowLiveness(any(), any()) } returns liveness

        service.registerLiveness(StartupEvent())
        service.captureScheduled()

        verify(exactly = 0) { liveness.recordSuccess() }
    }

    @Test
    fun `required signer failure stores no unsigned anchor and records no success`(): Unit = runBlocking {
        val service = service(enabled = true, signingRequired = true)
        every { metrics.registerWorkflowLiveness(any(), any()) } returns liveness
        every { signer.keyId } returns "kms-test-key"
        every { signer.sign(any()) } throws IllegalStateException("kms unavailable")
        coEvery { auditRepository.chainHead() } returns ChainHead(java.util.UUID.randomUUID(), "a".repeat(64), 1)
        coEvery { auditRepository.verifyChain() } returns ChainVerification(intact = true, checked = 1, unchained = 0)

        service.registerLiveness(StartupEvent())
        service.captureScheduled()

        coVerify(exactly = 0) { anchorRepository.save(any()) }
        verify(exactly = 0) { liveness.recordSuccess() }
    }
}
