// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.application.usecase

import com.openbank.libs.fincrime.ScreeningMatchStatus
import com.openbank.libs.fincrime.ScreeningResult
import com.openbank.libs.fincrime.ScreeningRole
import com.openbank.swift.application.port.out.AmlCasePort
import com.openbank.swift.application.port.out.SanctionsScreeningPort
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * sanctions-service short-circuits a replayed idempotency key with its STORED result. A key derived
 * only from the message id therefore makes a re-screen of a held MT103 (after a list update or a
 * name correction) return the first attempt's CLEAR instead of screening again. Each attempt must
 * carry its own key; both parties of one attempt share its nonce.
 */
class SwiftSanctionsScreeningKeyTest {

    private val keys = mutableListOf<Pair<ScreeningRole, String>>()

    /** Mimics sanctions-service: the first answer stored per key is replayed for that key. */
    private val stored = mutableMapOf<String, ScreeningMatchStatus>()
    private var listedNow = false

    private val port = mockk<SanctionsScreeningPort>().also {
        coEvery { it.screen(any(), any(), any()) } answers {
            val role = secondArg<ScreeningRole>()
            val key = thirdArg<String>()
            keys += role to key
            val listed = listedNow && role == ScreeningRole.CREDITOR
            val status = stored.getOrPut(key) { if (listed) ScreeningMatchStatus.HIT else ScreeningMatchStatus.CLEAR }
            ScreeningResult(firstArg(), role, status, if (status == ScreeningMatchStatus.HIT) 1.0 else 0.0, null)
        }
    }
    private val aml = mockk<AmlCasePort>().also { coEvery { it.openCase(any()) } returns Unit }
    private val screening = SwiftSanctionsScreening(port, aml)

    @Test
    fun `a re-screen after a list update screens afresh instead of replaying the stored CLEAR`(): Unit = runBlocking {
        val msg = message()
        assertThat(screening.screen(msg)).isEqualTo(SwiftScreeningVerdict.RELEASE)

        listedNow = true // the beneficiary is added to a sanctions list
        assertThat(screening.screen(msg)).isEqualTo(SwiftScreeningVerdict.REJECT)
    }

    @Test
    fun `each attempt gets its own key and both parties share it`(): Unit = runBlocking {
        val msg = message()
        screening.screen(msg)
        screening.screen(msg)

        val attempts = keys.map { (role, key) -> key.removeSuffix(":${role.name.lowercase()}") }
        assertThat(keys.map { it.first })
            .containsExactly(ScreeningRole.DEBTOR, ScreeningRole.CREDITOR, ScreeningRole.DEBTOR, ScreeningRole.CREDITOR)
        assertThat(attempts[0]).isEqualTo(attempts[1]).startsWith("swift-${msg.id}-")
        assertThat(attempts[2]).isEqualTo(attempts[3])
        assertThat(attempts[0]).isNotEqualTo(attempts[2])
    }

    private fun message() = SwiftMessage(
        id = UUID.fromString("5f5f5f5f-0000-4000-8000-000000000001"),
        idempotencyKey = "idem-key-test",
        messageType = SwiftMessageType.MT103,
        senderBic = "ABCDEFGH",
        receiverBic = "IJKLMNOP",
        transactionReference = "TRX-KEY",
        relatedReference = null,
        valueDate = "20260527",
        amount = SwiftMessage.moneyOfMinorUnits(1000, "EUR"),
        orderingCustomerAccount = "DE89370400440532013000",
        orderingCustomerAccountId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        orderingCustomerName = "Alice",
        beneficiaryAccount = "GB33BUKB20201555555555",
        beneficiaryName = "Bob",
        remittanceInfo = "Invoice 1",
        chargeCode = "SHA",
        priority = SwiftPriority.NORMAL,
        status = SwiftStatus.VALIDATED,
        rawMt = null,
        ackReceivedAt = null,
        rejectionReason = null,
        createdAt = Instant.parse("2026-05-27T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-27T00:00:00Z"),
    )
}
