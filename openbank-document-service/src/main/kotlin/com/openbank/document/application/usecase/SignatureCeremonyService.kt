// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.document.application.port.`in`.OpenCeremonyCommand
import com.openbank.document.application.port.`in`.SignatureCeremonyUseCase
import com.openbank.document.application.port.out.CeremonyRepositoryPort
import com.openbank.document.application.port.out.ClientSignatureIssuerPort
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.application.port.out.SignatureSealPort
import com.openbank.document.application.port.out.SignerVerificationPort
import com.openbank.document.domain.event.SignatureCeremonyCompleted
import com.openbank.document.domain.model.CeremonyStatus
import com.openbank.document.domain.model.Document
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
 * Orchestrates e-signature ceremonies (ADR-0162 D4 continued — the two-tier signature model). A
 * SIGNED decision must first pass the SCA-bound [SignerVerificationPort] check (ADR-0021) — a
 * DECLINED decision does not. Once verified, the signer's own **electronic signature** is applied
 * immediately via [ClientSignatureIssuerPort] (a fresh one-time certificate per signing act).
 * Once every signer reaches a terminal decision, the bank's institutional **electronic seal** is
 * applied last via [SignatureSealPort] (a stable organizational identity), and a
 * [SignatureCeremonyCompleted] outbox event is emitted.
 */
@ApplicationScoped
class SignatureCeremonyService(
    private val ceremonyRepo: CeremonyRepositoryPort,
    private val documentRepo: DocumentRepositoryPort,
    private val objectStore: ObjectStorePort,
    private val sealPort: SignatureSealPort,
    private val clientSignaturePort: ClientSignatureIssuerPort,
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
        val ceremony = ceremonyRepo.findById(ceremonyId) ?: error("Ceremony not found: $ceremonyId")
        val now = Instant.now(clock)
        // Validate the decision in the domain FIRST — signer order, not-already-decided, ceremony
        // status — before any object-store mutation. A rejected decision (wrong signer order, a
        // replayed duplicate, an already-terminal ceremony) must never leave a phantom client
        // signature on the stored document, which it would if signing ran before this check.
        val updated = ceremony.recordDecision(partyRef, decision, now)
        if (decision == SignerStatus.SIGNED) {
            // Fetched here (not just inside signAsClient) so its sha256 can scope the SCA check
            // to THIS exact document + ceremony (RTS Art. 5 dynamic linking, ADR-0169 D2) — an
            // evidenceRef approved for a different document must not verify here.
            val document = documentRepo.findById(ceremony.documentId)
                ?: error("Cannot verify SCA for ceremony $ceremonyId: document ${ceremony.documentId} not found")
            val verified = evidenceRef != null &&
                signerVerificationPort.verify(partyRef, evidenceRef, document.sha256, ceremonyId.toString())
            if (!verified) {
                error("SCA verification failed for signer $partyRef on ceremony $ceremonyId")
            }
            // Apply this signer's own one-time electronic signature only after the decision is
            // validated and SCA-verified, and before persisting: a decision is never recorded as
            // SIGNED without a corresponding signature actually landing on the document.
            signAsClient(document, partyRef)
        }
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

    override suspend fun findByDocumentId(documentId: UUID): SignatureCeremony? =
        ceremonyRepo.findByDocumentId(documentId)

    private suspend fun signAsClient(document: Document, partyRef: String) {
        val pdf = objectStore.get(document.storageKey)
        val signed = clientSignaturePort.signAsClient(pdf, partyRef)
        objectStore.put(document.storageKey, signed, document.contentType)
    }

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
