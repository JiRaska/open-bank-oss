// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.domain.model

import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class SepaSettlementCompletionTest {

    private val tx = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val otherTx = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
    private val completedAt = Instant.parse("2026-01-03T10:15:30Z")

    @Test
    fun `an unchanged processing request is accepted`() {
        val current = payment()
        SepaSettlementCompletion.validateRequest(current, current, tx)
        SepaSettlementCompletion.validateRequest(current.copy(transactionId = tx), current, tx)
        SepaSettlementCompletion.validateRequest(current.copy(returnIntent = intent(original = tx)), current, tx)
    }

    @Test
    fun `every request identity and value field is compared`() {
        val current = payment()
        val drifted = listOf(
            "status" to current.copy(status = SepaPaymentStatus.VALIDATED),
            "idempotencyKey" to current.copy(idempotencyKey = "idem-2"),
            "debtorAccountId" to current.copy(debtorAccountId = UUID.randomUUID()),
            "type" to current.copy(type = SepaPaymentType.SCT_INST),
            "amount" to current.copy(amount = Money.of(BigDecimal("10.01"), "EUR")),
            "currency" to current.copy(amount = Money.of(BigDecimal("10.00"), "CZK")),
            "remittanceInfo" to current.copy(remittanceInfo = "invoice 2"),
            "endToEndId" to current.copy(endToEndId = "E2E2"),
        )
        for ((field, requested) in drifted) {
            assertThatThrownBy { SepaSettlementCompletion.validateRequest(current, requested, tx) }
                .describedAs(field)
                .isInstanceOf(ResourceConflictException::class.java)
                .hasMessage("Settlement request no longer matches the payment")
        }
    }

    @Test
    fun `settlement never replaces an already recorded transaction identity`() {
        val current = payment().copy(transactionId = otherTx)
        assertThatThrownBy { SepaSettlementCompletion.validateRequest(current, payment(), tx) }
            .isInstanceOf(ResourceConflictException::class.java)
            .hasMessage("Settlement cannot replace the original transaction identity")
    }

    @Test
    fun `settlement must agree with the return's original identity`() {
        val current = payment().copy(returnIntent = intent(original = otherTx))
        assertThatThrownBy { SepaSettlementCompletion.validateRequest(current, payment(), tx) }
            .isInstanceOf(ResourceConflictException::class.java)
            .hasMessage("Settlement conflicts with the return's original identity")
    }

    @Test
    fun `replay is compatible only for the same settled transaction`() {
        val completed = payment(SepaPaymentStatus.COMPLETED).copy(transactionId = tx)
        assertThat(SepaSettlementCompletion.isCompatibleReplay(completed, tx)).isTrue()
        assertThat(SepaSettlementCompletion.isCompatibleReplay(completed, otherTx)).isFalse()
        assertThat(SepaSettlementCompletion.isCompatibleReplay(completed.copy(transactionId = null), tx)).isFalse()
        assertThat(
            SepaSettlementCompletion.isCompatibleReplay(payment().copy(transactionId = tx), tx),
        ).isFalse()
    }

    @Test
    fun `a returned payment replays only when its resolved return names that original`() {
        val resolved = intent(original = tx).copy(resolvedAt = completedAt, reversalTransactionId = otherTx)
        val returned = payment(SepaPaymentStatus.RETURNED).copy(transactionId = tx, returnIntent = resolved)
        assertThat(SepaSettlementCompletion.isCompatibleReplay(returned, tx)).isTrue()
        assertThat(
            SepaSettlementCompletion.isCompatibleReplay(
                returned.copy(returnIntent = resolved.copy(resolvedAt = null)),
                tx,
            ),
        ).isFalse()
        assertThat(
            SepaSettlementCompletion.isCompatibleReplay(
                returned.copy(returnIntent = resolved.copy(originalTransactionId = otherTx)),
                tx,
            ),
        ).isFalse()
        assertThat(SepaSettlementCompletion.isCompatibleReplay(returned.copy(returnIntent = null), tx)).isFalse()
    }

    @Test
    fun `completion records the transaction and the settlement instant`() {
        val before = payment()
        val done = SepaSettlementCompletion.completed(before, tx, completedAt)
        assertThat(done.status).isEqualTo(SepaPaymentStatus.COMPLETED)
        assertThat(done.transactionId).isEqualTo(tx)
        assertThat(done.completedAt).isEqualTo(completedAt)
        assertThat(done.updatedAt).isEqualTo(completedAt)
        assertThat(done.revision).isEqualTo(before.revision + 1)
        assertThat(done.returnIntent).isNull()
    }

    @Test
    fun `completion proves a pending return's original and narrows only the unknown-original cause`() {
        val unknown = payment().copy(returnIntent = intent(original = null, cause = "UNRESOLVED_ORIGINAL_TRANSACTION"))
        val proven = SepaSettlementCompletion.completed(unknown, tx, completedAt).returnIntent!!
        assertThat(proven.originalTransactionId).isEqualTo(tx)
        assertThat(proven.unresolvedCause).isEqualTo("REVERSAL_UNPROVEN")
        assertThat(proven.firstReceivedAt).isEqualTo(unknown.returnIntent!!.firstReceivedAt)

        val other = payment().copy(returnIntent = intent(original = null, cause = "SCHEME_TIMEOUT"))
        assertThat(SepaSettlementCompletion.completed(other, tx, completedAt).returnIntent!!.unresolvedCause)
            .isEqualTo("SCHEME_TIMEOUT")
    }

    @Test
    fun `completion refuses to overwrite any non-processing state`() {
        for (status in SepaPaymentStatus.entries - SepaPaymentStatus.PROCESSING) {
            assertThatThrownBy { SepaSettlementCompletion.completed(payment(status), tx, completedAt) }
                .describedAs(status.name)
                .isInstanceOf(ResourceConflictException::class.java)
                .hasMessage("Settlement cannot overwrite a conflicting terminal state")
        }
    }

    private fun intent(original: UUID?, cause: String = "REVERSAL_UNPROVEN") = SepaReturnIntent(
        originalTransactionId = original,
        firstReceivedAt = Instant.parse("2026-01-02T00:00:00Z"),
        reasonCode = "AM09",
        actorId = "actor",
        actorType = "ROLE_API",
        correlationId = null,
        unresolvedCause = cause,
    )

    private fun payment(status: SepaPaymentStatus = SepaPaymentStatus.PROCESSING) = SepaPayment(
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        idempotencyKey = "idem",
        type = SepaPaymentType.SCT,
        status = status,
        debtorAccountId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
        debtorIban = "CZ6500000000000000000001",
        debtorName = "Debtor",
        creditorIban = "DE89370400440532013000",
        creditorName = "Creditor",
        creditorBic = "COBADEFFXXX",
        amount = Money.of(BigDecimal("10.00"), "EUR"),
        remittanceInfo = "invoice 1",
        endToEndId = "E2E1",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        completedAt = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        revision = 3,
    )
}
