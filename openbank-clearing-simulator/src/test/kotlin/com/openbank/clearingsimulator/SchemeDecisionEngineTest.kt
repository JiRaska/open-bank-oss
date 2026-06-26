// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.clearingsimulator

import com.openbank.clearingsimulator.domain.RejectReason
import com.openbank.clearingsimulator.domain.SchemeDecisionEngine
import com.openbank.libs.iso20022.PaymentStatus
import com.openbank.libs.iso20022.ReceivedCreditTransfer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SchemeDecisionEngineTest {
    private val engine = SchemeDecisionEngine()

    private fun transfer(amount: String) = ReceivedCreditTransfer(
        messageId = "M1",
        endToEndId = "E2E-0001",
        transactionId = "TX-0001",
        amount = BigDecimal(amount),
        currency = "EUR",
        creditorName = "Bob Creditor",
        creditorIban = "FR1420041010050500013M02606",
        creditorAgentBic = "BNPAFRPPXXX",
        debtorIban = "DE89370400440532013000",
    )

    @Test
    fun `settles a normal transfer`() {
        val d = engine.decide(transfer("12.34"))
        assertThat(d.status).isEqualTo(PaymentStatus.ACSC)
        assertThat(d.settled).isTrue()
        assertThat(d.reason).isNull()
    }

    @Test
    fun `minor-unit remainder 01 rejects with AC04`() {
        val d = engine.decide(transfer("10.01"))
        assertThat(d.status).isEqualTo(PaymentStatus.RJCT)
        assertThat(d.reason).isEqualTo(RejectReason.AC04)
    }

    @Test
    fun `minor-unit remainder 02 rejects with AM05`() {
        assertThat(engine.decide(transfer("10.02")).reason).isEqualTo(RejectReason.AM05)
    }

    @Test
    fun `minor-unit remainder 04 rejects with RR04`() {
        assertThat(engine.decide(transfer("10.04")).reason).isEqualTo(RejectReason.RR04)
    }

    @Test
    fun `a non-trigger remainder still settles`() {
        assertThat(engine.decide(transfer("10.03")).settled).isTrue()
    }

    @Test
    fun `decision is deterministic for the same amount`() {
        assertThat(engine.decide(transfer("10.01"))).isEqualTo(engine.decide(transfer("10.01")))
    }
}
