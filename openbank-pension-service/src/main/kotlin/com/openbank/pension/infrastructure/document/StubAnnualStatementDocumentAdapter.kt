// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.document

import com.openbank.pension.application.port.out.AnnualStatementContent
import com.openbank.pension.application.port.out.AnnualStatementDocumentPort
import com.openbank.pension.application.port.out.RenderedDocument
import io.quarkus.arc.profile.IfBuildProfile
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * `dev`/`test` stand-in for [DocumentServiceAnnualStatementAdapter]: deterministic id and hash over
 * the data map the real adapter would send, and a loud log line, so it never passes for a render.
 */
@IfBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class StubAnnualStatementDocumentAdapter : AnnualStatementDocumentPort {
    private val log = Logger.getLogger(StubAnnualStatementDocumentAdapter::class.java)

    override suspend fun generate(content: AnnualStatementContent): RenderedDocument {
        val key = PensionDocumentData.annualStatement(content).toSortedMap().toString()
        log.warnf(
            "STUB annual statement %d for contract %s not rendered",
            content.summary.taxYear,
            content.summary.contractId,
        )
        val sha = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return RenderedDocument(
            "stub-statement-${UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8))}",
            sha,
        )
    }
}
