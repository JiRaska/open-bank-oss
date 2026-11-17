// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.consent

import com.openbank.libs.contact.SuppressionReason
import com.openbank.libs.contact.SuppressionScope
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class LiveSuppressionAdapterTest {

    private val client = mockk<SuppressionRestClient>()
    private val adapter = LiveSuppressionAdapter(client)
    private val partyId: UUID = UUID.randomUUID()
    private val entry = SuppressionResponse(
        scope = SuppressionScope.ALL,
        value = null,
        reason = SuppressionReason.CUSTOMER_OPTOUT,
        source = "preference-centre",
    )

    @Test
    fun `maps the consent-service list into gate entries`(): Unit = runBlocking {
        every { client.listActive(partyId) } returns Uni.createFrom().item(
            listOf(entry),
        )

        val entries = adapter.activeSuppressions(partyId)

        assertThat(entries).hasSize(1)
        assertThat(entries[0].scope).isEqualTo(SuppressionScope.ALL)
        assertThat(entries[0].reason).isEqualTo(SuppressionReason.CUSTOMER_OPTOUT)
        assertThat(entries[0].source).isEqualTo("preference-centre")
    }

    @Test
    fun `an unavailable consent-service propagates instead of reading as an empty list`(): Unit = runBlocking {
        every { client.listActive(partyId) } returns Uni.createFrom().failure(IllegalStateException("down"))

        assertThatThrownBy { runBlocking { adapter.activeSuppressions(partyId) } }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
