// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import com.openbank.fx.application.port.out.AmlCaseRiskLevel
import com.openbank.fx.application.port.out.OpenAmlCaseCommand
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class AmlCaseAdapterTest {

    private val client = mockk<AmlServiceClient>()
    private val adapter = AmlCaseAdapter(client).also { it.self = it }

    private fun command(alertCode: String = "SANCTIONS_HIT") = OpenAmlCaseCommand(
        idempotencyKey = "aml-1-$alertCode",
        conversionId = UUID.randomUUID(),
        partyId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        customerReference = "party EUR->CZK 10000",
        riskLevel = AmlCaseRiskLevel.CRITICAL,
        alertCode = alertCode,
        alertDetail = "OFAC SDN match",
        matchedEntity = "OFAC SDN",
    )

    @Test
    fun `openCase maps the command onto the aml-service request with the idempotency-key header`() {
        val cmd = command()
        val request = slot<CreateAmlCaseRequest>()
        val key = slot<String>()
        every { client.createCase(capture(key), capture(request)) } returns
            Uni.createFrom().item(Response.status(201).build())

        runBlocking { adapter.openCase(cmd) }

        assertThat(key.captured).isEqualTo(cmd.idempotencyKey)
        assertThat(request.captured.partyId).isEqualTo(cmd.partyId)
        assertThat(request.captured.accountId).isEqualTo(cmd.accountId)
        assertThat(request.captured.transactionId).isEqualTo(cmd.conversionId)
        assertThat(request.captured.riskLevel).isEqualTo("CRITICAL")
        assertThat(request.captured.alertCode).isEqualTo("SANCTIONS_HIT")
        assertThat(request.captured.matchedEntity).isEqualTo("OFAC SDN")
        assertThat(request.captured.screeningType).isEqualTo("TRANSACTION_MONITORING")
        verify(exactly = 1) { client.createCase(any(), any()) }
    }

    @Test
    fun `a transport failure propagates so the caller's best-effort wrapper can log it`() {
        every { client.createCase(any(), any()) } returns
            Uni.createFrom().failure(RuntimeException("aml-service down"))

        assertThatThrownBy {
            runBlocking { adapter.openCase(command()) }
        }.isInstanceOf(RuntimeException::class.java).hasMessage("aml-service down")
    }
}
