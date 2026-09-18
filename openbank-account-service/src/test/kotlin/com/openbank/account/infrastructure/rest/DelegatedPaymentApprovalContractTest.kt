// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.port.`in`.AuthorizationUseCase
import com.openbank.account.application.port.`in`.DelegatedPaymentDecision
import com.openbank.account.application.port.`in`.DelegatedPaymentOutcome
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class DelegatedPaymentApprovalContractTest {
    @Test
    fun `new refusal is additive on the wire and old outcome enum stays closed`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val useCase: AuthorizationUseCase = mockk()
        coEvery { useCase.authorizeDelegatedPayment(accountId, partyId, null) } returns
            DelegatedPaymentDecision(DelegatedPaymentOutcome.APPROVAL_REQUIRED)

        val body = DelegatedPaymentAuthorizationResource(useCase).check(accountId, partyId, null, null).entity
        assertThat(body).isInstanceOf(Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val fields = body as Map<String, Any?>
        assertThat(fields["authorized"]).isEqualTo(false)
        assertThat(fields["outcome"]).isEqualTo("NO_GRANT")
        assertThat(fields["approvalRequired"]).isEqualTo(true)
        assertThat(fields).doesNotContainKeys("delegationId", "grantorPartyId")

        val contract = File("src/main/resources/openapi.yaml").readText()
        assertThat(contract).contains("version: 1.19.0", "approvalRequired:")
        assertThat(contract).doesNotContain("- APPROVAL_REQUIRED")
    }
}
