// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

import com.openbank.sepainstant.application.port.out.AmlCaseRiskLevel
import com.openbank.sepainstant.application.port.out.OpenAmlCaseCommand
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for the AML case adapter (ADR-0032 §D). Failures must propagate — the use-case's
 * best-effort wrapper decides what to do with them; swallowing here would hide a case-store outage.
 */
class AmlCaseAdapterTest {

    private val client = mockk<AmlServiceClient>()
    private val adapter = AmlCaseAdapter(client).also { it.self = it }

    private val paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val debtorAccountId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun command(
        alertDetail: String? = "creditor 'Bob' HIT score=0.97",
        matchedEntity: String? = "BOB CREDITOR / OFAC",
    ) = OpenAmlCaseCommand(
        idempotencyKey = "aml-$paymentId-SANCTIONS_HIT",
        paymentId = paymentId,
        debtorAccountId = debtorAccountId,
        customerReference = "Alice Debtor / DE89370400440532013000",
        riskLevel = AmlCaseRiskLevel.CRITICAL,
        alertCode = "SANCTIONS_HIT",
        alertDetail = alertDetail,
        matchedEntity = matchedEntity,
    )

    @Test
    fun `openCase maps the payment boundary command onto the aml-service contract`() {
        val keySlot = slot<String>()
        val requestSlot = slot<CreateAmlCaseRequest>()
        every { client.createCase(capture(keySlot), capture(requestSlot)) } returns
            Uni.createFrom().item(Response.status(201).build())

        adapter.openCase(command()).await().indefinitely()

        assertThat(keySlot.captured).isEqualTo("aml-$paymentId-SANCTIONS_HIT")
        val request = requestSlot.captured
        // ADR-0032 §D fast-follow: the payment carries no resolved party, so the debtor account
        // fills both partyId and accountId until account→party resolution lands.
        assertThat(request.partyId).isEqualTo(debtorAccountId)
        assertThat(request.accountId).isEqualTo(debtorAccountId)
        assertThat(request.transactionId).isEqualTo(paymentId)
        assertThat(request.customerReference).isEqualTo("Alice Debtor / DE89370400440532013000")
        assertThat(request.screeningType).isEqualTo("TRANSACTION_MONITORING")
        assertThat(request.riskLevel).isEqualTo("CRITICAL")
        assertThat(request.alertCode).isEqualTo("SANCTIONS_HIT")
        assertThat(request.alertDetail).isEqualTo("creditor 'Bob' HIT score=0.97")
        assertThat(request.matchedEntity).isEqualTo("BOB CREDITOR / OFAC")
    }

    @Test
    fun `openCase passes nullable alert fields through unchanged`() {
        val requestSlot = slot<CreateAmlCaseRequest>()
        every { client.createCase(any(), capture(requestSlot)) } returns
            Uni.createFrom().item(Response.status(201).build())

        adapter.openCase(command(alertDetail = null, matchedEntity = null)).await().indefinitely()

        assertThat(requestSlot.captured.alertDetail).isNull()
        assertThat(requestSlot.captured.matchedEntity).isNull()
    }

    @Test
    fun `a case-store failure propagates instead of being swallowed`() {
        val boom = RuntimeException("aml-service unreachable")
        every { client.createCase(any(), any()) } returns Uni.createFrom().failure(boom)

        assertThatThrownBy { adapter.openCase(command()).await().indefinitely() }
            .isSameAs(boom)
    }
}
