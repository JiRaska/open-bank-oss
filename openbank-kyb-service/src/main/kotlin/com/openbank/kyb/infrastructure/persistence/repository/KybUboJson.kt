// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.persistence.repository

import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.kyb.domain.model.BeneficialOwner
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.UboFinding
import com.openbank.kyb.domain.model.UboSource
import java.time.Instant

/** Versioned stored shape: domain identifier construction remains validated on read. */
internal object KybUboJson {
    data class Doc(
        val schemaVersion: Int,
        val scheme: String,
        val identifier: String,
        val source: String,
        val owners: List<BeneficialOwner>,
        val registerStatements: List<String>,
        val threshold: Double,
        val registerName: String?,
        val sourceRef: String?,
        val fetchedAt: Instant,
    )

    fun write(finding: UboFinding): String = KybJson.mapper.writeValueAsString(
        Doc(
            schemaVersion = 1,
            scheme = finding.identifier.scheme.name,
            identifier = finding.identifier.value,
            source = finding.source.name,
            owners = finding.owners,
            registerStatements = finding.registerStatements,
            threshold = finding.threshold,
            registerName = finding.registerName,
            sourceRef = finding.sourceRef,
            fetchedAt = finding.fetchedAt,
        ),
    )

    fun read(json: String): UboFinding {
        val doc: Doc = KybJson.mapper.readValue(json)
        require(doc.schemaVersion == 1) { "unsupported UBO observation schema" }
        return UboFinding(
            identifier = LegalEntityIdentifier.of(IdentifierScheme.valueOf(doc.scheme), doc.identifier),
            source = UboSource.valueOf(doc.source),
            owners = doc.owners,
            registerStatements = doc.registerStatements,
            threshold = doc.threshold,
            registerName = doc.registerName,
            sourceRef = doc.sourceRef,
            fetchedAt = doc.fetchedAt,
        )
    }
}
