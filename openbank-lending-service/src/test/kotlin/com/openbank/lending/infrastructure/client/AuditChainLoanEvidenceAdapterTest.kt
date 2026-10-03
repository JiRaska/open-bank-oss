// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.infrastructure.client

import com.openbank.lending.application.port.out.LoanEvidenceUnavailable
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.ProcessingException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AuditChainLoanEvidenceAdapterTest {
    @Test
    fun `an unreachable audit-service is unavailable with no status, never an empty bundle`() {
        val client = mockk<AuditEvidenceRestClient>()
        every { client.evidence(any()) } returns Uni.createFrom().failure(ProcessingException("connection refused"))
        assertThatThrownBy { runBlocking { AuditChainLoanEvidenceAdapter(client).bundleFor("x") } }
            .isInstanceOfSatisfying(LoanEvidenceUnavailable::class.java) { assertThat(it.status).isNull() }
    }

    private fun assertThat(v: Int?) = org.assertj.core.api.Assertions.assertThat(v)
}
