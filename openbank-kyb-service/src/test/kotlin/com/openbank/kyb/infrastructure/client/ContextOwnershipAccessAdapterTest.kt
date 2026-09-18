// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.client

import com.openbank.kyb.application.port.out.UboObservationAccessDecision
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ContextOwnershipAccessAdapterTest {
    private val client = mockk<ContextOwnershipAccessRestClient>()
    private val caseId = UUID.randomUUID()
    private val bearer = "Bearer synthetic-test-token"

    @Test
    fun `staged source keeps the legacy check until the data-free endpoint is deployed`() {
        coEvery { client.history(caseId, bearer, caseId.toString(), PURPOSE) } returns Response.ok().build()

        assertThat(runBlocking { ContextOwnershipAccessAdapter(client, false).check(caseId, bearer) })
            .isEqualTo(UboObservationAccessDecision.ALLOWED)
        coVerify(exactly = 1) { client.history(caseId, bearer, caseId.toString(), PURPOSE) }
        coVerify(exactly = 0) { client.access(any(), any(), any(), any()) }
    }

    @Test
    fun `data-free check accepts only a 204 and never reads history`() {
        coEvery { client.access(caseId, bearer, caseId.toString(), PURPOSE) } returns Response.noContent().build()

        assertThat(runBlocking { ContextOwnershipAccessAdapter(client, true).check(caseId, bearer) })
            .isEqualTo(UboObservationAccessDecision.ALLOWED)
        coVerify(exactly = 0) { client.history(any(), any(), any(), any()) }
    }

    @Test
    fun `data-free check fails closed without falling back to history`() {
        coEvery { client.access(caseId, bearer, caseId.toString(), PURPOSE) } returns
            Response.status(Response.Status.SERVICE_UNAVAILABLE).build()

        assertThat(runBlocking { ContextOwnershipAccessAdapter(client, true).check(caseId, bearer) })
            .isEqualTo(UboObservationAccessDecision.UNAVAILABLE)
        coVerify(exactly = 0) { client.history(any(), any(), any(), any()) }
    }

    private companion object {
        const val PURPOSE = "KYB_OWNERSHIP_REVIEW"
    }
}
