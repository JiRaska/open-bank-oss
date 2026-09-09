// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.document.application.usecase

import com.openbank.document.application.port.`in`.DisclosureSnapshotUseCase
import com.openbank.document.application.port.`in`.IssueDisclosureSnapshotCommand
import com.openbank.document.application.port.out.DisclosureSnapshotRepository
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.domain.model.DisclosureSnapshot
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.libs.storage.ObjectStorePort
import jakarta.enterprise.context.ApplicationScoped
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class DisclosureSnapshotService(
    private val documents: DocumentRepositoryPort,
    private val snapshots: DisclosureSnapshotRepository,
    private val objectStore: ObjectStorePort,
    private val clock: Clock,
) : DisclosureSnapshotUseCase {

    override suspend fun issue(cmd: IssueDisclosureSnapshotCommand): DisclosureSnapshot? {
        require(cmd.expectedPartyRef.isNotBlank()) { "expectedPartyRef is required" }
        snapshots.findByRequestId(cmd.requestId)?.let { existing ->
            require(existing.sourceDocumentId == cmd.sourceDocumentId && existing.partyRef == cmd.expectedPartyRef) {
                "requestId is already bound to another disclosure source"
            }
            readObject(existing.storageKey)?.let { bytes ->
                check(Document.sha256(bytes) == existing.sha256) { "disclosure snapshot digest mismatch" }
                return existing
            }
        }

        val source = documents.findById(cmd.sourceDocumentId) ?: return null
        require(source.partyRef == cmd.expectedPartyRef) { "source document does not belong to the expected party" }
        require(source.status == DocumentStatus.SIGNED) { "only SIGNED documents can be disclosed externally" }
        require(source.contentType == PDF_CONTENT_TYPE) { "only PDF documents can be disclosed externally" }
        val bytes = readSource(source) ?: return null
        require(Document.sha256(bytes) == source.sha256) { "source document digest does not match its stored bytes" }

        // Deterministic identity makes the object-store write idempotent across a crash between the
        // blob write and metadata insert. A retry writes the same verified bytes to the same key.
        val id = UUID.nameUUIDFromBytes("disclosure:${cmd.requestId}".toByteArray(StandardCharsets.UTF_8))
        val digest = Document.sha256(bytes)
        val storageKey = "documents/disclosures/$id/$digest"
        val candidate = DisclosureSnapshot(
            id = id,
            requestId = cmd.requestId,
            sourceDocumentId = source.id,
            partyRef = cmd.expectedPartyRef,
            sourceSha256 = source.sha256,
            sha256 = digest,
            storageKey = storageKey,
            contentType = PDF_CONTENT_TYPE,
            sizeBytes = bytes.size.toLong(),
            createdAt = Instant.now(clock),
        )
        objectStore.put(
            storageKey,
            bytes,
            PDF_CONTENT_TYPE,
            mapOf("sha256" to candidate.sha256, "sourceDocumentId" to source.id.toString()),
        )
        val persisted = snapshots.createOrFind(candidate)
        check(
            persisted.sourceDocumentId == candidate.sourceDocumentId &&
                persisted.partyRef == candidate.partyRef &&
                persisted.sha256 == candidate.sha256,
        ) { "requestId was concurrently bound to another disclosure source" }
        return persisted
    }

    override suspend fun getMetadata(id: UUID): DisclosureSnapshot? = snapshots.findById(id)

    override suspend fun getContent(id: UUID): ByteArray? {
        val snapshot = snapshots.findById(id) ?: return null
        val bytes = readObject(snapshot.storageKey) ?: return null
        check(Document.sha256(bytes) == snapshot.sha256) { "disclosure snapshot digest mismatch" }
        return bytes
    }

    private suspend fun readSource(source: Document): ByteArray? = readObject(source.storageKey)

    @Suppress("SwallowedException")
    private suspend fun readObject(storageKey: String): ByteArray? = try {
        objectStore.get(storageKey)
    } catch (e: NoSuchElementException) {
        null
    } catch (e: NoSuchKeyException) {
        null
    }

    private companion object {
        const val PDF_CONTENT_TYPE = "application/pdf"
    }
}
