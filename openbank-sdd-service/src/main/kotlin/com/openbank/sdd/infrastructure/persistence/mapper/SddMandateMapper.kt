// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.persistence.mapper

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.sdd.domain.model.MandateAmendment
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.infrastructure.persistence.entity.SddMandateEntity
import jakarta.enterprise.context.ApplicationScoped

/** Domain ↔ entity mapping for the mandate aggregate; amendments (de)serialise as a JSON array. */
@ApplicationScoped
class SddMandateMapper(private val json: ObjectMapper) {

    fun toEntity(m: SddMandate): SddMandateEntity = SddMandateEntity().apply {
        id = m.id
        accountId = m.accountId
        debtorIban = m.debtorIban
        creditorIdentifier = m.creditorIdentifier
        umr = m.umr
        scheme = m.scheme
        sequenceType = m.sequenceType
        creditorName = m.creditorName
        debtorName = m.debtorName
        signatureDate = m.signatureDate
        status = m.status
        b2bConfirmed = m.b2bConfirmed
        lastCollectionDate = m.lastCollectionDate
        lastPreNotificationDate = m.lastPreNotificationDate
        createdAt = m.createdAt
        amendments = json.writeValueAsString(m.amendments)
    }

    fun toDomain(e: SddMandateEntity): SddMandate = SddMandate(
        id = e.id,
        accountId = e.accountId,
        debtorIban = e.debtorIban,
        creditorIdentifier = e.creditorIdentifier,
        umr = e.umr,
        scheme = e.scheme,
        sequenceType = e.sequenceType,
        creditorName = e.creditorName,
        debtorName = e.debtorName,
        signatureDate = e.signatureDate,
        status = e.status,
        b2bConfirmed = e.b2bConfirmed,
        lastCollectionDate = e.lastCollectionDate,
        lastPreNotificationDate = e.lastPreNotificationDate,
        createdAt = e.createdAt,
        amendments = json.readValue(e.amendments.ifBlank { "[]" }),
    )
}
