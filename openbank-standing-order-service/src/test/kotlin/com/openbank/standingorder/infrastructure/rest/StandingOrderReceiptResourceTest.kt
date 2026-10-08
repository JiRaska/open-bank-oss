// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.rest

import com.openbank.standingorder.application.port.`in`.StandingOrderUseCase
import com.openbank.standingorder.infrastructure.rest.dto.StandingOrderReceiptLookupRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class StandingOrderReceiptResourceTest {
    private val useCase: StandingOrderUseCase = mockk()
    private val resource = StandingOrderReceiptResource(useCase)
    private val party = UUID.randomUUID()
    private val account = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val request = StandingOrderReceiptLookupRequest("original-key", account)

    @Test
    fun `receipt route is fixed and role gated`() {
        val route = StandingOrderReceiptResource::class.java.getAnnotation(Path::class.java)
        val method = StandingOrderReceiptResource::class.java.getDeclaredMethod(
            "receiptLookup",
            StandingOrderReceiptLookupRequest::class.java,
            String::class.java,
            String::class.java,
            kotlin.coroutines.Continuation::class.java,
        )
        assertThat(route.value).isEqualTo("/api/v1/standing-orders/receipt-lookup")
        assertThat(method.getAnnotation(POST::class.java)).isNotNull()
        assertThat(method.getAnnotation(RolesAllowed::class.java)).isNotNull()
    }

    @Test
    fun `missing durable receipt is UNKNOWN and never a negative payment claim`(): Unit = runBlocking {
        coEvery { useCase.findBoundReceipt("original-key", party, account, actor) } returns null

        val result = resource.receiptLookup(request, party.toString(), actor.toString())

        assertThat(result.outcome).isEqualTo("UNKNOWN")
        assertThat(result.id).isNull()
        assertThat(result.status).isNull()
    }

    @Test
    fun `missing actor fails before repository lookup`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { resource.receiptLookup(request, party.toString(), null) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { useCase.findBoundReceipt(any(), any(), any(), any()) }
    }
}
