// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.document.application.port.`in`.OpenCeremonyCommand
import com.openbank.document.application.port.`in`.SignatureCeremonyUseCase
import com.openbank.document.application.port.out.CeremonyRepositoryPort
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.application.port.out.SignatureSealPort
import com.openbank.document.application.port.out.SignerVerificationPort
import com.openbank.document.domain.event.SignatureCeremonyCompleted
import com.openbank.document.domain.model.CeremonyStatus
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.document.domain.model.Signer
import com.openbank.document.domain.model.SignerStatus
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.storage.ObjectStorePort
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Orchestrates e-signature ceremonies. A SIGNED decision must first pass the SCA-bound
 * [SignerVerificationPort] check (ADR-0162 D4, ADR-0021) — a DECLINED decision does not. On
 * completion it PAdES-seals the stored document bytes via [SignatureSealPort] and emits a
 * [SignatureCeremonyCompleted] outbox event.
 */
@ApplicationScoped
class SignatureCeremonyService(
    private val ceremonyRepo: CeremonyRepositoryPort,
    private val documentRepo: DocumentRepositoryPort,
    private val objectStore: ObjectStorePort,
    private val sealPort: SignatureSealPort,
    private val signerVerificationPort: SignerVerificationPort,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) : SignatureCeremonyUseCase {

    override suspend fun openCeremony(cmd: OpenCeremonyCommand): SignatureCeremony {
        documentRepo.findById(cmd.documentId) ?: error("Document not found: ${cmd.documentId}")
        val signers = cmd.signerPartyRefs.mapIndexed { index, ref ->
            Signer(partyRef = ref, order = index + 1, status = SignerStatus.PENDING, signedAt = null)
        }
        val ceremony = SignatureCeremony(
            id = Ids.newId(),
            documentId = cmd.documentId,
            signers = signers,
            status = CeremonyStatus.DRAFT,
            signatureLevel = cmd.signatureLevel,
            createdAt = Instant.now(clock),
        ).open()
        return ceremonyRepo.save(ceremony)
    }

    override suspend fun recordDecision(
        ceremonyId: UUID,
        partyRef: String,
        decision: SignerStatus,
        evidenceRef: String?,
    ): SignatureCeremony {
        if (decision == SignerStatus.SIGNED) {
            val verified = evidenceRef != null && signerVerificationPort.verify(partyRef, evidenceRef)
            if (!verified) {
                error("SCA verification failed for signer $partyRef on ceremony $ceremonyId")
            }
        }
        val ceremony = ceremonyRepo.findById(ceremonyId) ?: error("Ceremony not found: $ceremonyId")
        val now = Instant.now(clock)
        val updated = ceremony.recordDecision(partyRef, decision, now)
        if (updated.status != CeremonyStatus.COMPLETED) {
            return ceremonyRepo.save(updated)
        }

        // Seal BEFORE persisting COMPLETED + emitting the event: if sealing throws (document
        // missing, render/store failure), this whole call fails and nothing is persisted — an
        // unsealed document must never be representable as a completed, event-emitted ceremony.
        sealDocument(updated)
        val outboxMessage = OutboxMessage(
            eventId = Ids.newId(),
            aggregateId = updated.id,
            eventType = EVENT_CEREMONY_COMPLETED,
            payload = objectMapper.writeValueAsString(
                SignatureCeremonyCompleted(
                    ceremonyId = updated.id,
                    documentId = updated.documentId,
                    at = now,
                ),
            ),
        )
        return ceremonyRepo.saveWithOutbox(updated, outboxMessage)
    }

    override suspend fun getCeremony(id: UUID): SignatureCeremony? = ceremonyRepo.findById(id)

    private suspend fun sealDocument(ceremony: SignatureCeremony) {
        val document = documentRepo.findById(ceremony.documentId)
            ?: error("Cannot seal ceremony ${ceremony.id}: document ${ceremony.documentId} not found")
        val pdf = objectStore.get(document.storageKey)
        val sealed = sealPort.sealPades(pdf, ceremony)
        objectStore.put(document.storageKey, sealed, document.contentType)
    }

    companion object {
        const val EVENT_CEREMONY_COMPLETED = "signature-ceremony.completed.v1"
    }
}
