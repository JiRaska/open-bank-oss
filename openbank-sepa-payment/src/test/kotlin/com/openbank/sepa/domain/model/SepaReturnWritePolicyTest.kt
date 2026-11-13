// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.domain.model

import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class SepaReturnWritePolicyTest {

    private val tx = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val reversal = UUID.fromString("00000000-0000-0000-0000-0000000000c3")
    private val resolvedAt = Instant.parse("2026-01-04T00:00:00Z")

    // ---- no return on record

    @Test
    fun `a write with no return on either side is accepted`() {
        val p = payment(SepaPaymentStatus.PROCESSING)
        accepts(p, p.copy(status = SepaPaymentStatus.COMPLETED, transactionId = tx))
    }

    @Test
    fun `a return cannot appear through a general write`() {
        val p = payment(SepaPaymentStatus.PROCESSING)
        refuses(p, p.copy(returnIntent = pending(null)), "Return intent must be created through beginReturn")
    }

    @Test
    fun `RETURNED cannot be written without an existing return`() {
        val p = payment(SepaPaymentStatus.COMPLETED, tx)
        refuses(p, p.copy(status = SepaPaymentStatus.RETURNED), "RETURNED requires an existing proven return intent")
    }

    @Test
    fun `an existing return cannot be dropped`() {
        val p = payment(SepaPaymentStatus.PROCESSING, returnIntent = pending(null))
        refuses(p, p.copy(returnIntent = null), IMMUTABLE)
    }

    // ---- immutable request + identity

    @Test
    fun `the original return request is immutable`() {
        val intent = pending(tx)
        val p = payment(SepaPaymentStatus.PROCESSING, tx, intent)
        for (changed in listOf(
            intent.copy(reasonCode = "MS02"),
            intent.copy(actorId = "someone-else"),
            intent.copy(actorType = "ROLE_OPERATOR"),
            intent.copy(correlationId = "c-2"),
            intent.copy(firstReceivedAt = intent.firstReceivedAt.plusSeconds(1)),
            intent.copy(originalTransactionId = reversal),
        )) {
            refuses(p, p.copy(returnIntent = changed), IMMUTABLE)
        }
    }

    @Test
    fun `the payment's transaction identity cannot change while a return exists`() {
        val p = payment(SepaPaymentStatus.PROCESSING, tx, pending(tx))
        refuses(p, p.copy(transactionId = reversal), IMMUTABLE)
    }

    // ---- attaching the first settlement identity

    @Test
    fun `settlement may attach the first identity to a pending return`() {
        val before =
            payment(SepaPaymentStatus.PROCESSING, returnIntent = pending(null, "UNRESOLVED_ORIGINAL_TRANSACTION"))
        val next = before.copy(
            status = SepaPaymentStatus.COMPLETED,
            transactionId = tx,
            returnIntent = pending(tx, "REVERSAL_UNPROVEN"),
        )
        accepts(before, next, allowInitialSettlementIdentity = true)
        refuses(before, next, IMMUTABLE)
    }

    @Test
    fun `identity attachment requires completion, a fresh identity and a matching original`() {
        val before = payment(SepaPaymentStatus.PROCESSING, returnIntent = pending(null))
        val next = before.copy(status = SepaPaymentStatus.COMPLETED, transactionId = tx, returnIntent = pending(tx))
        refuses(before, next.copy(status = SepaPaymentStatus.PROCESSING), IMMUTABLE, attach = true)
        refuses(before, next.copy(returnIntent = pending(reversal)), IMMUTABLE, attach = true)
        val alreadyIdentified = before.copy(transactionId = reversal)
        refuses(alreadyIdentified, next, IMMUTABLE, attach = true)
    }

    // ---- pending returns

    @Test
    fun `a pending return may stay in a recovery state`() {
        val p = payment(SepaPaymentStatus.PROCESSING, tx, pending(tx))
        accepts(p, p)
        accepts(p, p.copy(status = SepaPaymentStatus.COMPLETED))
    }

    @Test
    fun `a pending return cannot complete without a proven original identity`() {
        val p = payment(SepaPaymentStatus.PROCESSING, tx, pending(null))
        refuses(
            p,
            p.copy(status = SepaPaymentStatus.COMPLETED),
            "A pending return requires a proven original identity before settlement completion",
        )
    }

    @Test
    fun `a pending return cannot leave recovery states or rewrite its recovery metadata`() {
        val p = payment(SepaPaymentStatus.PROCESSING, tx, pending(tx))
        for (status in listOf(SepaPaymentStatus.CANCELLED, SepaPaymentStatus.REJECTED, SepaPaymentStatus.VALIDATED)) {
            refuses(p, p.copy(status = status), RECOVERY)
        }
        refuses(p, p.copy(returnIntent = pending(tx, "SCHEME_TIMEOUT")), RECOVERY)
    }

    // ---- resolving a return

    @Test
    fun `a proven resolution is accepted only when explicitly allowed`() {
        val p = payment(SepaPaymentStatus.COMPLETED, tx, pending(tx))
        val next = p.copy(status = SepaPaymentStatus.RETURNED, returnIntent = resolved(tx))
        accepts(p, next, allowResolution = true)
        refuses(p, next, RESOLUTION)
    }

    @Test
    fun `resolution needs RETURNED, both identities, distinct identities and the preserved cause`() {
        val p = payment(SepaPaymentStatus.COMPLETED, tx, pending(tx))
        val ok = p.copy(status = SepaPaymentStatus.RETURNED, returnIntent = resolved(tx))
        refuses(p, ok.copy(status = SepaPaymentStatus.COMPLETED), RESOLUTION, allowResolution = true)
        refuses(
            p,
            ok.copy(returnIntent = resolved(tx).copy(reversalTransactionId = null)),
            RESOLUTION,
            allowResolution = true,
        )
        refuses(
            p,
            ok.copy(returnIntent = resolved(tx).copy(reversalTransactionId = tx)),
            RESOLUTION,
            allowResolution = true,
        )
        refuses(
            p,
            ok.copy(returnIntent = resolved(tx).copy(unresolvedCause = "OTHER")),
            RESOLUTION,
            allowResolution = true,
        )
        val unknown = payment(SepaPaymentStatus.COMPLETED, tx, pending(null))
        refuses(
            unknown,
            unknown.copy(status = SepaPaymentStatus.RETURNED, returnIntent = resolved(null)),
            RESOLUTION,
            allowResolution = true,
        )
    }

    // ---- resolved returns

    @Test
    fun `a resolved return is final`() {
        val p = payment(SepaPaymentStatus.RETURNED, tx, resolved(tx))
        accepts(p, p, allowResolution = true)
        refuses(p, p.copy(returnIntent = resolved(tx).copy(resolvedAt = resolvedAt.plusSeconds(1))), FINAL)
        refuses(p, p.copy(returnIntent = resolved(tx).copy(reversalTransactionId = UUID.randomUUID())), FINAL)
        refuses(p, p.copy(status = SepaPaymentStatus.COMPLETED), FINAL)
    }

    private fun accepts(
        before: SepaPayment,
        next: SepaPayment,
        allowResolution: Boolean = false,
        allowInitialSettlementIdentity: Boolean = false,
    ) {
        assertThatCode {
            SepaReturnWritePolicy.validate(before, next, allowResolution, allowInitialSettlementIdentity)
        }.doesNotThrowAnyException()
    }

    private fun refuses(
        before: SepaPayment,
        next: SepaPayment,
        message: String,
        allowResolution: Boolean = false,
        attach: Boolean = false,
    ) {
        assertThatThrownBy { SepaReturnWritePolicy.validate(before, next, allowResolution, attach) }
            .isInstanceOf(ResourceConflictException::class.java)
            .hasMessage(message)
    }

    private fun pending(original: UUID?, cause: String = "REVERSAL_UNPROVEN") = SepaReturnIntent(
        originalTransactionId = original,
        firstReceivedAt = Instant.parse("2026-01-02T00:00:00Z"),
        reasonCode = "AM09",
        actorId = "actor",
        actorType = "ROLE_API",
        correlationId = "c-1",
        unresolvedCause = cause,
    )

    private fun resolved(original: UUID?) =
        pending(original).copy(resolvedAt = resolvedAt, reversalTransactionId = reversal)

    private fun payment(
        status: SepaPaymentStatus,
        transactionId: UUID? = null,
        returnIntent: SepaReturnIntent? = null,
    ) = SepaPayment(
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
        remittanceInfo = null,
        endToEndId = "E2E1",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        completedAt = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        transactionId = transactionId,
        returnIntent = returnIntent,
    )

    private companion object {
        const val IMMUTABLE = "A return's original request and transaction identity are immutable"
        const val RECOVERY = "An unresolved return must retain its recovery state and metadata"
        const val RESOLUTION = "Return resolution requires proven reversal and atomic evidence"
        const val FINAL = "A resolved return cannot be rewritten"
    }
}
