// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.account.infrastructure.rest

import com.openbank.account.application.usecase.BusinessPaymentAuthorityDecision
import com.openbank.account.application.usecase.BusinessPaymentAuthorityOutcome
import com.openbank.account.application.usecase.BusinessPaymentAuthorityService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.UUID

class BusinessPaymentAuthorityContractTest {
    @Test
    fun `sole decision names the exact owner and contract declares the additive route`(): Unit = runBlocking {
        val account = UUID.randomUUID()
        val human = UUID.randomUUID()
        val company = UUID.randomUUID()
        val service: BusinessPaymentAuthorityService = mockk()
        coEvery { service.decide(account, human) } returns
            BusinessPaymentAuthorityDecision(BusinessPaymentAuthorityOutcome.SOLE, company)

        val body = BusinessPaymentAuthorityResource(service).check(account, human)
        assertThat(body).isEqualTo(BusinessPaymentAuthorityResponse(true, "SOLE", company))
        val contract = File("src/main/resources/openapi.yaml").readText()
        assertThat(contract).contains("version: 1.19.0", "/api/v1/accounts/{accountId}/business-payment-authorization:")
        assertThat(contract).contains("operationId: checkBusinessPaymentAuthorization", "actorPartyId")
    }

    @Test
    fun `missing actor is a client error before authority lookup`() {
        val service: BusinessPaymentAuthorityService = mockk()
        val resource = BusinessPaymentAuthorityResource(service)
        assertThrows<IllegalArgumentException> {
            runBlocking { resource.check(UUID.randomUUID(), null) }
        }
    }
}
