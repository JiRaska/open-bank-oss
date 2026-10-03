// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.entity

import com.openbank.lending.domain.model.GraphGuaranteeFact
import com.openbank.lending.domain.model.GraphGuaranteeProposal
import com.openbank.lending.domain.model.GraphGuaranteeStatus
import com.openbank.libs.domain.identifiers.Ids
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "lending_graph_guarantee")
class GraphGuaranteeEntity {
    @Id
    @Column(name = "guarantee_id")
    var guaranteeId: UUID = Ids.newId()

    @Column(name = "contract_id")
    lateinit var contractId: UUID

    @Column(name = "revision")
    var revision: Long = 1

    @Column(name = "supersedes_guarantee_id")
    var supersedesGuaranteeId: UUID? = null

    @Column(name = "loan_id")
    lateinit var loanId: UUID

    @Column(name = "guarantor_party_id")
    lateinit var guarantorPartyId: UUID

    @Column(name = "cap_amount", precision = 20, scale = 2)
    lateinit var capAmount: BigDecimal

    @Column(name = "currency", length = 3)
    lateinit var currency: String

    @Column(name = "coverage_fraction", precision = 7, scale = 6)
    lateinit var coverageFraction: BigDecimal

    @Column(name = "seniority")
    var seniority: Int = 1

    @Column(name = "valid_from")
    lateinit var validFrom: Instant

    @Column(name = "valid_to")
    var validTo: Instant? = null

    @Column(name = "source_document_id")
    lateinit var sourceDocumentId: UUID

    @Column(name = "source_sha256", length = 64)
    lateinit var sourceSha256: String

    @Column(name = "proposed_by", length = 128)
    lateinit var proposedBy: String

    @Column(name = "proposed_at")
    lateinit var proposedAt: Instant

    @Column(name = "status", length = 16)
    var status: String = GraphGuaranteeStatus.PENDING.name

    @Column(name = "decided_by", length = 128)
    var decidedBy: String? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    fun toFact() = GraphGuaranteeFact(
        guaranteeId = guaranteeId,
        proposal = GraphGuaranteeProposal(
            contractId, revision, supersedesGuaranteeId, loanId, guarantorPartyId,
            capAmount, currency, coverageFraction, seniority, validFrom, validTo,
            sourceDocumentId, sourceSha256,
        ),
        status = GraphGuaranteeStatus.valueOf(status),
        proposedBy = proposedBy,
        proposedAt = proposedAt,
        decidedBy = decidedBy,
        decidedAt = decidedAt,
    )

    companion object {
        fun pending(proposal: GraphGuaranteeProposal, actor: String, at: Instant) =
            GraphGuaranteeEntity().also { entity ->
                entity.contractId = proposal.contractId
                entity.revision = proposal.revision
                entity.supersedesGuaranteeId = proposal.supersedesGuaranteeId
                entity.loanId = proposal.loanId
                entity.guarantorPartyId = proposal.guarantorPartyId
                entity.capAmount = proposal.capAmount
                entity.currency = proposal.currency
                entity.coverageFraction = proposal.coverageFraction
                entity.seniority = proposal.seniority
                entity.validFrom = proposal.validFrom
                entity.validTo = proposal.validTo
                entity.sourceDocumentId = proposal.sourceDocumentId
                entity.sourceSha256 = proposal.sourceSha256
                entity.proposedBy = actor
                entity.proposedAt = at
            }
    }
}
