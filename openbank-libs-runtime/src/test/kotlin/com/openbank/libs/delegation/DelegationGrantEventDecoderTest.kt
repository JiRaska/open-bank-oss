// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.delegation

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

class DelegationGrantEventDecoderTest {

    private val mapper = ObjectMapper()
    private val grant = UUID.randomUUID()
    private val grantor = UUID.randomUUID()
    private val grantee = UUID.randomUUID()
    private val account = UUID.randomUUID()

    private fun payload(
        type: String = DelegationGrantEvents.ACTIVATED,
        aggregateId: String = "\"$grant\"",
        grantorId: String = "\"$grantor\"",
        granteeId: String = "\"$grantee\"",
        extra: String = "",
    ) = """
        {"eventType":"$type","aggregateId":$aggregateId,
         "grantorPartyId":$grantorId,"granteePartyId":$granteeId,
         "resourceType":"ACCOUNT","resourceId":"$account",
         "capabilities":["ACCOUNT_READ_BALANCES","ACCOUNT_INITIATE_PAYMENT"]$extra}
    """.trimIndent()

    @Test
    fun `decodes the fields every projection keys a row on`() {
        val event = DelegationGrantEventDecoder.decode(mapper, payload())!!

        assertThat(event.type).isEqualTo(DelegationGrantEvents.ACTIVATED)
        assertThat(event.grantId).isEqualTo(grant)
        assertThat(event.grantorPartyId).isEqualTo(grantor)
        assertThat(event.granteePartyId).isEqualTo(grantee)
        assertThat(event.resourceType).isEqualTo("ACCOUNT")
        assertThat(event.resourceId).isEqualTo(account)
        assertThat(event.capabilities).containsExactlyInAnyOrder("ACCOUNT_READ_BALANCES", "ACCOUNT_INITIATE_PAYMENT")
    }

    @Test
    fun `decodes the validity window, which decides whether a row is enforceable`() {
        val event = DelegationGrantEventDecoder.decode(
            mapper,
            payload(
                extra = ""","validFrom":"2026-11-01T00:00:00Z",""" +
                    """"validTo":"2026-12-01T00:00:00Z","lifecycleRevision":7""",
            ),
        )!!

        assertThat(event.validFrom).isEqualTo(OffsetDateTime.parse("2026-11-01T00:00:00Z"))
        assertThat(event.validTo).isEqualTo(OffsetDateTime.parse("2026-12-01T00:00:00Z"))
        assertThat(event.lifecycleRevision).isEqualTo(7L)
    }

    @Test
    fun `decodes the approval and limit fields the account projection reads`() {
        val event = DelegationGrantEventDecoder.decode(
            mapper,
            payload(
                extra = ""","approvalPolicy":"N_OF_M","requiredApprovals":2,""" +
                    """"approvalGroupRevision":3,"perTransactionLimit":{"amount":"1500.50","currency":"CZK"}""",
            ),
        )!!

        assertThat(event.approvalPolicy).isEqualTo("N_OF_M")
        assertThat(event.requiredApprovals).isEqualTo(2)
        assertThat(event.approvalGroupRevision).isEqualTo(3L)
        assertThat(event.perTxLimitAmount).isEqualByComparingTo(BigDecimal("1500.50"))
        assertThat(event.perTxLimitCurrency).isEqualTo("CZK")
    }

    @Test
    fun `an open-ended grant decodes with a null validTo rather than failing`() {
        val event = DelegationGrantEventDecoder.decode(mapper, payload())!!

        // Optional fields degrade to null; only the three ids are hard requirements, because a
        // null return means POISON PILL and is acked, not retried.
        assertThat(event.validTo).isNull()
        assertThat(event.approvalPolicy).isNull()
        assertThat(event.perTxLimitAmount).isNull()
    }

    @Test
    fun `a record missing any of the three keying ids is a poison pill`() {
        assertThat(DelegationGrantEventDecoder.decode(mapper, payload(aggregateId = "null"))).isNull()
        assertThat(DelegationGrantEventDecoder.decode(mapper, payload(grantorId = "null"))).isNull()
        assertThat(DelegationGrantEventDecoder.decode(mapper, payload(granteeId = "null"))).isNull()
        assertThat(DelegationGrantEventDecoder.decode(mapper, payload(aggregateId = "\"not-a-uuid\""))).isNull()
    }

    @Test
    fun `malformed JSON is a poison pill, not an exception`() {
        assertThat(DelegationGrantEventDecoder.decode(mapper, "{not json")).isNull()
        assertThat(DelegationGrantEventDecoder.decode(mapper, "")).isNull()
    }

    @Test
    fun `OFFERED and DECLINED are not projected, and that is a decision rather than an omission`() {
        // An offer nobody has accepted must never create an enforceable row: acceptance is a
        // second SCA ceremony (ADR-0232 D4). The decoder still decodes them — the record is
        // well-formed — and the projection is what declines to act.
        DelegationGrantEvents.IGNORED.forEach { assertThat(DelegationGrantEvents.isProjected(it)).isFalse() }
        assertThat(DelegationGrantEventDecoder.decode(mapper, payload(type = "DelegationOffered"))).isNotNull
    }

    @Test
    fun `every enabling and disabling type is projected, and the two sets do not overlap`() {
        (DelegationGrantEvents.ENABLING + DelegationGrantEvents.DISABLING).forEach {
            assertThat(DelegationGrantEvents.isProjected(it)).isTrue()
        }
        assertThat(DelegationGrantEvents.ENABLING).doesNotContainAnyElementsOf(DelegationGrantEvents.DISABLING)
    }
}
