// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProposalDetectorTest {

    @Test
    fun `factual read reply is not a proposal`() {
        val reply = "Account CZ6508000000192000145399 is ACTIVE with a CZK balance of 10500."
        assertThat(ProposalDetector.isProposal(reply)).isFalse()
    }

    @Test
    fun `recommendation phrase triggers proposal flag`() {
        val reply = "I recommend you investigate this account — the transaction volume is unusually high."
        assertThat(ProposalDetector.isProposal(reply)).isTrue()
    }

    @Test
    fun `suggest phrase triggers proposal flag`() {
        val reply = "I suggest reviewing the balance holds before approving the payment."
        assertThat(ProposalDetector.isProposal(reply)).isTrue()
    }

    @Test
    fun `action required triggers proposal flag`() {
        val reply = "Action required: the KYC status has expired and must be renewed."
        assertThat(ProposalDetector.isProposal(reply)).isTrue()
    }

    @Test
    fun `should be investigated triggers proposal flag`() {
        val reply = "The account should be investigated for potential AML risk."
        assertThat(ProposalDetector.isProposal(reply)).isTrue()
    }

    @Test
    fun `plain list of transactions is not a proposal`() {
        val reply = """Here are the last 3 transactions:
            |1. 2026-06-01 -500 CZK SEPA transfer
            |2. 2026-05-31 +1000 CZK salary
            |3. 2026-05-30 -200 CZK card payment
        """.trimMargin()
        assertThat(ProposalDetector.isProposal(reply)).isFalse()
    }

    // Regression cases for previously over-broad patterns (removed 'consider' and 'you could')
    @Test
    fun `consider that — factual informational phrase is NOT a proposal`() {
        val reply = "Consider that the balance includes a hold of 500 CZK that will clear tomorrow."
        assertThat(ProposalDetector.isProposal(reply)).isFalse()
    }

    @Test
    fun `you could see — factual reference is NOT a proposal`() {
        val reply = "You could see from the account history that three transfers occurred in January."
        assertThat(ProposalDetector.isProposal(reply)).isFalse()
    }

    @Test
    fun `account should be investigated triggers proposal flag`() {
        val reply = "This account should be investigated for AML risk based on the transaction volume."
        assertThat(ProposalDetector.isProposal(reply)).isTrue()
    }
}
