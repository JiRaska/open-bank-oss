// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * The canonical inbox projection (ADR-0227 D1). Pins what the projection CAN source from a
 * persisted [PendingApproval] — and, just as much, what it cannot: amount/currency are not
 * persisted anywhere (a null must never be read as "zero amount" by the D3 amount-band filter),
 * and riskClass is UNKNOWN until a caller supplies a classifier, because the authoritative
 * classification lives in rules.yaml action-class metadata the store cannot see.
 */
class ApprovalItemTest {

    private fun pending() = PendingApproval(
        id = "appr-7",
        action = "ledger.reverse",
        resourceId = "je-1",
        makerId = "maker-1",
        status = ApprovalStatus.PENDING,
        createdAt = OffsetDateTime.parse("2026-06-22T10:20:00Z"),
        makerActorKind = MakerActorKind.HUMAN,
    )

    @Test
    fun `the projection carries the persisted fields and the caller-supplied domain`() {
        val item = pending().toApprovalItem(domain = "ledger")

        assertThat(item.id).isEqualTo("appr-7")
        assertThat(item.domain).isEqualTo("ledger")
        assertThat(item.action).isEqualTo("ledger.reverse")
        assertThat(item.state).isEqualTo(ApprovalStatus.PENDING)
        assertThat(item.maker).isEqualTo(
            ApprovalMaker(
                id = "maker-1",
                channel = MakerActorKind.HUMAN,
                at = OffsetDateTime.parse("2026-06-22T10:20:00Z"),
            ),
        )
    }

    @Test
    fun `summary comes from the persisted request binding and is null when unbound`() {
        val bound = pending().copy(summary = "POST /api/v1/journals je-1/reverse")
        assertThat(bound.toApprovalItem("ledger").summary).isEqualTo("POST /api/v1/journals je-1/reverse")
        assertThat(pending().toApprovalItem("ledger").summary).isNull()
    }

    @Test
    fun `amount and currency are absent for every record today - the request body is not persisted`() {
        val item = pending().toApprovalItem("ledger")
        assertThat(item.amount).isNull()
        assertThat(item.currency).isNull()
    }

    @Test
    fun `payloadRef is absent - the fingerprint is an integrity digest, not a payload pointer`() {
        val fingerprinted = pending().copy(requestFingerprint = "a".repeat(64))
        assertThat(fingerprinted.toApprovalItem("ledger").payloadRef).isNull()
    }

    @Test
    fun `riskClass defaults to UNKNOWN and accepts a caller-supplied classifier result`() {
        assertThat(pending().toApprovalItem("ledger").riskClass).isEqualTo(ApprovalRiskClass.UNKNOWN)
        assertThat(pending().toApprovalItem("ledger", ApprovalRiskClass.MONEY_PATH).riskClass)
            .isEqualTo(ApprovalRiskClass.MONEY_PATH)
    }
}
