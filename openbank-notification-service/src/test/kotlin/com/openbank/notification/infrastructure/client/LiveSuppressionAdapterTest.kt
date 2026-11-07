// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.client

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
    private val client = mockk<SuppressionServiceClient>()
    private val adapter = LiveSuppressionAdapter(client)
    private val partyId = UUID.randomUUID()

    @Test
    fun `maps the live list without dropping its scope or reason`(): Unit = runBlocking {
        every { client.listActive(partyId) } returns Uni.createFrom().item(
            listOf(
                SuppressionResponse(
                    SuppressionScope.SCOPE,
                    "MARKETING_COMMS_EMAIL",
                    SuppressionReason.COMPLAINT,
                    "support",
                ),
            ),
        )

        val entries = adapter.activeSuppressions(partyId)

        assertThat(entries).hasSize(1)
        assertThat(entries.single().scope).isEqualTo(SuppressionScope.SCOPE)
        assertThat(entries.single().value).isEqualTo("MARKETING_COMMS_EMAIL")
        assertThat(entries.single().reason).isEqualTo(SuppressionReason.COMPLAINT)
    }

    @Test
    fun `propagates an unavailable suppression store for fail-closed handling`(): Unit = runBlocking {
        every { client.listActive(partyId) } returns Uni.createFrom().failure(IllegalStateException("unavailable"))

        assertThatThrownBy { runBlocking { adapter.activeSuppressions(partyId) } }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
