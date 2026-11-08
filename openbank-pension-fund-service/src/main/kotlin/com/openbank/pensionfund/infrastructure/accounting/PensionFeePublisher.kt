// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.infrastructure.accounting

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.pensionfund.domain.model.ManagementFeeAccrual
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate
import java.util.UUID

data class FeePosting(
    val bookId: UUID,
    val fundId: UUID,
    val navId: UUID,
    val valuationDate: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val correctsNavId: UUID?,
)

data class PensionBookDescriptor(val kind: String, val bookId: UUID?)
data class PensionClosingDay(val businessDate: LocalDate, val status: String)
data class PensionCloseReport(
    val outcome: String,
    val journalsPosted: Int,
    val itemsProcessed: Int,
    val reason: String?,
    val exercisedJobs: Set<String>,
    val sourceRunId: String? = null,
)

@ApplicationScoped
class PensionFeePublisher(
    private val config: PensionAccountingConfig,
    private val receipts: FeeDeliveryReceipts,
    private val mapper: ObjectMapper,
    private val clients: PensionBookClients,
) : OutboxEventPublisher {
    override suspend fun publish(entry: OutboxEntry) {
        check(config.deliveryEnabled()) { "Pension accounting delivery is disabled" }
        check(entry.eventType == "PensionManagementFeeAccrued.v1") { "Unknown pension accounting event" }
        val fee = mapper.readValue(entry.payload, ManagementFeeAccrual::class.java)
        val company = config.companyBookId().orElseThrow { IllegalStateException("Company book id is missing") }
        val fundUrl = checkNotNull(config.funds()[fee.fundId.toString()]) { "Fund book destination is missing" }
        val companyUrl = config.companyUrl().orElseThrow { IllegalStateException("Company destination is missing") }
        check(fundUrl != companyUrl) { "Fund and company cannot share a ledger endpoint" }
        receipts.bind(fee.fundId, company)
        deliver(fee, "PENSION_FUND", fee.fundId, fundUrl, entry.synthetic)
        deliver(fee, "PENSION_COMPANY", company, companyUrl, entry.synthetic)
    }

    private suspend fun deliver(fee: ManagementFeeAccrual, kind: String, bookId: UUID, url: URI, synthetic: Boolean) {
        if (receipts.has(fee.navId, kind)) return
        val receipt = clients.open(url).use { client ->
            client.post(
                if (synthetic) "true" else null,
                FeePosting(
                    bookId,
                    fee.fundId,
                    fee.navId,
                    fee.valuationDate,
                    fee.amount,
                    fee.currency,
                    fee.correctsNavId,
                ),
            )
        }
        check(receipt.bookId == bookId && receipt.navId == fee.navId) {
            "Accounting receipt belongs to another source/book"
        }
        check(receipt.synthetic == synthetic) { "Accounting receipt lost the source population" }
        check(receipt.outcome == if (fee.amount.signum() == 0) "NO_POSTING" else "POSTED") { "Unexpected fee outcome" }
        check((receipt.outcome == "POSTED") == (receipt.journalId != null)) { "Missing accounting journal receipt" }
        receipts.save(kind, receipt)
    }
}
