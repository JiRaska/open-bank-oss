// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.messaging

import com.openbank.kyb.domain.model.BeneficialOwner
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.KybEvents
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.OwnershipBand
import com.openbank.kyb.domain.model.UboFinding
import com.openbank.kyb.domain.model.UboObservation
import com.openbank.kyb.domain.model.UboSource
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class KafkaKybOutboxEventPublisherTest {
    private val lifecycle = mockk<MutinyEmitter<String>>()
    private val ownership = mockk<MutinyEmitter<String>>()
    private val publisher = KafkaKybOutboxEventPublisher(lifecycle, ownership)

    @Test
    fun `reference goes only to isolated channel and unknown event never reaches shared channel`() {
        every { ownership.sendMessage(any()) } returns Uni.createFrom().voidItem()
        runBlocking { publisher.publish(entry(UboObservationReference.EVENT_TYPE)) }
        verify(exactly = 1) { ownership.sendMessage(any()) }
        verify(exactly = 0) { lifecycle.sendMessage(any()) }

        assertThatThrownBy { runBlocking { publisher.publish(entry("UnknownKybEvent")) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { lifecycle.sendMessage(any()) }
    }

    @Test
    fun `lifecycle event retains its original channel`() {
        every { lifecycle.sendMessage(any()) } returns Uni.createFrom().voidItem()
        runBlocking { publisher.publish(entry(KybEvents.STARTED)) }
        verify(exactly = 1) { lifecycle.sendMessage(any()) }
        verify(exactly = 0) { ownership.sendMessage(any()) }
    }

    @Test
    fun `reference payload contains only identifiers revision and hash`() {
        val now = Instant.parse("2026-09-17T00:00:00Z")
        val caseId = UUID.randomUUID()
        val observation = UboObservation(
            id = UUID.randomUUID(),
            caseId = caseId,
            revision = 1,
            finding = UboFinding(
                identifier = LegalEntityIdentifier.of(IdentifierScheme.GB_CRN, "01234567"),
                source = UboSource.REGISTER,
                owners = listOf(
                    BeneficialOwner(
                        fullName = "Synthetic Owner",
                        dateOfBirth = null,
                        nationality = null,
                        countryOfResidence = null,
                        band = OwnershipBand.PCT_25_TO_50,
                        natureOfControl = listOf("ownership-of-shares-25-to-50-percent"),
                        notifiedOn = null,
                        corporate = false,
                    ),
                ),
                registerStatements = emptyList(),
                threshold = 0.25,
                registerName = "Example register",
                sourceRef = "source-record",
                fetchedAt = now,
            ),
            sourceSha256 = "a".repeat(64),
            recordedAt = now,
        )
        val message = UboObservationReference.from(observation).toOutboxMessage(now)
        assertThat(message.aggregateId).isEqualTo(caseId)
        assertThat(message.payload).doesNotContain("Synthetic Owner", "Example register", "source-record")
        val fields = com.openbank.kyb.infrastructure.persistence.repository.KybJson.mapper
            .readTree(message.payload).fieldNames().asSequence().toSet()
        assertThat(fields).containsExactlyInAnyOrder(
            "schemaVersion",
            "eventType",
            "caseId",
            "observationId",
            "revision",
            "sourceSha256",
        )
    }

    private fun entry(eventType: String): OutboxEntry {
        val now = Instant.parse("2026-09-17T00:00:00Z")
        return OutboxEntry(
            eventId = UUID.randomUUID(),
            aggregateId = UUID.randomUUID(),
            eventType = eventType,
            payload = "{}",
            status = OutboxStatus.PENDING,
            attemptCount = 0,
            createdAt = now,
            updatedAt = now,
            sentAt = null,
            lastError = null,
        )
    }
}
