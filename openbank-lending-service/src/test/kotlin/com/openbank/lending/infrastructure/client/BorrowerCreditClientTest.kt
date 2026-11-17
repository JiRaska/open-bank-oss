// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import com.openbank.libs.domain.money.Money
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class BorrowerCreditClientTest {

    @Test
    fun `same key succeeds once the existing credit is completed`() {
        val transactionId = UUID.randomUUID()
        val requests = mutableListOf<InitiateTransactionBody>()
        val acknowledgements = ArrayDeque(
            listOf(TransactionAck(transactionId, "PENDING"), TransactionAck(transactionId, "COMPLETED")),
        )
        val client = object : TransactionServiceRestClient {
            override fun initiate(request: InitiateTransactionBody): Uni<TransactionAck> {
                requests += request
                return Uni.createFrom().item(acknowledgements.removeFirst())
            }
        }
        val adapter = BorrowerCreditClient(client, Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC))
        val reference = "loan:synthetic-fixture:disbursement-credit"
        val accountId = UUID.randomUUID()
        val amount = Money.of(BigDecimal("12000.00"), "EUR")

        assertThatThrownBy { adapter.credit(reference, accountId, amount).await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Borrower credit is not confirmed")
        assertThat(adapter.credit(reference, accountId, amount).await().indefinitely()).isEqualTo(Unit)

        val originalRequest = requests.first()
        assertThat(originalRequest.idempotencyKey).isEqualTo(reference)
        assertThat(originalRequest.targetAccountId).isEqualTo(accountId)
        assertThat(requests).containsExactly(originalRequest, originalRequest)
    }
}
