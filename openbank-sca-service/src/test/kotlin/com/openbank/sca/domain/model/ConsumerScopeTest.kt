// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/** ADR-0335 D1: a scoped consumer spends only its reserved namespace, and nobody else spends it. */
class ConsumerScopeTest {
    private val pension = ConsumerScope.Reserved(ReservedNamespace.PENSION)
    private val general = ConsumerScope.General

    @Test
    fun `pension may spend an APPROVAL challenge signed into its namespace`() {
        listOf("pension-exit:${"a".repeat(64)}", "pension-onboarding:app-1:doc-2", "pension-transfer-out:c:p:n")
            .forEach { assertThat(pension.permits(approval(it))).`as`(it).isTrue() }
    }

    @Test
    fun `pension may not spend an ordinary business approval`() {
        assertThat(pension.permits(approval(UUID.randomUUID().toString()))).isFalse()
    }

    @Test
    fun `pension may not spend a payment, card or document challenge`() {
        ScaPurpose.entries.filter { it != ScaPurpose.APPROVAL }.forEach { purpose ->
            val ch = approval("pension-exit:abc").copy(purpose = purpose)
            assertThat(pension.permits(ch)).`as`(purpose.name).isFalse()
        }
        assertThat(pension.permits(approval(null))).isFalse()
    }

    @Test
    fun `pension may not spend a malformed pension id`() {
        listOf("pension-", "pension-:x", "pension-exit:", "pension-Exit:x", "pension-exit: x")
            .forEach { assertThat(pension.permits(approval(it))).`as`(it).isFalse() }
    }

    @Test
    fun `a general consumer may not spend a pension-namespaced challenge, malformed or not`() {
        listOf("pension-exit:abc", "pension-anything", "pension-")
            .forEach { assertThat(general.permits(approval(it))).`as`(it).isFalse() }
    }

    @Test
    fun `a general consumer keeps every unreserved challenge`() {
        assertThat(general.permits(approval(UUID.randomUUID().toString()))).isTrue()
        assertThat(general.permits(approval(null).copy(purpose = ScaPurpose.PAYMENT_INITIATION))).isTrue()
        assertThat(general.permits(approval("xpension-exit:abc"))).isTrue()
    }

    private fun approval(approvalRequestId: String?) = ScaChallenge(
        partyId = UUID.randomUUID(),
        purpose = ScaPurpose.APPROVAL,
        method = ScaMethod.PUSH_NOTIFICATION,
        status = ScaStatus.COMPLETED,
        expiresAt = OffsetDateTime.now().plusMinutes(5),
        dynamicLinkingData = DynamicLinkingData(
            null,
            null,
            null,
            null,
            null,
            approvalRequestId = approvalRequestId,
            payloadSha256 = "a".repeat(64),
        ),
        createdAt = OffsetDateTime.now(),
    )
}
