// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ClientDtosTest {

    @Test
    fun `ScreenRequest defaults aliases to empty and keeps supplied fields`() {
        val request = ScreenRequest(idempotencyKey = "idem", entityType = "INDIVIDUAL", name = "Alice")

        assertThat(request.idempotencyKey).isEqualTo("idem")
        assertThat(request.entityType).isEqualTo("INDIVIDUAL")
        assertThat(request.name).isEqualTo("Alice")
        assertThat(request.aliases).isEmpty()
    }

    @Test
    fun `ScreenResponse and ScreenMatch default to null and empty`() {
        val empty = ScreenResponse()
        assertThat(empty.status).isNull()
        assertThat(empty.overallScore).isNull()
        assertThat(empty.matches).isEmpty()

        val populated = ScreenResponse(
            status = "HIT",
            overallScore = 0.97,
            matches = listOf(ScreenMatch(matchedName = "BOB / OFAC", matchScore = 0.97)),
        )
        assertThat(populated.status).isEqualTo("HIT")
        assertThat(populated.overallScore).isEqualTo(0.97)
        assertThat(populated.matches).hasSize(1)
        assertThat(populated.matches.first().matchedName).isEqualTo("BOB / OFAC")
        assertThat(populated.matches.first().matchScore).isEqualTo(0.97)
    }

    @Test
    fun `CreateAmlCaseRequest carries the full case payload`() {
        val partyId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        val request = CreateAmlCaseRequest(
            partyId = partyId,
            accountId = accountId,
            transactionId = transactionId,
            customerReference = "Alice / DE89",
            screeningType = "TRANSACTION_MONITORING",
            riskLevel = "CRITICAL",
            alertCode = "SANCTIONS_HIT",
            alertDetail = "OFAC hit",
            matchedEntity = "BOB / OFAC",
        )

        assertThat(request.partyId).isEqualTo(partyId)
        assertThat(request.accountId).isEqualTo(accountId)
        assertThat(request.transactionId).isEqualTo(transactionId)
        assertThat(request.customerReference).isEqualTo("Alice / DE89")
        assertThat(request.screeningType).isEqualTo("TRANSACTION_MONITORING")
        assertThat(request.riskLevel).isEqualTo("CRITICAL")
        assertThat(request.alertCode).isEqualTo("SANCTIONS_HIT")
        assertThat(request.alertDetail).isEqualTo("OFAC hit")
        assertThat(request.matchedEntity).isEqualTo("BOB / OFAC")
    }
}
