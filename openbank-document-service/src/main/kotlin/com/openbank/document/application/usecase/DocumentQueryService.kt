// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.openbank.document.application.port.`in`.DocumentQueryUseCase
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.domain.model.Document
import com.openbank.libs.storage.ObjectStorePort
import jakarta.enterprise.context.ApplicationScoped
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.util.UUID

@ApplicationScoped
class DocumentQueryService(
    private val documentRepo: DocumentRepositoryPort,
    private val objectStore: ObjectStorePort,
) : DocumentQueryUseCase {

    override suspend fun getMetadata(id: UUID): Document? = documentRepo.findById(id)

    /**
     * The shared [ObjectStorePort.get] (ADR-0161) throws rather than returning null on a missing
     * key, and — because the concrete exception type is backend-specific ([NoSuchElementException]
     * on the Postgres adapter, [NoSuchKeyException] on the S3 adapter) — this maps *either* of
     * those two "not found" signals to this use case's own nullable return contract. Any other
     * exception (a real I/O/auth/config failure) is intentionally left to propagate, not swallowed.
     */
    @Suppress("SwallowedException") // the exception type itself IS the "not found" signal — see KDoc above
    override suspend fun getContent(id: UUID): ByteArray? {
        val document = documentRepo.findById(id) ?: return null
        return try {
            objectStore.get(document.storageKey)
        } catch (e: NoSuchElementException) {
            null
        } catch (e: NoSuchKeyException) {
            null
        }
    }

    override suspend fun listByParty(partyRef: String): List<Document> = documentRepo.findByParty(partyRef)

    override suspend fun listByPartyPaged(partyRef: String, page: Int, size: Int): List<Document> =
        documentRepo.findByPartyPaged(partyRef, page, size)

    override suspend fun countByParty(partyRef: String): Long = documentRepo.countByParty(partyRef)

    override suspend fun findByIdempotencyKey(key: String): Document? = documentRepo.findByIdempotencyKey(key)
}
