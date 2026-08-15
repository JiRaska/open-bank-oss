// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.infrastructure.consent

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

class ConsentClientTest {

    private val partyId = UUID.randomUUID()

    @Test
    fun `consent outage propagates so the contact gate can retry instead of recording a denial`() {
        val client = mockk<ConsentServiceClient>()
        every { client.hasActiveConsent(partyId, "campaign", "MARKETING_COMMS_EMAIL") } returns
            Uni.createFrom().failure(IllegalStateException("consent unavailable"))
        val adapter = LiveConsentCheckAdapter(client, "campaign")

        assertThatThrownBy {
            runBlocking { adapter.hasActiveConsent(partyId, "MARKETING_COMMS_EMAIL") }
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `active suppressions are mapped from consent service without losing their scope`() {
        val client = mockk<SuppressionServiceClient>()
        every { client.listActive(partyId) } returns Uni.createFrom().item(
            listOf(
                SuppressionResponse(
                    scope = SuppressionScope.TOPIC,
                    value = "loans",
                    reason = SuppressionReason.RM_MANAGED,
                    source = "rm-workbench",
                ),
            ),
        )

        val entries = runBlocking { LiveSuppressionAdapter(client).activeSuppressions(partyId) }

        assertThat(entries).hasSize(1)
        val entry = entries.single()
        assertThat(entry.scope).isEqualTo(SuppressionScope.TOPIC)
        assertThat(entry.value).isEqualTo("loans")
        assertThat(entry.reason).isEqualTo(SuppressionReason.RM_MANAGED)
        assertThat(entry.source).isEqualTo("rm-workbench")
    }

    @Test
    fun `suppression outage is never converted to an empty do-not-contact list`() {
        val client = mockk<SuppressionServiceClient>()
        every { client.listActive(partyId) } returns
            Uni.createFrom().failure(IllegalStateException("suppression store unavailable"))

        assertThatThrownBy {
            runBlocking { LiveSuppressionAdapter(client).activeSuppressions(partyId) }
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
