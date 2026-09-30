// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.document.application.usecase

import com.openbank.document.application.port.`in`.ExportExternalDisclosureCommand
import com.openbank.document.application.port.`in`.ExternalDisclosureExportUseCase
import com.openbank.document.application.port.`in`.SealedExternalDisclosure
import com.openbank.document.application.port.out.DisclosureWatermarkPort
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.application.port.out.ExternalDisclosureSealPort
import com.openbank.libs.storage.ObjectStorePort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotFoundException

/** Produces a derived, visibly marked and institutionally sealed PDF; it never exposes source bytes. */
@ApplicationScoped
class ExternalDisclosureExportService(
    private val documentRepository: DocumentRepositoryPort,
    private val objectStore: ObjectStorePort,
    private val watermarkPort: DisclosureWatermarkPort,
    private val sealPort: ExternalDisclosureSealPort,
) : ExternalDisclosureExportUseCase {
    override suspend fun export(command: ExportExternalDisclosureCommand): SealedExternalDisclosure {
        val document = documentRepository.findById(command.documentId) ?: throw NotFoundException("document not found")
        require(document.contentType.equals("application/pdf", ignoreCase = true)) {
            "external disclosure supports sealed PDF documents only"
        }
        val marked = watermarkPort.watermark(
            objectStore.get(document.storageKey),
            command.recipientLabel,
            command.disclosureId,
        )
        val sealed = sealPort.sealExternalDisclosure(marked, command.disclosureId)
        return SealedExternalDisclosure(document.id, command.disclosureId, document.contentType, sealed)
    }
}
