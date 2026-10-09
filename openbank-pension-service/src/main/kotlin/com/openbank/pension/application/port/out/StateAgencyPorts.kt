// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.out

import com.openbank.pension.domain.statecontribution.ReturnReportLine
import com.openbank.pension.domain.statecontribution.ReturnResultLine
import com.openbank.pension.domain.statecontribution.ReturnStatus
import com.openbank.pension.domain.statecontribution.StateContributionReturn
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

enum class AgencyDocumentKind { CLAIM_APPLICATION, RETURN_REPORT }

/** One document for the state agency, already rendered in the channel's wire format. */
data class AgencyDocument(val kind: AgencyDocumentKind, val format: String, val fileName: String, val payload: String)

enum class TransmissionStatus {
    /** The channel confirmed delivery. */
    DELIVERED,

    /**
     * Nothing was transmitted. The document waits for an operator to file it through the agency's
     * own channel. This is not delivery and is never reported as one.
     */
    AWAITING_MANUAL_FILING,
}

data class TransmissionReceipt(val status: TransmissionStatus, val channelReference: String)

/**
 * Transport to the state agency (ADR-0334, #12382). The port does not depend on the transport
 * (SFTP, an API or a portal upload), because MF has published neither its transport nor its file
 * specification (research T2). Wire formats live in the claim channel adapters, and this port only
 * moves bytes. An implementation must fail closed: if it cannot deliver, it throws or answers
 * [TransmissionStatus.AWAITING_MANUAL_FILING]. It never pretends a delivery happened.
 */
interface StateAgencyGateway {
    suspend fun transmit(document: AgencyDocument): TransmissionReceipt
}

/** Wire format of the monthly return report and its result (CZ: ZDPS §18(4)-(6)). */
interface StateContributionReturnChannel {
    val format: String

    /** Renders the report. Fails if the channel is not configured to file. */
    fun render(month: YearMonth, lines: List<ReturnReportLine>): String

    fun parseResult(payload: String): List<ReturnResultLine>
}

/** A filed monthly return report (ZDPS §18(4)) and the evidence of what was filed. */
data class ReturnReport(
    val id: UUID,
    val month: YearMonth,
    val returnIds: List<UUID>,
    val payload: String,
    val channelReference: String?,
    val resultApplied: Boolean,
    val createdAt: Instant,
)

// One repository for the return lifecycle and its reports, which share a transaction (fileReportAtomically).
@Suppress("TooManyFunctions")
interface StateContributionReturnRepository {
    suspend fun bySourceKey(sourceKey: String): StateContributionReturn?

    /**
     * Stores [item] and claims [months] (contribution month → amount) for it in ONE transaction,
     * under a lock on the contract row. The (contract, month) key is unique across all returns, so
     * a month can be owed back only once. False if any month is already claimed by another return,
     * or if [item]'s source key already exists. In that case nothing is written.
     */
    suspend fun insertWithMonths(item: StateContributionReturn, months: Map<YearMonth, BigDecimal>): Boolean

    /** Contribution months of [contractId] already claimed by some return. */
    suspend fun coveredMonths(contractId: UUID): Set<YearMonth>

    suspend fun findById(id: UUID): StateContributionReturn?

    suspend fun byStatus(status: ReturnStatus): List<StateContributionReturn>

    suspend fun byContract(contractId: UUID): List<StateContributionReturn>

    suspend fun update(item: StateContributionReturn)

    /**
     * Stores [report] and moves every return it lists from DUE to REPORTED in ONE transaction. If any
     * listed return is no longer DUE, nothing is written and the answer is false.
     */
    suspend fun fileReportAtomically(report: ReturnReport, at: Instant): Boolean

    suspend fun findReport(id: UUID): ReturnReport?

    suspend fun reports(): List<ReturnReport>

    suspend fun markReportResultApplied(id: UUID)

    suspend fun setReportChannelReference(id: UUID, reference: String)
}
