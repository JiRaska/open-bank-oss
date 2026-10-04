// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.contract

import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.libs.storage.ObjectStorePort
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The state both document provider twins serve for delegation-service's external-disclosure
 * export (#8345). One implementation, so the folder twin (PR lane) and the broker twin (main push)
 * cannot seed different documents for the same interaction.
 */
object DocumentDisclosurePactSeed {
    const val STATE = "a sealed PDF document exists for the pact disclosure"

    /** Must match DocumentDisclosureExportPactConsumerTest (openbank-delegation-service). */
    private val DISCLOSURE_DOCUMENT_ID: UUID = UUID.fromString("d0c0d0c0-0000-4000-8000-000000000001")
    private const val DISCLOSURE_STORAGE_KEY = "pact/disclosure/d0c0d0c0-0000-4000-8000-000000000001.pdf"

    /**
     * Seeds a real, PDFBox-generated one-page PDF in the object store and its `Document` row,
     * `SIGNED` and `application/pdf`, which is the only content type the export accepts. The
     * watermark and seal adapters then run for real against those bytes; the pact pins status and
     * content type only, since a sealed PDF's bytes differ per run. Idempotent across replays.
     */
    fun seed(vertx: Vertx, documentRepository: DocumentRepositoryPort, objectStore: ObjectStorePort) =
        runOnVertxContext(vertx) {
            if (documentRepository.findById(DISCLOSURE_DOCUMENT_ID) != null) return@runOnVertxContext
            val pdf =
                ByteArrayOutputStream()
                    .also { out ->
                        PDDocument().use { doc ->
                            doc.addPage(PDPage())
                            doc.save(out)
                        }
                    }.toByteArray()
            val sha256 = MessageDigest.getInstance("SHA-256").digest(pdf).joinToString("") { "%02x".format(it) }
            objectStore.put(DISCLOSURE_STORAGE_KEY, pdf, "application/pdf")
            documentRepository.save(
                Document(
                    id = DISCLOSURE_DOCUMENT_ID,
                    templateCode = "PACT_DISCLOSURE",
                    templateVersion = "1",
                    sha256 = sha256,
                    storageKey = DISCLOSURE_STORAGE_KEY,
                    contentType = "application/pdf",
                    sizeBytes = pdf.size.toLong(),
                    status = DocumentStatus.SIGNED,
                    metadata = emptyMap(),
                    partyRef = null,
                    caseRef = null,
                    productRef = null,
                    retainUntil = null,
                    createdAt = Instant.parse("2026-01-15T10:00:00Z"),
                ),
            )
        }

    /**
     * Bridges reactive Panache into Pact-JVM's synchronous `@State` callback, which runs on the
     * JUnit thread with no Vert.x context. Same shape as account-service's provider test.
     */
    internal fun runOnVertxContext(vertx: Vertx, block: suspend () -> Unit) {
        val future = CompletableFuture<Unit>()
        val duplicated = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(duplicated, true)
        val dispatcher = Executor { command -> duplicated.runOnContext { command.run() } }.asCoroutineDispatcher()
        CoroutineScope(dispatcher).launch {
            try {
                block()
                future.complete(Unit)
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        future.get(30, TimeUnit.SECONDS)
    }
}
