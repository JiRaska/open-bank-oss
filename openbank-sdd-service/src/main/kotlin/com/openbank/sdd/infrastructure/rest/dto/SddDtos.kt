// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.rest.dto

import com.openbank.sdd.application.port.`in`.AmendableField
import com.openbank.sdd.domain.authorise.AuthorisationResult
import com.openbank.sdd.domain.model.MandateAmendment
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import com.openbank.sdd.domain.refund.RefundDecision
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class RegisterMandateRequest(
    val accountId: UUID,
    val debtorIban: String,
    val creditorIdentifier: String,
    val umr: String,
    val scheme: SddScheme,
    val sequenceType: SequenceType,
    val creditorName: String,
    val debtorName: String,
    val signatureDate: LocalDate,
)

data class AmendMandateRequest(val field: AmendableField, val newValue: String)

data class DebtorControlsDto(
    val blockAll: Boolean = false,
    val blockedCreditors: Set<String> = emptySet(),
    val maxAmountPerCollection: BigDecimal? = null,
)

data class AuthoriseCollectionRequest(
    val creditorIdentifier: String,
    val umr: String,
    val scheme: SddScheme,
    val sequenceType: SequenceType,
    val amount: BigDecimal,
    val currency: String,
    val dueDate: LocalDate,
    val controls: DebtorControlsDto = DebtorControlsDto(),
)

data class AmendmentDto(val field: String, val oldValue: String, val newValue: String, val at: Instant) {
    companion object {
        fun of(a: MandateAmendment) = AmendmentDto(a.field, a.oldValue, a.newValue, a.at)
    }
}

data class MandateResponse(
    val id: UUID,
    val accountId: UUID,
    val debtorIban: String,
    val creditorIdentifier: String,
    val umr: String,
    val scheme: SddScheme,
    val sequenceType: SequenceType,
    val creditorName: String,
    val debtorName: String,
    val signatureDate: LocalDate,
    val status: String,
    val b2bConfirmed: Boolean,
    val lastCollectionDate: LocalDate?,
    val lastPreNotificationDate: LocalDate?,
    val createdAt: Instant,
    val amendments: List<AmendmentDto>,
) {
    companion object {
        fun of(m: SddMandate) = MandateResponse(
            id = m.id,
            accountId = m.accountId,
            debtorIban = m.debtorIban,
            creditorIdentifier = m.creditorIdentifier,
            umr = m.umr,
            scheme = m.scheme,
            sequenceType = m.sequenceType,
            creditorName = m.creditorName,
            debtorName = m.debtorName,
            signatureDate = m.signatureDate,
            status = m.status.name,
            b2bConfirmed = m.b2bConfirmed,
            lastCollectionDate = m.lastCollectionDate,
            lastPreNotificationDate = m.lastPreNotificationDate,
            createdAt = m.createdAt,
            amendments = m.amendments.map(AmendmentDto::of),
        )
    }
}

data class AuthorisationResponse(val decision: String, val reasonCode: String?, val reason: String?) {
    companion object {
        fun of(r: AuthorisationResult): AuthorisationResponse = when (r) {
            is AuthorisationResult.Accept -> AuthorisationResponse("ACCEPT", null, null)
            is AuthorisationResult.Reject -> AuthorisationResponse("REJECT", r.reasonCode, r.reason)
            is AuthorisationResult.Refuse -> AuthorisationResponse("REFUSE", r.reasonCode, r.reason)
        }
    }
}

data class RefundAssessmentResponse(
    val eligible: Boolean,
    val kind: String?,
    val reasonCode: String?,
    val reason: String?,
) {
    companion object {
        fun of(d: RefundDecision): RefundAssessmentResponse = when (d) {
            is RefundDecision.Eligible -> RefundAssessmentResponse(true, d.kind.name, d.reasonCode, null)
            is RefundDecision.Ineligible -> RefundAssessmentResponse(false, null, null, d.reason)
        }
    }
}
